<%@ page contentType="text/html;charset=UTF-8" language="java" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Orders (Phase 3 demonstration)</title>
</head>
<body>
<h1>Orders</h1>
<p id="notice">Phase 3 demonstration: a read-only list read from the database. It shows no customer data and is replaced by the signed-in order list in Phase 7.</p>
<form method="get" action="${pageContext.request.contextPath}/demo/orders">
  <label>Status
    <select name="status">
      <option value="">any</option>
      <c:forEach var="s" items="${statuses}">
        <option value="${s.name()}" ${s.name() == selectedStatus ? 'selected' : ''}><c:out value="${s}"/></option>
      </c:forEach>
    </select>
  </label>
  <label>Source
    <select name="source">
      <option value="">any</option>
      <c:forEach var="s" items="${sources}">
        <option value="${s.name()}" ${s.name() == selectedSource ? 'selected' : ''}><c:out value="${s}"/></option>
      </c:forEach>
    </select>
  </label>
  <button type="submit">Filter</button>
</form>
<p id="summary">Showing <c:out value="${page.items().size()}"/> of <c:out value="${page.total()}"/> orders, page <c:out value="${page.page()}"/> of <c:out value="${page.totalPages()}"/>.</p>
<table id="orders" border="1">
  <thead><tr><th>Id</th><th>Source</th><th>Brand</th><th>Reference</th><th>Status</th><th>Total</th><th>Created (UTC)</th></tr></thead>
  <tbody>
  <c:forEach var="o" items="${page.items()}">
    <tr>
      <td><c:out value="${o.id()}"/></td>
      <td><c:out value="${o.source()}"/></td>
      <td><c:out value="${o.brandCode()}"/></td>
      <td><c:out value="${o.externalRef()}"/></td>
      <td><c:out value="${o.status()}"/></td>
      <td><c:out value="${o.total()}"/></td>
      <td><c:out value="${o.createdAt()}"/></td>
    </tr>
  </c:forEach>
  <c:if test="${empty page.items()}"><tr><td colspan="7">No orders.</td></tr></c:if>
  </tbody>
</table>
<c:if test="${page.page() > 1}">
  <a id="prev" href="${pageContext.request.contextPath}/demo/orders?page=${page.page() - 1}&amp;status=${selectedStatus}&amp;source=${selectedSource}">Previous</a>
</c:if>
<c:if test="${page.page() < page.totalPages()}">
  <a id="next" href="${pageContext.request.contextPath}/demo/orders?page=${page.page() + 1}&amp;status=${selectedStatus}&amp;source=${selectedSource}">Next</a>
</c:if>
</body>
</html>
