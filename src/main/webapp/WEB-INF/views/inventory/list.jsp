<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Inventory"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Inventory</h1>
<table id="inventory">
  <thead><tr><th>Item</th><th>Brand</th><th>In stock</th><th>Reorder level</th><th>Level</th></tr></thead>
  <tbody>
  <c:forEach var="i" items="${items}">
    <tr>
      <td><c:out value="${i.itemName}"/></td>
      <td><c:out value="${i.brandCode}"/></td>
      <td><c:out value="${i.stockQty}"/></td>
      <td><c:out value="${i.reorderLevel}"/></td>
      <td><c:choose><c:when test="${i.low}"><strong class="low">LOW</strong></c:when><c:otherwise>OK</c:otherwise></c:choose></td>
    </tr>
  </c:forEach>
  <c:if test="${empty items}"><tr><td colspan="5">No items.</td></tr></c:if>
  </tbody>
</table>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
