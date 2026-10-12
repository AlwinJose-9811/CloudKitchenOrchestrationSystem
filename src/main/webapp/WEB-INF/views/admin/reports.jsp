<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Reports"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Daily report</h1>
<p class="note">A CSV file of the orders created on one day (UTC), without customer details.</p>
<form method="get" action="<c:url value='/admin/reports/daily.csv'/>" class="filter" id="report-form">
  <label for="date">Day (UTC)</label>
  <input id="date" name="date" type="date" required value="<c:out value='${today}'/>">
  <button type="submit">Download CSV</button>
</form>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
