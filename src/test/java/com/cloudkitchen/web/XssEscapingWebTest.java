package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-08: text that comes from outside (a customer name, an external reference, an item name, a station name, an actor, a
 * note, an inventory item name, a flash message, a query parameter) is shown as text on every page that shows it, never as
 * markup. The hostile values are written into the database directly, because the order validator would refuse some of them:
 * the pages must be safe even when the data is not.
 */
class XssEscapingWebTest extends WebTestBase {

    private static final String SCRIPT = "<script>alert(1)</script>";
    private static final String ESCAPED_SCRIPT = "&lt;script&gt;alert(1)&lt;/script&gt;";
    private static final String ATTRIBUTE_BREAK = "\"><img src=x onerror=alert(2)>";

    private static void assertSafe(String page, String what) {
        String lower = page.toLowerCase();
        assertFalse(lower.contains("<script"), what + ": a script element reached the page");
        assertFalse(lower.contains("<img"), what + ": an injected image reached the page");
        assertFalse(java.util.regex.Pattern.compile("<[^>]*\\son(error|mouseover)\\s*=").matcher(lower).find(),
                what + ": an event-handler attribute reached the page inside a tag");
    }

    @Test
    void aHostileCustomerNameIsShownAsTextOnTheOrderPage() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "XS-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        execute("UPDATE orders SET customer_name = ? WHERE id = ?", SCRIPT, id);
        String page = app.staffBrowser().get("/orders/view?id=" + id).body();
        assertSafe(page, "customer name");
        assertTrue(page.contains(ESCAPED_SCRIPT), page);
    }

    @Test
    void hostileItemNamesActorsAndNotesAreShownAsTextOnTheOrderPage() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "XS-2", "BIRYANI", "Asha", "Chicken Biryani", 1);
        execute("UPDATE order_items SET item_name = ? WHERE order_id = ?", ATTRIBUTE_BREAK, id);
        execute("UPDATE order_status_history SET actor = ?, note = ? WHERE order_id = ?", SCRIPT, "<b onmouseover=alert(3)>note</b>", id);
        HttpTestClient staff = app.staffBrowser();
        String detail = staff.get("/orders/view?id=" + id).body();
        assertSafe(detail, "order page");
        assertTrue(detail.contains(ESCAPED_SCRIPT));
        assertTrue(detail.contains("&lt;b onmouseover=alert(3)&gt;note&lt;/b&gt;"), detail);
        assertFalse(detail.contains("<b onmouseover"));
        assertTrue(detail.contains("&gt;&lt;img src=x onerror=alert(2)&gt;"), "the quote that tried to leave the attribute is text: " + detail);
    }

    @Test
    void aHostileExternalReferenceIsEscapedInTheListAndTheDomainRefusesToLoadItOnTheOrderPage() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "XS-3", "BIRYANI", "Asha", "Chicken Biryani", 1);
        execute("UPDATE orders SET external_ref = ? WHERE id = ?", SCRIPT, id);
        try {
            HttpTestClient staff = app.staffBrowser();
            String list = staff.get("/orders").body();
            assertSafe(list, "order list");
            assertTrue(list.contains(ESCAPED_SCRIPT), "the list reads the row as it is and escapes it");
            Reply detail = staff.get("/orders/view?id=" + id);
            assertEquals(500, detail.status(), "an order that breaks the domain rules is not rendered at all");
            assertSafe(detail.body(), "order page of the invalid row");
            assertFalse(detail.body().contains("alert(1)"), "and its content is not echoed in the error page");
        } finally {
            execute("UPDATE orders SET external_ref = 'XS-3' WHERE id = ?", id);
        }
    }

    @Test
    void aHostileStationNameAndInventoryItemNameAreEscapedOnTheListInventoryAndDashboard() throws Exception {
        execute("UPDATE menu_items SET name = ? WHERE name = 'Cola'", SCRIPT);
        execute("UPDATE inventory SET stock_qty = 1, reorder_level = 10 WHERE item_id = (SELECT id FROM menu_items WHERE name = ?)", SCRIPT);
        try {
            HttpTestClient staff = app.staffBrowser();
            for (String path : List.of("/inventory", "/dashboard")) {
                String page = staff.get(path).body();
                assertSafe(page, path);
                assertTrue(page.contains(ESCAPED_SCRIPT), path);
            }
        } finally {
            execute("UPDATE menu_items SET name = 'Cola' WHERE name = ?", SCRIPT);
            execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        }
    }

    @Test
    void aHostileUserNameInTheHeaderAndAFlashMessageAreEscaped() throws Exception {
        String username = "<i>x</i>\"'";
        execute("UPDATE staff_users SET username = ? WHERE username = ?", username, app.staffUsername());
        try {
            HttpTestClient browser = app.browser();
            String token = HttpTestClient.extractCsrf(browser.get("/login").body());
            Reply login = browser.postForm("/login", java.util.Map.of("username", username, "password", app.staffPassword(), "_csrf", token));
            HttpTestClient.assertRedirect(login, "/dashboard");
            String page = browser.get("/orders").body();
            assertFalse(page.contains("<i>x</i>"), page);
            assertTrue(page.contains("&lt;i&gt;x&lt;/i&gt;"), page);
        } finally {
            execute("UPDATE staff_users SET username = ? WHERE username = ?", app.staffUsername(), username);
        }
    }

    @Test
    void queryParametersAreNeverEchoedIntoThePage() {
        HttpTestClient staff = app.staffBrowser();
        String hostile = URLEncoder.encode(SCRIPT, StandardCharsets.UTF_8);
        for (String path : List.of("/orders?status=" + hostile, "/orders?source=" + hostile, "/orders?page=" + hostile,
                "/orders/view?id=" + hostile)) {
            Reply reply = staff.get(path);
            assertEquals(400, reply.status(), path);
            assertFalse(reply.body().contains("alert(1)"), path + " echoed its parameter");
        }
        Reply filtered = staff.get("/orders?status=ROUTED&source=SWIGGY");
        assertSafe(filtered.body(), "filtered list");
        assertTrue(filtered.body().contains("<option value=\"ROUTED\" selected>ROUTED</option>"), "the selected filter value is rendered from the enum, not from the request");
    }

    @Test
    void theLoginPageDoesNotEchoTheSubmittedUsername() {
        HttpTestClient browser = app.browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        browser.postForm("/login", java.util.Map.of("username", SCRIPT, "password", "x", "_csrf", token));
        String page = browser.get("/login").body();
        assertSafe(page, "login page after a failure");
        assertFalse(page.contains("alert(1)"));
    }
}
