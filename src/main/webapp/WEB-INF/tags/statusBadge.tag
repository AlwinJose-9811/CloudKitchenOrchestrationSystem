<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ attribute name="status" required="true" type="java.lang.String" %>
<span class="badge badge-<c:out value='${fn:toLowerCase(status)}'/>"><c:out value="${status}"/></span>
