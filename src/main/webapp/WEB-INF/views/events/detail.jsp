<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
<head><%@ include file="../common/head.jspf" %></head>
<body>
<%@ include file="../common/navigation.jspf" %>
<main id="main-content" class="container page-content">
<c:url var="eventsUrl" value="/events"/><a href="<c:out value='${eventsUrl}'/>">Back to events</a>
<article class="account-card"><p class="eyebrow">UNIVERSITY EVENTS</p><h1 class="h2"><c:out value="${event.title}"/></h1>
<dl class="row mt-4"><dt class="col-sm-3">Category</dt><dd class="col-sm-9"><c:out value="${event.categoryName}"/></dd><dt class="col-sm-3">Venue</dt><dd class="col-sm-9"><c:out value="${event.venueName}"/></dd><dt class="col-sm-3">Starts</dt><dd class="col-sm-9"><c:out value="${event.startsLabel}"/> (Asia/Bangkok)</dd><dt class="col-sm-3">Ends</dt><dd class="col-sm-9"><c:out value="${event.endsLabel}"/> (Asia/Bangkok)</dd></dl>
<h2 class="h5">About this event</h2><div class="event-description"><c:out value="${event.description}"/></div>
<c:url var="seatsUrl" value="/events/seats"><c:param name="eventId" value="${event.id}"/></c:url><p class="mt-4"><a class="btn btn-outline-dark" href="<c:out value='${seatsUrl}'/>">View seat availability and prices</a></p>
</article>
</main>
<%@ include file="../common/footer.jspf" %>
</body>
</html>
