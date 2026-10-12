<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Feed simulator"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Feed simulator</h1>
<table id="simulator-state" class="facts">
  <tr><th scope="row">State</th><td><c:choose><c:when test="${sim.running}">running</c:when><c:otherwise>stopped</c:otherwise></c:choose></td></tr>
  <tr><th scope="row">Rate (orders per minute)</th><td><c:out value="${sim.ratePerMinute}"/></td></tr>
  <tr><th scope="row">Highest allowed rate</th><td><c:out value="${sim.maxRatePerMinute}"/></td></tr>
  <tr><th scope="row">Orders produced</th><td><c:out value="${sim.produced}"/></td></tr>
  <tr><th scope="row">Accepted</th><td><c:out value="${sim.accepted}"/></td></tr>
  <tr><th scope="row">Not accepted (stock, queue, shutdown)</th><td><c:out value="${sim.notAccepted}"/></td></tr>
  <tr><th scope="row">Errors</th><td><c:out value="${sim.errors}"/></td></tr>
</table>

<form method="post" action="<c:url value='/admin/simulator'/>" class="filter" id="simulator-form">
  <t:csrf/>
  <label for="ratePerMinute">Orders per minute</label>
  <input id="ratePerMinute" name="ratePerMinute" type="number" min="1" max="<c:out value='${sim.maxRatePerMinute}'/>" value="<c:out value='${sim.ratePerMinute}'/>" required>
  <button type="submit" name="action" value="start">Start</button>
  <button type="submit" name="action" value="rate">Change rate</button>
  <button type="submit" name="action" value="stop" formnovalidate>Stop</button>
</form>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
