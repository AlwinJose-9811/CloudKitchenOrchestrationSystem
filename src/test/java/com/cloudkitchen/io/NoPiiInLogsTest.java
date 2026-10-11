package com.cloudkitchen.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

/** FR-FIL-02, NFR-OBS-01: a full run with planted customer data and real secrets leaves none of them in orders.log or in the application log. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoPiiInLogsTest {

    private static final String NAME_1 = "ZZSENTINEL-Wilhelmina Quartermain-1";
    private static final String NAME_2 = "ZZSENTINEL-Bartholomew O'Neill-2";

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private OrderRequest order(String ref, String name, long itemId, int quantity) {
        return new OrderRequest(OrderSource.ZOMATO, ref, "BIRYANI", name, new BigDecimal("4.50"), PaymentMode.PREPAID_AGGREGATOR,
                List.of(new OrderItemRequest(itemId, quantity)));
    }

    @Test
    void theApplicationLogPatternsCarryTheThreadName() throws Exception {
        String xml;
        try (var in = NoPiiInLogsTest.class.getResourceAsStream("/logback.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        long patterns = xml.lines().filter(l -> l.contains("<pattern>")).count();
        assertEquals(2, patterns, "console and rolling file");
        assertTrue(xml.lines().filter(l -> l.contains("<pattern>")).allMatch(l -> l.contains("[%thread]")), "NFR-OBS-01: the thread name is in every pattern");
        assertFalse(xml.contains("%X{") || xml.contains("%mdc"), "no request data is added to log lines");
    }

    @Test
    void customerNamesPasswordsAndTheHmacSecretNeverAppearInAnyLogOutput() throws Exception {
        h.reset();
        Properties properties = AppFixture.properties(h.db, "snapshot.interval.seconds", "1");
        List<String> secrets = List.of(properties.getProperty("bootstrap.admin.password"), properties.getProperty("bootstrap.staff.password"),
                properties.getProperty("snapshot.hmac.secret"), properties.getProperty("db.password"));
        AppConfig config = AppConfig.fromProperties(properties);
        long raita = h.t.itemId("Raita");

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        ch.qos.logback.classic.Logger ours = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.cloudkitchen");
        Level before = root.getLevel();
        Level oursBefore = ours.getLevel();
        root.setLevel(Level.DEBUG); // the most verbose level a developer could switch on
        ours.setLevel(Level.DEBUG);
        AppContext app = AppContext.create(config, h.db.jdbcUrl(), Clock.systemUTC(), null, false);
        Path ordersLog;
        try {
            assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
            ordersLog = app.eventLog().path();
            SubmissionResult one = app.orders().submitOrder(order("PII-1", NAME_1, raita, 1));
            SubmissionResult two = app.orders().submitOrder(order("PII-2", NAME_2, raita, 2));
            assertEquals(SubmissionResult.Outcome.ACCEPTED, one.outcome());
            app.orders().submitOrder(order("PII-1", NAME_1, raita, 1)); // a duplicate
            app.orders().submitOrder(order("PII-BAD", NAME_2, raita, 0)); // invalid quantity: rejected with the name in the request
            app.orders().submitOrder(order("PII-UNKNOWN", NAME_1, 9_999_999L, 1)); // unknown item
            app.orders().cancelOrder(two.orderId(), "staff");
            app.simulator().start(6000);
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> app.simulator().produced() >= 5));
            app.simulator().stop();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
                try {
                    return h.t.number("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED','FAILED')") == 0;
                } catch (Exception e) {
                    return false;
                }
            }));
            app.snapshots().writeAndPrune();
            app.reports().exportDaily(LocalDate.now(ZoneOffset.UTC));
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders WHERE customer_name = ?", NAME_1), "the sentinel really is in the database");
        } finally {
            app.close();
            root.detachAppender(captured);
            root.setLevel(before);
            ours.setLevel(oursBefore);
        }
        assertTrue(Files.size(ordersLog) > 0, "orders.log has content");

        List<String> haystacks = new ArrayList<>();
        haystacks.add(Files.readString(ordersLog, StandardCharsets.UTF_8));
        for (ILoggingEvent event : captured.list) {
            String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            haystacks.add(text);
        }
        haystacks.add(config.toString());
        assertTrue(captured.list.size() >= 10, "a substantial amount of application log was captured: " + captured.list.size());
        List<String> forbidden = new ArrayList<>(secrets);
        forbidden.addAll(List.of("ZZSENTINEL", "Wilhelmina", "Quartermain", "Bartholomew", "O'Neill"));
        for (String bad : forbidden) {
            for (String text : haystacks) {
                assertFalse(text.contains(bad), "found '" + bad + "' in: " + (text.length() > 300 ? text.substring(0, 300) : text));
            }
        }
    }
}
