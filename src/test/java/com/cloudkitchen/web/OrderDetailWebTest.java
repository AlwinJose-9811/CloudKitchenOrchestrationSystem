package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-07, FR-LIF-04, FR-WEB-14 (banner): the order page shows items, totals, payment and the whole status history; it
 * offers Cancel only where the lifecycle allows it and never for an order awaiting compensation. Orders are driven through
 * the real pipeline (real workers, real lifecycle), not written by hand, wherever the history matters.
 */
class OrderDetailWebTest extends WebTestBase {

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private static List<String> historyTargets(String html) {
        Matcher table = Pattern.compile("(?s)<table id=\"history\">.*?</table>").matcher(html);
        assertTrue(table.find(), html);
        List<String> to = new ArrayList<>();
        Matcher row = Pattern.compile("(?s)<tr><td>[^<]*</td><td>([A-Z_-]+)</td><td>([A-Z_]+)</td><td>").matcher(table.group());
        while (row.find()) {
            to.add(row.group(1) + ">" + row.group(2));
        }
        return to;
    }

    @Test
    void aFullyProcessedOrderShowsItsWholeLifecycleInOrderWithTimeFromToAndActor() throws Exception {
        long id = submit(OrderSource.SWIGGY, "LC-1", "BIRYANI", "Asha Rao", "Chicken Biryani", 2);
        awaitStatus(id, "DELIVERED");
        Reply page = app.staffBrowser().get("/orders/view?id=" + id);
        assertEquals(200, page.status());
        List<String> steps = historyTargets(page.body());
        assertEquals(List.of("->PLACED", "PLACED>VALIDATED", "VALIDATED>ROUTED", "ROUTED>PREPARING", "PREPARING>PACKED",
                "PACKED>OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY>DELIVERED"), steps);
        String text = plain(page.body());
        assertTrue(text.contains("UTC"), "times are labelled UTC: " + text);
        assertTrue(text.contains("worker:BIRYANI#"), "the actor of a worker step is shown: " + text);
        assertTrue(page.body().contains("badge-delivered"));
        assertFalse(page.body().contains("id=\"cancel-form\""), "a delivered order cannot be cancelled");
    }

