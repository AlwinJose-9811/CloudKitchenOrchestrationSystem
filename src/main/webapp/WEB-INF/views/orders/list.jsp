<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Orders"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Orders</h1>
<p id="notice" class="note">This list is read-only; open an order to see its lines and its status history.</p>
<form method="get" action="<c:url value='/orders'/>" class="filter">
  <label for="status">Status</label>
  <select id="status" name="status">
    <option value="">any</option>
    <c:forEach var="s" items="${statuses}">
      <option value="<c:out value='${s}'/>"<c:if test="${s == selectedStatus}"> selected</c:if>><c:out value="${s}"/></option>
    </c:forEach>
  </select>
  <label for="source">Source</label>
  <select id="source" name="source">
    <option value="">any</option>
    <c:forEach var="s" items="${sources}">
      <option value="<c:out value='${s}'/>"<c:if test="${s == selectedSource}"> selected</c:if>><c:out value="${s}"/></option>
    </c:forEach>
  </select>
  <button type="submit">Filter</button>
</form>
<p id="summary">Showing <c:out value="${listing.count}"/> of <c:out value="${listing.total}"/> orders, page <c:out value="${listing.page}"/> of <c:out value="${listing.totalPages}"/>.</p>
<table id="orders">
  <thead><tr><th>Id</th><th>Source</th><th>Brand</th><th>Reference</th><th>Status</th><th>Total</th><th>Created (UTC)</th></tr></thead>
  <tbody>
  <c:forEach var="o" items="${listing.items}">
    <tr>
      <td><a href="<c:url value='/orders/view'><c:param name='id' value='${o.id}'/></c:url>"><c:out value="${o.id}"/></a></td>
      <td><c:out value="${o.source}"/></td>
      <td><c:out value="${o.brandCode}"/></td>
      <td><c:out value="${o.externalRef}"/></td>
      <td><t:statusBadge status="${o.status}"/></td>
      <td><c:out value="${o.total}"/></td>
      <td><c:out value="${o.createdAt}"/></td>
    </tr>
  </c:forEach>
  <c:if test="${empty listing.items}"><tr><td colspan="7">No orders.</td></tr></c:if>
  </tbody>
</table>
<p class="pager">
<c:if test="${listing.page > 1}">
  <a id="prev" href="<c:url value='/orders'><c:param name='page' value='${listing.page - 1}'/><c:param name='status' value='${selectedStatus}'/><c:param name='source' value='${selectedSource}'/></c:url>">Previous</a>
</c:if>
<c:if test="${listing.page < listing.totalPages}">
  <a id="next" href="<c:url value='/orders'><c:param name='page' value='${listing.page + 1}'/><c:param name='status' value='${selectedStatus}'/><c:param name='source' value='${selectedSource}'/></c:url>">Next</a>
</c:if>
</p>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
