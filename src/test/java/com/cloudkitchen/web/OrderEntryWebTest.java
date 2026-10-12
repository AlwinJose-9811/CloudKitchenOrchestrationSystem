package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-ORD-08, SDD 10.2 routes 8 and 9: manual entry of an in-house order by either role. The submission goes through the same
 * {@code OrderService} as every other source; the page never decides the source, the reference or a price. Every outcome ends
 * in a redirect, a refusal keeps what was typed (once) and creates nothing, and the CSRF and login checks stand in front.
 */
class OrderEntryWebTest extends WebTestBase {

    private static final Pattern ORDER_ID = Pattern.compile("/orders/view\\?id=(\\d+)");

    private HttpTestClient staff;
    private HttpTestClient admin;

    @Override
    protected String[] overrides() {
        return new String[] {"station.queue.capacity", "2", "queue.offer.timeout.ms", "200"};
    }

    @BeforeEach
    void freshState() throws Exception {
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        execute("UPDATE menu_items SET active = 1");
        if (staff == null) {
            staff = app.staffBrowser();
            admin = app.adminBrowser();
        }
        staff.get("/orders");
        admin.get("/orders");
        resumeAll();
    }

    @AfterEach
    void accepting() {
        app.appContext().orders().setAccepting(true);
        resumeAll();
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    /** A valid form for one Chicken Biryani, with a field overridden or removed by the caller. */
    private Map<String, String> form(HttpTestClient browser) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("brandCode", "BIRYANI");
        f.put("customerName", "Asha Rao");
        f.put("deliveryDistanceKm", "3.50");
        f.put("paymentMode", "CASH");
        f.put("_csrf", TestApp.tokenOf(browser));
        return f;
    }

    private Reply place(HttpTestClient browser, Map<String, String> fields, String item, String quantity) {
        return browser.post("/orders/new", HttpTestClient.encode(fields) + "&itemId=" + item + "&quantity=" + quantity);
    }

    private long orderIdOf(Reply reply) {
        Matcher m = ORDER_ID.matcher(reply.location());
        assertTrue(m.find(), reply.location());
        return Long.parseLong(m.group(1));
    }

    @Test
    void theFormOffersTheBrandsAndOnlyAfterChoosingOneItsActiveItemsWithoutPrices() throws Exception {
        execute("UPDATE menu_items SET active = 0 WHERE name = 'Raita'");
        Reply first = staff.get("/orders/new");
        assertEquals(200, first.status());
        assertTrue(first.body().contains("Spice Route Biryani") && first.body().contains("value=\"BIRYANI\""));
        assertFalse(first.body().contains("id=\"order-form\""), "no menu before a brand is chosen");

        Reply menu = staff.get("/orders/new?brandCode=BIRYANI");
        String text = plain(menu.body());
        assertTrue(menu.body().contains("id=\"order-form\"") && menu.body().contains("name=\"_csrf\""));
        assertTrue(text.contains("Chicken Biryani") && text.contains("249.00"));
        assertFalse(text.contains("Raita"), "an inactive item is not offered");
        assertFalse(menu.body().contains("name=\"source\"") || menu.body().contains("name=\"externalRef\"") || menu.body().contains("name=\"price\""));
        assertFalse(menu.body().contains("PREPAID_AGGREGATOR"), "an in-house order is paid at the counter, by UPI or by card");

        assertFalse(staff.get("/orders/new?brandCode=NOPE").body().contains("id=\"order-form\""), "an unknown brand shows no menu");
    }

