<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Compensations"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Orders awaiting compensation</h1>
<p class="note">These orders were accepted but could not be queued, and giving their stock back did not complete. The system retries by itself; Retry now tries at once. An order is never re-queued: a successful retry makes it FAILED and restores its stock.</p>
<p id="pending-count"><c:out value="${pending.size()}"/> awaiting compensation.</p>
<table id="pending">
  <thead><tr><th>Order</th><th>Brand</th><th>Reason</th><th>Attempts</th><th>Age (s)</th><th>Next attempt (UTC)</th><th></th></tr></thead>
  <tbody>
  <c:forEach var="p" items="${pending}">
    <tr>
      <td><a href="<c:url value='/orders/view'><c:param name='id' value='${p.orderId}'/></c:url>"><c:out value="${p.orderId}"/></a></td>
      <td><c:out value="${p.brandCode}"/></td>
      <td><c:out value="${p.reason}"/></td>
      <td><c:out value="${p.attempts}"/></td>
      <td><c:out value="${p.ageSeconds}"/></td>
      <td><c:out value="${p.nextAttemptAt}" default="as soon as possible"/></td>
      <td>
        <form method="post" action="<c:url value='/admin/compensations'/>">
          <t:csrf/>
          <input type="hidden" name="action" value="retry">
          <input type="hidden" name="orderId" value="<c:out value='${p.orderId}'/>">
          <button type="submit">Retry compensation</button>
        </form>
      </td>
    </tr>
  </c:forEach>
  <c:if test="${empty pending}"><tr><td colspan="7">No order is awaiting compensation.</td></tr></c:if>
  </tbody>
</table>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
