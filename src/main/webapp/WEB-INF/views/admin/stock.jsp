<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Stock"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Stock</h1>
<p class="note">Saving replaces the quantity and the reorder level of the item. Orders placed at the same moment are not lost: the change is one database update.</p>
<table id="stock">
  <thead><tr><th>Item</th><th>Brand</th><th>Level</th><th>In stock</th><th>Reorder level</th><th></th></tr></thead>
  <tbody>
  <c:forEach var="i" items="${items}">
    <tr>
      <td><c:out value="${i.itemName}"/></td>
      <td><c:out value="${i.brandCode}"/></td>
      <td><c:choose><c:when test="${i.low}"><strong class="low">LOW</strong></c:when><c:otherwise>OK</c:otherwise></c:choose></td>
      <td colspan="3">
        <form method="post" action="<c:url value='/admin/stock'/>" class="filter">
          <t:csrf/>
          <input type="hidden" name="itemId" value="<c:out value='${i.itemId}'/>">
          <label for="stockQty-<c:out value='${i.itemId}'/>">In stock</label>
          <input id="stockQty-<c:out value='${i.itemId}'/>" name="stockQty" type="number" min="0" value="<c:out value='${i.stockQty}'/>" required>
          <label for="reorderLevel-<c:out value='${i.itemId}'/>">Reorder level</label>
          <input id="reorderLevel-<c:out value='${i.itemId}'/>" name="reorderLevel" type="number" min="0" value="<c:out value='${i.reorderLevel}'/>" required>
          <button type="submit">Save</button>
        </form>
      </td>
    </tr>
  </c:forEach>
  <c:if test="${empty items}"><tr><td colspan="6">No items.</td></tr></c:if>
  </tbody>
</table>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
