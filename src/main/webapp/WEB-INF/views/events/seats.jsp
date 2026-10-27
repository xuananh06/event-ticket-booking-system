<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
    <head>
        <%@ include file="../common/head.jspf" %>
    </head>
    <body>
        <%@ include file="../common/navigation.jspf" %>
        <main id="main-content" class="container page-content">
            <c:url var="detailUrl" value="/events/detail">
                <c:param name="id" value="${event.id}"/>
            </c:url>
            <a href="<c:out value='${detailUrl}'/>">Back to event details</a>
            <h1 class="h2 mt-3">Seat availability: <c:out value="${event.title}"/></h1>
            <p><c:out value="${event.venueName}"/> / Starts <c:out value="${event.startsLabel}"/> (Asia/Bangkok)</p>
            <%@ include file="../common/inventory-layout.jspf" %>
            <%@ include file="../common/inventory-paging.jspf" %>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
