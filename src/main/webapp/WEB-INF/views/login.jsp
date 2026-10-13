<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Sign in"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<section class="login">
  <h1>Sign in</h1>
  <form method="post" action="<c:url value='/login'/>" autocomplete="off">
    <t:csrf/>
    <label for="username">Username</label>
    <input id="username" name="username" type="text" maxlength="256" required autocomplete="username">
    <label for="password">Password</label>
    <input id="password" name="password" type="password" maxlength="256" required autocomplete="current-password">
    <p><button type="submit">Sign in</button></p>
  </form>
</section>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
