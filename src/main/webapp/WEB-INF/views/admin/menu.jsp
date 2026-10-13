<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Menu"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Menu</h1>
<table id="menu-items">
  <thead><tr><th>Item</th><th>Brand</th><th>Price, active</th></tr></thead>
  <tbody>
  <c:forEach var="i" items="${items}">
    <tr>
      <td><c:out value="${i.name}"/></td>
      <td><c:out value="${i.brandName}"/></td>
      <td>
        <form method="post" action="<c:url value='/admin/menu'/>" class="filter">
          <t:csrf/>
          <input type="hidden" name="action" value="update">
          <input type="hidden" name="itemId" value="<c:out value='${i.itemId}'/>">
          <label for="price-<c:out value='${i.itemId}'/>">Price</label>
          <input id="price-<c:out value='${i.itemId}'/>" name="price" type="text" inputmode="decimal" value="<c:out value='${i.price}'/>" required>
          <label for="active-<c:out value='${i.itemId}'/>">Active</label>
          <input id="active-<c:out value='${i.itemId}'/>" name="active" type="checkbox" value="true"<c:if test="${i.active}"> checked</c:if>>
          <button type="submit">Save</button>
        </form>
      </td>
    </tr>
  </c:forEach>
  <c:if test="${empty items}"><tr><td colspan="3">No items.</td></tr></c:if>
  </tbody>
</table>

<h2>Add an item</h2>
<form method="post" action="<c:url value='/admin/menu'/>" id="create-form">
  <t:csrf/>
  <input type="hidden" name="action" value="create">
  <label for="stationCode">Brand</label>
  <select id="stationCode" name="stationCode" required>
    <c:forEach var="b" items="${brands}"><option value="<c:out value='${b.code}'/>"><c:out value="${b.name}"/></option></c:forEach>
  </select>
  <label for="name">Name</label>
  <input id="name" name="name" type="text" maxlength="100" required>
  <label for="price">Price</label>
  <input id="price" name="price" type="text" inputmode="decimal" required>
  <label for="initialStock">Initial stock</label>
  <input id="initialStock" name="initialStock" type="number" min="0" value="0" required>
  <label for="reorderLevel">Reorder level</label>
  <input id="reorderLevel" name="reorderLevel" type="number" min="0" value="10" required>
  <p><button type="submit">Add item</button></p>
</form>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
