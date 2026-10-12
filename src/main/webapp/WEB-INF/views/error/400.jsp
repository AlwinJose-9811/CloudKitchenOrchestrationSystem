<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Bad request"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<section class="error-page">
  <h1><c:out value="${statusCode}"/> Bad request</h1>
  <p>The request could not be understood. Please check what you entered and try again.</p>
  <p class="error-id">Reference: <code><c:out value="${errorId}"/></code></p>
  <p><a href="<c:url value='/'/>">Back to the start page</a></p>
</section>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
