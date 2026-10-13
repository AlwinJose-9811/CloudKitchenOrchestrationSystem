<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Order"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Order <c:out value="${order.id}"/> <t:statusBadge status="${order.status}"/></h1>

<c:if test="${order.compensationPending}">
  <p class="flash flash-error" id="compensation-banner" role="alert">This order is awaiting compensation: its stock has not been given back yet and it cannot be cancelled here.
    <c:if test="${requestScope['ck.currentUser'].role == 'ADMIN'}"> <a href="<c:url value='/admin/compensations'/>">Open the compensation page</a>.</c:if></p>
</c:if>

<section aria-labelledby="summary-title">
  <h2 id="summary-title">Summary</h2>
  <table class="facts">
    <tr><th scope="row">Source</th><td><c:out value="${order.source}"/></td></tr>
    <tr><th scope="row">Brand</th><td><c:out value="${order.brandCode}"/></td></tr>
    <tr><th scope="row">External reference</th><td><c:out value="${order.externalRef}"/></td></tr>
    <tr><th scope="row">Tracking code</th><td><c:out value="${order.trackingCode}"/></td></tr>
    <tr><th scope="row">Customer</th><td><c:out value="${order.customerName}"/></td></tr>
    <tr><th scope="row">Distance (km)</th><td><c:out value="${order.deliveryDistanceKm}"/></td></tr>
    <tr><th scope="row">Created</th><td><c:out value="${order.createdAt}"/></td></tr>
    <tr><th scope="row">Estimated delivery</th><td><c:out value="${order.estimatedDeliveryAt}" default="not set"/></td></tr>
  </table>
</section>

<section aria-labelledby="items-title">
  <h2 id="items-title">Items</h2>
  <table id="items">
    <thead><tr><th>Item</th><th>Unit price</th><th>Quantity</th><th>Line total</th></tr></thead>
    <tbody>
    <c:forEach var="line" items="${order.items}">
      <tr><td><c:out value="${line.name}"/></td><td><c:out value="${line.unitPrice}"/></td><td><c:out value="${line.quantity}"/></td><td><c:out value="${line.lineTotal}"/></td></tr>
    </c:forEach>
    </tbody>
  </table>
  <table class="facts">
    <tr><th scope="row">Subtotal</th><td><c:out value="${order.subtotal}"/></td></tr>
    <tr><th scope="row">Platform fee</th><td><c:out value="${order.platformFee}"/></td></tr>
    <tr><th scope="row">Payable</th><td><c:out value="${order.payable}"/></td></tr>
    <tr><th scope="row">Payment</th><td><c:out value="${order.paymentMode}"/> (<c:out value="${order.paymentStatus}"/>)</td></tr>
  </table>
</section>

<section aria-labelledby="history-title">
  <h2 id="history-title">Status history</h2>
  <table id="history">
    <thead><tr><th>Time (UTC)</th><th>From</th><th>To</th><th>By</th><th>Note</th></tr></thead>
    <tbody>
    <c:forEach var="h" items="${order.history}">
      <tr><td><c:out value="${h.changedAt}"/></td><td><c:out value="${h.from}" default="-"/></td><td><c:out value="${h.to}"/></td><td><c:out value="${h.actor}"/></td><td><c:out value="${h.note}"/></td></tr>
    </c:forEach>
    </tbody>
  </table>
</section>

<c:if test="${order.canCancel}">
  <form method="post" action="<c:url value='/orders/cancel'/>" id="cancel-form">
    <t:csrf/>
    <input type="hidden" name="id" value="<c:out value='${order.id}'/>">
    <button type="submit">Cancel this order</button>
  </form>
</c:if>
<p><a href="<c:url value='/orders'/>">Back to the order list</a></p>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
