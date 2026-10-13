package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-INV-07 (UI): every item with its stock and reorder level, and the text flag LOW where stock is at or below the reorder level. */
class InventoryWebTest extends WebTestBase {

    @BeforeEach
    void stock() throws Exception {
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
    }

    private static String row(String html, String item) {
        Matcher m = Pattern.compile("(?s)<tr>\\s*<td>" + Pattern.quote(item) + "</td>.*?</tr>").matcher(html);
        assertTrue(m.find(), item + " in " + html);
        return m.group().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    @Test
    void everyMenuItemIsListedWithItsStockAndReorderLevelForBothRoles() throws Exception {
        for (HttpTestClient browser : new HttpTestClient[] {app.staffBrowser(), app.adminBrowser()}) {
            Reply page = browser.get("/inventory");
            assertEquals(200, page.status());
            assertEquals("Chicken Biryani BIRYANI 100 10 OK", row(page.body(), "Chicken Biryani"));
            assertEquals(15, page.body().split("<tr>\\s*<td>", -1).length - 1, "three brands with five items each (DATABASE_DESIGN 3.9)");
        }
    }

    @Test
    void lowStockIsFlaggedInTextAtAndBelowTheReorderLevelAndNotAbove() throws Exception {
        execute("UPDATE inventory SET stock_qty = 10 WHERE item_id = ?", itemId("Cola"));
        execute("UPDATE inventory SET stock_qty = 0 WHERE item_id = ?", itemId("Margherita"));
        execute("UPDATE inventory SET stock_qty = 11 WHERE item_id = ?", itemId("Chicken Biryani"));
        String page = app.staffBrowser().get("/inventory").body();
        assertTrue(row(page, "Cola").endsWith("10 10 LOW"), row(page, "Cola"));
        assertTrue(row(page, "Margherita").endsWith("0 10 LOW"), row(page, "Margherita"));
        assertTrue(row(page, "Chicken Biryani").endsWith("11 10 OK"), row(page, "Chicken Biryani"));
    }

    @Test
    void theReadOnlyPageOffersNoStateChangingForm() {
        String body = app.adminBrowser().get("/inventory").body();
        assertFalse(body.contains("action=\"/admin/stock\""), "stock editing is an administrator page of a later checkpoint");
        assertEquals(1, body.split("<form ", -1).length - 1, "only the logout form of the header");
    }

    @Test
    void theInventoryIsProtected() {
        HttpTestClient.assertRedirect(app.browser().get("/inventory"), "/login");
    }
}