    @Test
    void anOrderIsPlacedAsInHouseWithAGeneratedReferenceAndTheStockIsDeducted() throws Exception {
        long before = count("SELECT COUNT(*) FROM orders");
        Reply reply = place(staff, form(staff), Long.toString(itemId("Chicken Biryani")), "2");
        long id = orderIdOf(reply);
        assertEquals(before + 1, count("SELECT COUNT(*) FROM orders"));
        assertEquals("IN_HOUSE", text("SELECT source FROM orders WHERE id = ?", id));
        assertTrue(text("SELECT external_ref FROM orders WHERE id = ?", id).startsWith("IH-"));
        assertEquals(98, stockOf("Chicken Biryani"));
        assertEquals("249.00", text("SELECT unit_price FROM order_items WHERE order_id = ?", id), "the price comes from the menu");

        Reply page = staff.get("/orders/view?id=" + id);
        assertTrue(plain(page.body()).contains("Order " + id + " was accepted"), page.body());
        assertTrue(page.body().contains("flash-success"));
        assertFalse(plain(staff.get("/orders/view?id=" + id).body()).contains("was accepted"), "the message is shown once");
    }

    @Test
    void theAdministratorMayEnterAnOrderToo() throws Exception {
        Reply reply = place(admin, form(admin), Long.toString(itemId("Veg Biryani")), "1");
        long id = orderIdOf(reply);
        assertEquals("IN_HOUSE", text("SELECT source FROM orders WHERE id = ?", id));
    }

    @Test
    void theSourceTheReferenceAndAPriceInTheRequestAreIgnored() throws Exception {
        Map<String, String> f = form(staff);
        f.put("source", "SWIGGY");
        f.put("externalRef", "HACK-1");
        f.put("price", "0.01");
        f.put("totalAmount", "0.01");
        long id = orderIdOf(place(staff, f, Long.toString(itemId("Chicken Biryani")), "1"));
        assertEquals("IN_HOUSE", text("SELECT source FROM orders WHERE id = ?", id));
        assertTrue(text("SELECT external_ref FROM orders WHERE id = ?", id).startsWith("IH-"));
        assertEquals("249.00", text("SELECT unit_price FROM order_items WHERE order_id = ?", id));
    }

    @Test
    void aRefusedOrderCreatesNothingKeepsTheInputOnceAndExplainsEveryProblem() throws Exception {
        long before = count("SELECT COUNT(*) FROM orders");
        Map<String, String> f = form(staff);
        f.put("customerName", "");
        f.put("deliveryDistanceKm", "far away");
        f.put("paymentMode", "PREPAID_AGGREGATOR");
        Reply reply = place(staff, f, Long.toString(itemId("Chicken Biryani")), "3");
        HttpTestClient.assertRedirect(reply, "/orders/new");
        assertEquals("", reply.body());
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
        assertEquals(100, stockOf("Chicken Biryani"));

        Reply page = staff.get("/orders/new");
        String text = plain(page.body());
        assertTrue(page.body().contains("flash-error"));
        assertTrue(page.body().contains("id=\"problems\""));
        assertTrue(text.contains("customer name") && text.contains("distance") && text.contains("payment mode"), text);
        assertTrue(page.body().contains("id=\"order-form\""), "the brand stays chosen");
        assertTrue(page.body().contains("value=\"far away\""), "what was typed is shown again");
        assertTrue(page.body().contains("value=\"3\""), "and so is the quantity");
        assertFalse(staff.get("/orders/new").body().contains("far away"), "the retained input is consumed by the next page");
    }

