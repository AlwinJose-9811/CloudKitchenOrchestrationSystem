<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="New order"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>New in-house order</h1>
<c:if test="${not empty problems}">
  <ul id="problems" class="flash flash-error">
    <c:forEach var="p" items="${problems}"><li><c:out value="${p}"/></li></c:forEach>
  </ul>
</c:if>

<form method="get" action="<c:url value='/orders/new'/>" class="filter" id="brand-form">
  <label for="brandCode">Brand</label>
  <select id="brandCode" name="brandCode" required>
    <option value="">Choose a brand</option>
    <c:forEach var="b" items="${brands}">
      <option value="<c:out value='${b.code}'/>"<c:if test="${b.code == brand}"> selected</c:if>><c:out value="${b.name}"/></option>
    </c:forEach>
  </select>
  <button type="submit">Show menu</button>
</form>

<c:if test="${not empty brand}">
<form method="post" action="<c:url value='/orders/new'/>" id="order-form">
  <t:csrf/>
  <input type="hidden" name="brandCode" value="<c:out value='${brand}'/>">
  <table id="menu">
    <thead><tr><th>Item</th><th>Price</th><th>Quantity</th></tr></thead>
    <tbody>
    <c:forEach var="l" items="${lines}">
      <tr>
        <td><c:out value="${l.name}"/></td>
        <td><c:out value="${l.price}"/></td>
        <td>
          <input type="hidden" name="itemId" value="<c:out value='${l.itemId}'/>">
          <input type="number" name="quantity" min="0" max="99" value="<c:out value='${l.quantity}'/>" aria-label="Quantity of <c:out value='${l.name}'/>">
        </td>
      </tr>
    </c:forEach>
    <c:if test="${empty lines}"><tr><td colspan="3">This brand has no active items.</td></tr></c:if>
    </tbody>
  </table>
  <label for="customerName">Customer name</label>
  <input id="customerName" name="customerName" type="text" maxlength="100" required value="<c:out value='${form.customerName}'/>">
  <label for="deliveryDistanceKm">Delivery distance (km)</label>
  <input id="deliveryDistanceKm" name="deliveryDistanceKm" type="text" inputmode="decimal" required value="<c:out value='${form.deliveryDistanceKm}'/>">
  <label for="paymentMode">Payment</label>
  <select id="paymentMode" name="paymentMode" required>
    <c:forEach var="m" items="${modes}">
      <option value="<c:out value='${m}'/>"<c:if test="${m == form.paymentMode}"> selected</c:if>><c:out value="${m}"/></option>
    </c:forEach>
  </select>
  <p><button type="submit">Place order</button></p>
</form>
</c:if>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
