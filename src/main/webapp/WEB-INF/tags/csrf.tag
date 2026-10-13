<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