    @Test
    void noItemsOrAWrongBrandOrAnInactiveItemIsRefused() throws Exception {
        long before = count("SELECT COUNT(*) FROM orders");
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(itemId("Chicken Biryani")), "0"), "/orders/new");
        assertTrue(plain(staff.get("/orders/new").body()).toLowerCase().contains("item"));

        Map<String, String> otherBrand = form(staff);
        otherBrand.put("brandCode", "PIZZA");
        HttpTestClient.assertRedirect(place(staff, otherBrand, Long.toString(itemId("Chicken Biryani")), "1"), "/orders/new");
        staff.get("/orders/new");

        execute("UPDATE menu_items SET active = 0 WHERE name = 'Veg Biryani'");
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(itemId("Veg Biryani")), "1"), "/orders/new");
        staff.get("/orders/new");
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void textThatIsNotAQuantityIsShownAsAnErrorAndMalformedListsAre400() throws Exception {
        long before = count("SELECT COUNT(*) FROM orders");
        long item = itemId("Chicken Biryani");
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(item), "two"), "/orders/new");
        assertTrue(plain(staff.get("/orders/new").body()).contains("must be a whole number"));
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(item), "-1"), "/orders/new");
        staff.get("/orders/new");

        assertEquals(400, place(staff, form(staff), "abc", "1").status(), "an item id is written by the page");
        Reply unequal = staff.post("/orders/new", HttpTestClient.encode(form(staff)) + "&itemId=" + item + "&itemId=" + item + "&quantity=1");
        assertEquals(400, unequal.status());
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void notEnoughStockIsRefusedWithoutChangingTheStock() throws Exception {
        execute("UPDATE inventory SET stock_qty = 1 WHERE item_id = ?", itemId("Chicken Biryani"));
        long before = count("SELECT COUNT(*) FROM orders");
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(itemId("Chicken Biryani")), "5"), "/orders/new");
        Reply page = staff.get("/orders/new");
        assertTrue(plain(page.body()).contains("not enough stock"), plain(page.body()));
        assertEquals(1, stockOf("Chicken Biryani"));
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void whileTheSystemIsNotAcceptingOrdersTheUserIsToldAndNothingIsCreated() throws Exception {
        app.appContext().orders().setAccepting(false);
        long before = count("SELECT COUNT(*) FROM orders");
        HttpTestClient.assertRedirect(place(staff, form(staff), Long.toString(itemId("Chicken Biryani")), "1"), "/orders/new");
        assertTrue(plain(staff.get("/orders/new").body()).contains("not accepting orders"));
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void aFullQueueFailsTheOrderRestoresItsStockAndSendsTheUserToTheOrderPage() throws Exception {
        pauseAll();
        long item = itemId("Chicken Biryani");
        place(staff, form(staff), Long.toString(item), "1");
        place(staff, form(staff), Long.toString(item), "1");
        assertEquals(98, stockOf("Chicken Biryani"));
        Reply third = place(staff, form(staff), Long.toString(item), "1");
        long id = orderIdOf(third);
        assertEquals("FAILED", statusOf(id));
        assertEquals(98, stockOf("Chicken Biryani"), "the third order's stock was given back");
        Reply page = staff.get("/orders/view?id=" + id);
        assertTrue(plain(page.body()).contains("queue is full"), plain(page.body()));
        assertTrue(page.body().contains("flash-error"));
    }

    @Test
    void theCustomerNameIsEscapedWhereItIsShown() throws Exception {
        Map<String, String> f = form(staff);
        f.put("customerName", "<script>alert(1)</script>");
        long id = orderIdOf(place(staff, f, Long.toString(itemId("Chicken Biryani")), "1"));
        String page = staff.get("/orders/view?id=" + id).body();
        assertFalse(page.contains("<script>alert(1)</script>"));

        Map<String, String> bad = form(staff);
        bad.put("customerName", "x\"><script>alert(2)</script>");
        bad.put("deliveryDistanceKm", "nope");
        place(staff, bad, Long.toString(itemId("Chicken Biryani")), "1");
        assertFalse(staff.get("/orders/new").body().contains("<script>alert(2)</script>"), "retained input is escaped too");
    }

    @Test
    void csrfLoginAndTheFormMethodAreChecked() throws Exception {
        long before = count("SELECT COUNT(*) FROM orders");
        String body = "brandCode=BIRYANI&customerName=Asha&deliveryDistanceKm=3&paymentMode=CASH&itemId=" + itemId("Chicken Biryani") + "&quantity=1";
        assertEquals(403, staff.post("/orders/new", body).status(), "no token");
        assertEquals(403, staff.post("/orders/new", body + "&_csrf=wrong").status(), "wrong token");
        HttpTestClient.assertRedirect(app.browser().get("/orders/new"), "/login");
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.post("/orders/new", body + "&_csrf=" + token), "/login");
        assertEquals(before, count("SELECT COUNT(*) FROM orders"));
        assertNotNull(OrderSource.IN_HOUSE);
    }
}
