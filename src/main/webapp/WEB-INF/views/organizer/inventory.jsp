<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en"><head><%@ include file="../common/head.jspf" %></head><body>
<%@ include file="../common/navigation.jspf" %>
<main id="main-content" class="container page-content">
<c:url var="eventUrl" value="/organizer/events"><c:param name="id" value="${event.id}"/></c:url>
<c:url var="inventoryUrl" value="/organizer/events/inventory"/>
<a href="<c:out value='${eventUrl}'/>">Back to event</a><h1 class="h2 mt-3">Event inventory: <c:out value="${event.title}"/></h1>
<p><c:out value="${event.venueName}"/> / <c:out value="${event.status}"/> / Starts <c:out value="${event.startsLabel}"/> (Asia/Bangkok)</p>
<%@ include file="../common/messages.jspf" %>
<c:if test="${not empty editReasons}"><div class="alert alert-info"><p>Inventory setup is read-only:</p><ul class="mb-0"><c:forEach var="reason" items="${editReasons}"><li><c:out value="${reason}"/></li></c:forEach></ul></div></c:if>
<c:if test="${editable}">
<section class="account-card mt-0"><h2 class="h4">Add missing venue seats</h2><p>Only missing active seats are added. Existing inventory and prices remain unchanged.</p>
<form method="post" action="<c:out value='${inventoryUrl}'/>"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="eventId" value="${event.id}"><input type="hidden" name="action" value="generate">
<label class="form-label" for="initial-price">Initial price for new seats (VND)</label><input class="form-control mb-3" id="initial-price" name="price" inputmode="decimal" maxlength="64" required value="<c:out value='${priceValue}'/>"><p class="form-text">An explicit nonnegative decimal with at most two fractional digits is required. Use 0 for a free seat.</p><button class="btn btn-primary" type="submit">Generate missing seats</button></form></section>
<section class="account-card mt-0"><h2 class="h4">Update selected prices</h2><p>Select between 1 and 50 seats on this page. Every selected seat must belong to this event; the batch is atomic.</p>
<form id="batch-pricing" method="post" action="<c:out value='${inventoryUrl}'/>"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="eventId" value="${event.id}"><input type="hidden" name="action" value="price">
<input type="hidden" name="page" value="${result.number}"><input type="hidden" name="size" value="${result.size}">
<label class="form-label" for="batch-price">New selected price (VND)</label><input class="form-control mb-3" id="batch-price" name="price" inputmode="decimal" maxlength="64" required value="<c:out value='${priceValue}'/>"><button class="btn btn-primary" type="submit">Update selected seats</button></form></section>
</c:if>
<section class="account-card mt-0"><h2 class="h4">Publication</h2>
<c:choose><c:when test="${ready}"><p>Ready to publish. Publishing freezes the event details, venue, inventory membership and prices. Availability will become visible to everyone.</p>
<form method="post" action="<c:out value='${inventoryUrl}'/>"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="eventId" value="${event.id}"><input type="hidden" name="action" value="publish">
<div class="form-check mb-3"><input class="form-check-input" type="checkbox" name="confirmation" value="publish" id="confirm-publication" required><label class="form-check-label" for="confirm-publication">I confirm that the event details, inventory and prices are final.</label></div><button class="btn btn-primary" type="submit">Publish event</button></form>
</c:when><c:otherwise><p>Not ready for publication:</p><ul><c:forEach var="reason" items="${publicationReasons}"><li><c:out value="${reason}"/></li></c:forEach></ul></c:otherwise></c:choose>
</section>
<%@ include file="../common/inventory-layout.jspf" %>
<%@ include file="../common/inventory-paging.jspf" %>
</main><%@ include file="../common/footer.jspf" %></body></html>
