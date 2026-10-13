package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-INV-08, FR-INV-09, SDD 10.2 routes 12 to 15: the administrator lists and changes the menu and the stock. Every refusal
 * (a value that is not in the exact form, a rule of the service, an unknown item) is a redirect with a flash message and
 * changes nothing; staff, visitors and requests without a token never reach the services.
 */
class AdminMenuStockWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient staff;

    @BeforeEach
    void freshState() throws Exception {
        execute("DELETE FROM inventory WHERE item_id IN (SELECT id FROM menu_items WHERE name LIKE 'C5 %')");
        execute("DELETE FROM menu_items WHERE name LIKE 'C5 %'");
        execute("UPDATE inventory SET stock_qty = 50, reorder_level = 10");
        execute("UPDATE menu_items SET active = 1, price = 249.00 WHERE name = 'Chicken Biryani'");
        if (admin == null) {
            admin = app.adminBrowser();
            staff = app.staffBrowser();
        }
        admin.get("/orders");
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private Reply post(HttpTestClient browser, String path, Map<String, String> fields) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", TestApp.tokenOf(browser));
        return browser.postForm(path, form);
    }

    private Map<String, String> create(String name, String price) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("action", "create");
        f.put("stationCode", "BURGER");
        f.put("name", name);
        f.put("price", price);
        f.put("initialStock", "7");
        f.put("reorderLevel", "3");
        return f;
    }

    private String flashAfter(Reply reply, String page) {
        HttpTestClient.assertRedirect(reply, page);
        return plain(admin.get(page).body());
    }

    // ---- menu ----

    @Test
    void theMenuPageListsEveryItemWithItsBrandAndOneFormPerItem() {
        Reply page = admin.get("/admin/menu");
        assertEquals(200, page.status());
        String text = plain(page.body());
        assertTrue(text.contains("Chicken Biryani") && text.contains("Spice Route Biryani") && text.contains("Burger Barn"));
        assertTrue(page.body().contains("name=\"action\" value=\"update\"") && page.body().contains("id=\"create-form\""));
        assertTrue(page.body().contains("name=\"_csrf\""));
    }

    @Test
    void anItemIsCreatedWithItsStockRowAndShowsInTheOrderForm() throws Exception {
        String text = flashAfter(post(admin, "/admin/menu", create("C5 Veggie Burger", "129.50")), "/admin/menu");
        assertTrue(text.contains("added to the menu"), text);
        long id = itemId("C5 Veggie Burger");
        assertEquals("129.50", text("SELECT price FROM menu_items WHERE id = ?", id));
        assertEquals(7, stockOf("C5 Veggie Burger"));
        assertEquals(3, count("SELECT reorder_level FROM inventory WHERE item_id = ?", id));
        assertTrue(plain(staff.get("/orders/new?brandCode=BURGER").body()).contains("C5 Veggie Burger"));
        assertTrue(plain(admin.get("/admin/stock").body()).contains("C5 Veggie Burger"));
    }

    @Test
    void createRefusesBadValuesAndChangesNothing() throws Exception {
        long before = count("SELECT COUNT(*) FROM menu_items");
        for (String price : new String[] {"free", "-5", "12.345", "100000", "1e3", "", "٤٠"}) {
            String text = flashAfter(post(admin, "/admin/menu", create("C5 Bad " + price.length(), price)), "/admin/menu");
            assertTrue(text.contains("price"), price + ": " + text);
        }
        Map<String, String> noStock = create("C5 NoStock", "10");
        noStock.put("initialStock", "-1");
        assertTrue(flashAfter(post(admin, "/admin/menu", noStock), "/admin/menu").contains("Stock and reorder level"));
        Map<String, String> text = create("C5 Text", "10");
        text.put("initialStock", "many");
        assertTrue(flashAfter(post(admin, "/admin/menu", text), "/admin/menu").contains("whole numbers"));
        assertTrue(flashAfter(post(admin, "/admin/menu", create("   ", "10")), "/admin/menu").contains("name"));
        Map<String, String> brand = create("C5 Brand", "10");
        brand.put("stationCode", "NOPE");
        assertTrue(flashAfter(post(admin, "/admin/menu", brand), "/admin/menu").contains("brand"));
        assertTrue(flashAfter(post(admin, "/admin/menu", create("Chicken Biryani", "10").entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), "stationCode".equals(e.getKey()) ? "BIRYANI" : e.getValue()), Map::putAll)), "/admin/menu")
                .contains("already has an item"));
        assertEquals(before, count("SELECT COUNT(*) FROM menu_items"));
    }

    @Test
    void updateChangesThePriceAndTheActiveFlagAndAnInactiveItemLeavesTheOrderForm() throws Exception {
        long id = itemId("Chicken Biryani");
        String text = flashAfter(post(admin, "/admin/menu", Map.of("action", "update", "itemId", Long.toString(id), "price", "255.5", "active", "true")), "/admin/menu");
        assertTrue(text.contains("active at 255.50"), text);
        assertEquals("255.50", text("SELECT price FROM menu_items WHERE id = ?", id));

        flashAfter(post(admin, "/admin/menu", Map.of("action", "update", "itemId", Long.toString(id), "price", "255.50")), "/admin/menu");
        assertEquals("0", text("SELECT active FROM menu_items WHERE id = ?", id), "an unchecked box sends nothing and means inactive");
        assertFalse(plain(staff.get("/orders/new?brandCode=BIRYANI").body()).contains("Chicken Biryani"));
    }

    @Test
    void updateRefusesABadPriceAndAnUnknownItem() throws Exception {
        long id = itemId("Chicken Biryani");
        assertTrue(flashAfter(post(admin, "/admin/menu", Map.of("action", "update", "itemId", Long.toString(id), "price", "-1", "active", "true")), "/admin/menu").contains("price"));
        assertTrue(flashAfter(post(admin, "/admin/menu", Map.of("action", "update", "itemId", Long.toString(id), "price", "abc", "active", "true")), "/admin/menu").contains("price"));
        assertEquals("249.00", text("SELECT price FROM menu_items WHERE id = ?", id));
        assertTrue(flashAfter(post(admin, "/admin/menu", Map.of("action", "update", "itemId", "999999", "price", "10", "active", "true")), "/admin/menu").contains("does not exist"));
    }

    @Test
    void anUnknownActionOrAMalformedItemIdIsA400() {
        assertEquals(400, post(admin, "/admin/menu", Map.of("action", "delete", "itemId", "1")).status());
        assertEquals(400, post(admin, "/admin/menu", Map.of("itemId", "1")).status());
        assertEquals(400, post(admin, "/admin/menu", Map.of("action", "update", "itemId", "one", "price", "1")).status());
        assertEquals(400, post(admin, "/admin/menu", Map.of("action", "update", "price", "1")).status());
    }

    @Test
    void itemNamesAreEscapedOnBothAdminPages() throws Exception {
        flashAfter(post(admin, "/admin/menu", create("C5 <img src=x onerror=alert(1)>", "10")), "/admin/menu");
        assertFalse(admin.get("/admin/menu").body().contains("<img src=x"));
        assertFalse(admin.get("/admin/stock").body().contains("<img src=x"));
        assertFalse(staff.get("/inventory").body().contains("<img src=x"));
    }

    // ---- stock ----

    @Test
    void theStockPageListsEveryItemWithAFormAndTheLowFlag() throws Exception {
        execute("UPDATE inventory SET stock_qty = 2 WHERE item_id = ?", itemId("Raita"));
        Reply page = admin.get("/admin/stock");
        assertEquals(200, page.status());
        assertTrue(plain(page.body()).contains("Raita") && page.body().contains("name=\"stockQty\"") && page.body().contains("name=\"reorderLevel\""));
        assertTrue(page.body().contains("<strong class=\"low\">LOW</strong>"));
    }

    @Test
    void theStockAndTheReorderLevelAreReplaced() throws Exception {
        long id = itemId("Raita");
        String text = flashAfter(post(admin, "/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "120", "reorderLevel", "15")), "/admin/stock");
        assertTrue(text.contains("is now 120 with reorder level 15"), text);
        assertEquals(120, stockOf("Raita"));
        assertEquals(15, count("SELECT reorder_level FROM inventory WHERE item_id = ?", id));
        flashAfter(post(admin, "/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "0", "reorderLevel", "0")), "/admin/stock");
        assertEquals(0, stockOf("Raita"), "zero is a valid quantity");
    }

    @Test
    void negativeNotANumberTooLargeAndUnknownValuesChangeNothing() throws Exception {
        long id = itemId("Raita");
        for (String bad : new String[] {"-1", "NaN", "1.5", "ten", "", " ", "1000001", "99999999999", "٣"}) {
            String text = flashAfter(post(admin, "/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", bad, "reorderLevel", "5")), "/admin/stock");
            assertTrue(text.contains("must be") , "[" + bad + "] " + text);
            assertEquals(50, stockOf("Raita"), "[" + bad + "]");
        }
        assertTrue(flashAfter(post(admin, "/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "5", "reorderLevel", "-2")), "/admin/stock").contains("must be"));
        assertEquals(10, count("SELECT reorder_level FROM inventory WHERE item_id = ?", id));
        assertTrue(flashAfter(post(admin, "/admin/stock", Map.of("itemId", "999999", "stockQty", "5", "reorderLevel", "5")), "/admin/stock").contains("does not exist"));
        assertEquals(400, post(admin, "/admin/stock", Map.of("itemId", "x", "stockQty", "5", "reorderLevel", "5")).status());
        assertEquals(400, post(admin, "/admin/stock", Map.of("stockQty", "5", "reorderLevel", "5")).status());
    }

    // ---- authorization and CSRF for all four routes ----

    @Test
    void staffAreRefusedAndVisitorsSentToTheLoginPageAndNothingChanges() throws Exception {
        long id = itemId("Raita");
        for (String path : new String[] {"/admin/menu", "/admin/stock"}) {
            assertEquals(403, staff.get(path).status(), path);
            HttpTestClient.assertRedirect(app.browser().get(path), "/login");
        }
        assertEquals(403, post(staff, "/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "1", "reorderLevel", "1")).status());
        assertEquals(403, post(staff, "/admin/menu", create("C5 Staff", "10")).status());
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "1", "reorderLevel", "1", "_csrf", token)), "/login");
        assertEquals(50, stockOf("Raita"));
        assertEquals(0, count("SELECT COUNT(*) FROM menu_items WHERE name = 'C5 Staff'"));
    }

    @Test
    void aRequestWithoutAValidTokenIs403AndChangesNothing() throws Exception {
        long id = itemId("Raita");
        assertEquals(403, admin.postForm("/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "1", "reorderLevel", "1")).status());
        assertEquals(403, admin.postForm("/admin/stock", Map.of("itemId", Long.toString(id), "stockQty", "1", "reorderLevel", "1", "_csrf", "forged")).status());
        Map<String, String> noToken = create("C5 Forged", "10");
        assertEquals(403, admin.postForm("/admin/menu", noToken).status());
        assertEquals(50, stockOf("Raita"));
        assertEquals(0, count("SELECT COUNT(*) FROM menu_items WHERE name = 'C5 Forged'"));
    }
}
