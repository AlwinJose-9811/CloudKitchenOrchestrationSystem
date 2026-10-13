<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Not allowed"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<section class="error-page">
  <h1><c:out value="${statusCode}"/> Not allowed</h1>
  <p>You are not allowed to do this.</p>
  <p class="error-id">Reference: <code><c:out value="${errorId}"/></code></p>
  <p><a href="<c:url value='/'/>">Back to the start page</a></p>
</section>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