    @Test
    void theItemsTheTotalsAndThePaymentAreShown() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "IT-1", "BIRYANI", "Asha Rao", "Chicken Biryani", 2);
        String text = plain(app.adminBrowser().get("/orders/view?id=" + id).body());
        assertTrue(text.contains("Chicken Biryani 249.00 2 498.00"), text);
        assertTrue(text.contains("Subtotal 498.00"), text);
        assertTrue(text.contains("Payable 498.00"), text);
        assertTrue(text.contains("Platform fee"), text);
        assertTrue(text.contains("PREPAID_AGGREGATOR"), text);
        assertTrue(text.contains("Source SWIGGY") && text.contains("Brand BIRYANI") && text.contains("External reference IT-1"), text);
        assertTrue(text.contains("Customer Asha Rao"), text);
        assertTrue(text.matches(".*Tracking code [A-Z0-9]{12} .*"), "tracking code: " + text);
    }

    @Test
    void cancelIsOfferedOnlyWhereTheLifecycleAllowsItAndCarriesACsrfToken() throws Exception {
        pauseAll();
        long held = submit(OrderSource.ZOMATO, "CN-1", "BURGER", "Bhavna", "Cola", 1);
        HttpTestClient staff = app.staffBrowser();
        for (OrderStatus status : OrderStatus.values()) {
            forceStatus(held, status.name(), null);
            Reply page = staff.get("/orders/view?id=" + held);
            assertEquals(200, page.status(), "status " + status + ": " + page.body());
            boolean offered = page.body().contains("id=\"cancel-form\"");
            assertEquals(status.canTransitionTo(OrderStatus.CANCELLED), offered, "Cancel for status " + status);
            if (offered) {
                assertTrue(page.body().contains("action=\"/orders/cancel\""));
                assertTrue(HttpTestClient.extractCsrf(page.body()) != null);
                assertTrue(page.body().contains("name=\"id\" value=\"" + held + "\""));
            }
        }
    }

    @Test
    void theCancelFormOfTheOrderPageCancelsTheOrderAndTheSamePageThenShowsTheNewStatusTheHistoryAndTheMessage() throws Exception {
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "RT-1", "BIRYANI", "Asha Rao", "Chicken Biryani", 2);
        HttpTestClient staff = app.staffBrowser();
        String page = staff.get("/orders/view?id=" + id).body();
        Matcher form = Pattern.compile("(?s)<form method=\"post\" action=\"([^\"]*)\" id=\"cancel-form\">(.*?)</form>").matcher(page);
        assertTrue(form.find(), "the page offers the Cancel form: " + page);
        String action = form.group(1);
        assertEquals("/orders/cancel", action, "the form posts to the route that C4 provides");
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(form.group(2));
        Matcher orderId = Pattern.compile("name=\"id\" value=\"(\\d+)\"").matcher(form.group(2));
        assertTrue(token.find() && orderId.find());
        assertEquals(Long.toString(id), orderId.group(1));

        Reply post = staff.postForm(action, java.util.Map.of("id", orderId.group(1), "_csrf", token.group(1)));
        HttpTestClient.assertRedirect(post, "/orders/view?id=" + id);
        Reply after = staff.get(post.location().substring(post.location().indexOf("/orders/view")));
        String text = plain(after.body());
        assertTrue(text.contains("Order " + id + " has been cancelled."), text);
        assertTrue(after.body().contains("badge-cancelled"));
        assertFalse(after.body().contains("id=\"cancel-form\""));
        List<String> steps = historyTargets(after.body());
        assertTrue(steps.get(steps.size() - 1).endsWith(">CANCELLED"), steps.toString());
        assertEquals("CANCELLED", statusOf(id));
        assertEquals(100, stockOf("Chicken Biryani"));
    }

    @Test
    void anOrderAwaitingCompensationShowsABannerAndNoCancelButtonAndOnlyTheAdministratorGetsTheLink() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "CM-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        forceStatus(id, "VALIDATED", "PENDING");
        try {
            String staff = app.staffBrowser().get("/orders/view?id=" + id).body();
            assertTrue(staff.contains("id=\"compensation-banner\""), "banner for a pending order: " + staff);
            assertTrue(staff.contains("awaiting compensation"));
            assertFalse(staff.contains("id=\"cancel-form\""), "no Cancel button for a pending order");
            assertFalse(staff.contains("/admin/compensations"), "staff get no link to the administrator's page");
            String admin = app.adminBrowser().get("/orders/view?id=" + id).body();
            assertTrue(admin.contains("id=\"compensation-banner\"") && admin.contains("href=\"/admin/compensations\""), admin);
            assertFalse(admin.contains("id=\"cancel-form\""));
        } finally {
            forceStatus(id, "VALIDATED", null);
        }
    }

    @Test
    void anUnknownIdIs404AndAMalformedOrMissingIdIs400WithGenericPages() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(404, staff.get("/orders/view?id=999999999").status());
        assertEquals(404, staff.get("/orders/view?id=-1").status());
        for (String bad : List.of("/orders/view", "/orders/view?id=", "/orders/view?id=abc", "/orders/view?id=1.5", "/orders/view?id=99999999999999999999")) {
            Reply reply = staff.get(bad);
            assertEquals(400, reply.status(), bad);
            assertFalse(reply.body().contains("Exception") || reply.body().contains("at com."), bad);
        }
    }

    @Test
    void theOrderPageIsProtectedAndPublicTrackingDataIsNotLeakedToAnonymousCallers() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PR-1", "BIRYANI", "Secret Customer", "Chicken Biryani", 1);
        Reply anonymous = app.browser().get("/orders/view?id=" + id);
        HttpTestClient.assertRedirect(anonymous, "/login");
        assertFalse(anonymous.body().contains("Secret Customer"));
    }

    @Test
    void thePageHasNoFormExceptCancelAndLogoutSoAViewCannotChangeAnything() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "NF-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        String body = app.adminBrowser().get("/orders/view?id=" + id).body();
        Matcher forms = Pattern.compile("<form [^>]*action=\"([^\"]*)\"").matcher(body);
        List<String> actions = new ArrayList<>();
        while (forms.find()) {
            actions.add(forms.group(1));
        }
        assertEquals(List.of("/logout", "/orders/cancel"), actions);
    }
}
