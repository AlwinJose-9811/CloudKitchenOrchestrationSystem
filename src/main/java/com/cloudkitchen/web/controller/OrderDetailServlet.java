package com.cloudkitchen.web.controller;

import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.PaymentResult;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderProgress;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /orders/view?id=} (SDD 10.2 route 7, FR-WEB-07, FR-LIF-04): one order with its lines, totals, payment, estimated
 * delivery and the complete status history in order. A non-numeric id is a 400, an unknown one a 404. The Cancel form is
 * offered only if the lifecycle allows {@code CANCELLED} from the current status ({@code OrderStatus.canTransitionTo}, the
 * single rule of the lifecycle) and the order is not awaiting compensation; for such an order the page shows a banner
 * instead, with a link to the compensation page for an administrator and no Cancel button (SDD 7.10, FR-WEB-14). The
 * server decides again when the form is posted, so this is a convenience, not the check.
 */
public class OrderDetailServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        long id = longParam(request, "id");
        Optional<Order> found;
        boolean pending;
        try {
            found = services().orderQueries().detail(id);
            Optional<OrderProgress> progress = found.isPresent() ? services().orders().readProgress(id) : Optional.empty();
            pending = progress.map(OrderProgress::compensationPending).orElse(false);
        } catch (CloudKitchenException e) {
            throw new ServletException("The order could not be read", e);
        }
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Order order = found.get();
        PaymentResult payment = order.processPayment();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("source", order.getSource());
        view.put("brandCode", order.getBrandCode());
        view.put("externalRef", order.getExternalRef());
        view.put("trackingCode", order.getTrackingCode());
        view.put("status", order.getStatus());
        view.put("customerName", order.getCustomerName());
        view.put("deliveryDistanceKm", order.getDeliveryDistanceKm());
        view.put("paymentMode", order.getPaymentMode());
        view.put("paymentStatus", payment.status());
        view.put("platformFee", payment.platformFee());
        view.put("subtotal", order.getSubtotal());
        view.put("payable", order.getPayableAmount());
        view.put("createdAt", order.getCreatedAt());
        view.put("estimatedDeliveryAt", order.getEstimatedDeliveryAt());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", item.getItemName());
            line.put("unitPrice", item.getUnitPrice());
            line.put("quantity", item.getQuantity());
            line.put("lineTotal", item.lineTotal());
            lines.add(line);
        }
        view.put("items", lines);
        view.put("history", order.getHistory());
        view.put("compensationPending", pending);
        view.put("canCancel", !pending && order.getStatus().canTransitionTo(OrderStatus.CANCELLED));
        request.setAttribute("order", Views.of(view));
        request.setAttribute("pageTitle", "Order " + order.getId());
        render(request, response, "orders/detail");
    }
}
