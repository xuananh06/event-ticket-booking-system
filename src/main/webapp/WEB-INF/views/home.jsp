<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Event Ticket Booking System</title>
</head>
<body>
<main>
    <h1><c:out value="${welcomeMessage}"/></h1>
    <p>P1 foundation smoke page</p>
    <p>Database startup connectivity: <strong id="database-status"><c:out value="${databaseStatus}"/></strong></p>
    <p>This startup result does not validate the application schema.</p>
    <ul id="components">
        <c:forEach var="component" items="${components}">
            <li><c:out value="${component}"/></li>
        </c:forEach>
    </ul>
</main>
</body>
</html>
