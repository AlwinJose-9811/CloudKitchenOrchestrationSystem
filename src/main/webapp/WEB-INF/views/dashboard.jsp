<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="pageTitle" value="Dashboard"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Dashboard</h1>
<p class="note">This page reloads itself every <c:out value="${refreshSeconds}"/> seconds. All times are UTC.</p>

<c:if test="${view.recovering}">
  <p class="flash flash-info" id="recovering" role="status">Recovering: the application is re-queueing unfinished orders and does not accept new ones yet.</p>
</c:if>

<section aria-labelledby="orders-title">
  <h2 id="orders-title">Orders by status</h2>
  <table id="status-counts">
    <thead><tr><th>Status</th><th>Orders</th></tr></thead>
    <tbody>
    <c:forEach var="e" items="${view.statusCounts}">
      <tr><td><t:statusBadge status="${e.key}"/></td><td><c:out value="${e.value}"/></td></tr>
    </c:forEach>
    </tbody>
  </table>
</section>

<section aria-labelledby="stations-title">
  <h2 id="stations-title">Kitchen stations</h2>
  <table id="stations">
    <thead><tr><th>Station</th><th>Queue</th><th>Worker</th><th>Generation</th><th>Restarts</th><th>Current order</th><th>Processed</th><th>Heartbeat age (s)</th></tr></thead>
    <tbody>
    <c:forEach var="s" items="${view.stations}">
      <tr>
        <td><c:out value="${s.stationCode}"/> (<c:out value="${s.brandName}"/>)<span class="dish dish-<c:out value='${fn:toLowerCase(s.stationCode)}'/>" aria-hidden="true"></span></td>
        <td><c:out value="${s.queueSize}"/> / <c:out value="${s.queueCapacity}"/>
          <progress max="<c:out value='${s.queueCapacity}'/>" value="<c:out value='${s.queueSize}'/>"></progress></td>
        <td><c:out value="${s.worker.state}"/><c:if test="${s.paused}"> (paused)</c:if></td>
        <td><c:out value="${s.worker.generation}"/></td>
        <td><c:out value="${s.worker.restarts}"/></td>
        <td><c:out value="${s.worker.currentOrderId}" default="-"/></td>
        <td><c:out value="${s.worker.processedCount}"/></td>
        <td><c:out value="${s.worker.heartbeatAgeMillis div 1000}"/></td>
      </tr>
    </c:forEach>
    </tbody>
  </table>
</section>

<section aria-labelledby="metrics-title">
  <h2 id="metrics-title">Metrics</h2>
  <table id="metrics" class="facts">
    <tr><th scope="row">Submitted</th><td><c:out value="${view.metrics.submitted}"/></td></tr>
    <tr><th scope="row">Processed</th><td><c:out value="${view.metrics.processed}"/></td></tr>
    <tr><th scope="row">Failed</th><td><c:out value="${view.metrics.failed}"/></td></tr>
    <tr><th scope="row">Rejected</th><td><c:out value="${view.metrics.rejected}"/></td></tr>
    <tr><th scope="row">Average processing time (ms)</th><td><c:out value="${view.metrics.averageProcessingMillis}"/></td></tr>
  </table>
</section>

<section aria-labelledby="stock-title">
  <h2 id="stock-title">Low stock</h2>
  <table id="low-stock">
    <thead><tr><th>Item</th><th>Brand</th><th>In stock</th><th>Reorder level</th><th>Level</th></tr></thead>
    <tbody>
    <c:forEach var="i" items="${view.lowStock}">
      <tr><td><c:out value="${i.itemName}"/></td><td><c:out value="${i.brandCode}"/></td><td><c:out value="${i.stockQty}"/></td><td><c:out value="${i.reorderLevel}"/></td><td><strong class="low">LOW</strong></td></tr>
    </c:forEach>
    <c:if test="${empty view.lowStock}"><tr><td colspan="5">No item is below its reorder level.</td></tr></c:if>
    </tbody>
  </table>
</section>

<section aria-labelledby="sim-title">
  <h2 id="sim-title">Feed simulator and compensation</h2>
  <table id="simulator" class="facts">
    <tr><th scope="row">Simulator</th><td><c:choose><c:when test="${view.simulatorRunning}">running</c:when><c:otherwise>stopped</c:otherwise></c:choose></td></tr>
    <tr><th scope="row">Rate per minute</th><td><c:out value="${view.simulatorRatePerMinute}"/></td></tr>
    <tr><th scope="row">Orders awaiting compensation</th><td><c:out value="${view.pendingCompensations}"/></td></tr>
    <tr><th scope="row">Oldest waiting (s)</th><td><c:out value="${view.oldestPendingCompensationAgeSeconds}"/></td></tr>
  </table>
</section>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
