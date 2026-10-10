<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>Phase 1 spike</title></head>
<body>
<h1>Phase 1 spike page</h1>
<p id="greeting">c:out says: <c:out value="${param.name}" default="no name given"/></p>
<p id="sum">EL sum: <c:out value="${1 + 2}"/></p>
<p id="launcher">launcher mark: <c:out value="${applicationScope['ck.launcher.mark']}" default="missing"/></p>
<p id="listener">listener saw: <c:out value="${applicationScope['ck.listener.seen']}" default="missing"/></p>
</body>
</html>
