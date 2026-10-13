<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Users"/>
<%@ include file="/WEB-INF/views/layout/header.jspf" %>
<h1>Staff accounts</h1>
<table id="users">
  <thead><tr><th>User</th><th>Role</th><th>State</th><th>Locked until (UTC)</th><th></th></tr></thead>
  <tbody>
  <c:forEach var="u" items="${users}">
    <tr>
      <td><c:out value="${u.username}"/></td>
      <td><c:out value="${u.role}"/></td>
      <td><c:choose><c:when test="${u.enabled}">enabled</c:when><c:otherwise>disabled</c:otherwise></c:choose></td>
      <td><c:out value="${u.lockedUntil}" default="-"/></td>
      <td>
        <form method="post" action="<c:url value='/admin/users'/>">
          <t:csrf/>
          <input type="hidden" name="userId" value="<c:out value='${u.id}'/>">
          <c:choose>
            <c:when test="${u.enabled}"><button type="submit" name="action" value="disable">Disable</button></c:when>
            <c:otherwise><button type="submit" name="action" value="enable">Enable</button></c:otherwise>
          </c:choose>
        </form>
      </td>
    </tr>
  </c:forEach>
  </tbody>
</table>
<p class="note">You cannot disable your own account or the last enabled administrator. A disabled account is signed out at its next request.</p>
<%@ include file="/WEB-INF/views/layout/footer.jspf" %>
