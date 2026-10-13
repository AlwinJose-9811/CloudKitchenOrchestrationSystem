<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Workers"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Kitchen workers</h1>
<table id="workers">
  <thead><tr><th>Station</th><th>State</th><th>Generation</th><th>Restarts</th><th>Heartbeat age (s)</th><th>Queue</th><th>Current order</th><th>Processed</th><th>Failures in a row</th><th>Actions</th></tr></thead>
  <tbody>
  <c:forEach var="s" items="${stations}">
    <tr>
      <td><c:out value="${s.stationCode}"/></td>
      <td><c:out value="${s.worker.state}"/><c:if test="${s.paused}"> (paused)</c:if></td>
      <td><c:out value="${s.worker.generation}"/></td>
      <td><c:out value="${s.worker.restarts}"/></td>
      <td><c:out value="${s.worker.heartbeatAgeMillis / 1000}"/></td>
      <td><c:out value="${s.queueSize}"/> / <c:out value="${s.queueCapacity}"/></td>
      <td><c:out value="${s.worker.currentOrderId}" default="-"/></td>
      <td><c:out value="${s.worker.processedCount}"/></td>
      <td><c:out value="${s.worker.consecutiveFailures}"/></td>
      <td>
        <form method="post" action="<c:url value='/admin/workers'/>" class="filter">
          <t:csrf/>
          <input type="hidden" name="stationCode" value="<c:out value='${s.stationCode}'/>">
          <input type="hidden" name="generation" value="<c:out value='${s.worker.generation}'/>">
          <c:choose>
            <c:when test="${s.paused}"><button type="submit" name="action" value="resume">Resume</button></c:when>
            <c:otherwise><button type="submit" name="action" value="pause">Pause</button></c:otherwise>
          </c:choose>
          <button type="submit" name="action" value="restart">Restart</button>
        </form>
      </td>
    </tr>
  </c:forEach>
  <c:if test="${empty stations}"><tr><td colspan="10">No stations.</td></tr></c:if>
  </tbody>
</table>
<p class="note">Restart replaces the worker with a new generation on the same queue; the orders it held are queued again. A restart is ignored if the page is out of date.</p>

<h2>Snapshot</h2>
<p class="note">Writes a signed snapshot of the in-flight orders now. The system also writes one on a schedule and at shutdown.</p>
<form method="post" action="<c:url value='/admin/snapshot'/>" id="snapshot-form">
  <t:csrf/>
  <button type="submit">Write a snapshot now</button>
</form>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
