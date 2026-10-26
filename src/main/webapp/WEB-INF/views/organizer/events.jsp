<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
<head><%@ include file="../common/head.jspf" %></head>
<body>
<%@ include file="../common/navigation.jspf" %>
<main id="main-content" class="container page-content">
<c:url var="eventsUrl" value="/organizer/events"/>
<div class="d-flex flex-wrap justify-content-between gap-3 mb-4"><h1 class="h2">Your events</h1><a class="btn btn-outline-dark" href="<c:out value='${eventsUrl}'/>">New draft</a></div>
<%@ include file="../common/messages.jspf" %>
<c:if test="${not empty event}">
<section class="account-card mt-0"><h2><c:out value="${event.title}"/></h2><span class="badge text-bg-secondary"><c:out value="${event.status}"/></span>
<p class="mt-3"><c:out value="${event.categoryName}"/> / <c:out value="${event.venueName}"/></p>
<p><c:out value="${event.startsLabel}"/> – <c:out value="${event.endsLabel}"/> (Asia/Bangkok)</p>
<pre class="event-description"><c:out value="${event.description}"/></pre>
<c:if test="${not event.draft}"><p>This event is read-only.</p></c:if>
</section>
</c:if>
<c:if test="${empty event or event.draft}">
<section class="account-card mt-0"><h2 class="h4"><c:out value="${empty form.id ? 'Create draft' : 'Edit draft'}"/></h2>
<form method="post" action="<c:out value='${eventsUrl}'/>">
<input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="action" value="save"><input type="hidden" name="id" value="<c:out value='${form.id}'/>">
<label class="form-label" for="title">Title</label><input class="form-control mb-3" id="title" name="title" maxlength="200" required value="<c:out value='${form.title}'/>">
<label class="form-label" for="description">Description (plain text)</label><textarea class="form-control mb-3" id="description" name="description" rows="5" maxlength="10000" required><c:out value="${form.description}"/></textarea>
<label class="form-label" for="category">Category ID</label><input class="form-control mb-2" id="category" name="category" list="category-options" inputmode="numeric" required value="<c:out value='${form.category}'/>">
<datalist id="category-options"><c:forEach var="category" items="${categories}"><option value="${category.id}"><c:out value="${category.name}"/> (<c:out value="${category.active ? 'active' : 'inactive'}"/>)</option></c:forEach></datalist>
<p class="form-text">Current category: <c:out value="${event.categoryName}"/>. Suggestions include inactive records; new assignments require active records.</p>
<label class="form-label" for="venue">Venue ID</label><input class="form-control mb-2" id="venue" name="venue" list="venue-options" inputmode="numeric" required value="<c:out value='${form.venue}'/>">
<datalist id="venue-options"><c:forEach var="venue" items="${venues}"><option value="${venue.id}"><c:out value="${venue.name}"/> (<c:out value="${venue.active ? 'active' : 'inactive'}"/>)</option></c:forEach></datalist>
<p class="form-text">Current venue: <c:out value="${event.venueName}"/>. Suggestions show up to 50 records. Existing unchanged inactive references can be retained.</p>
<p>Enter local times in Asia/Bangkok (UTC+07:00).</p>
<label class="form-label" for="startsAt">Starts at</label><input class="form-control mb-3" type="datetime-local" step="0.001" id="startsAt" name="startsAt" required value="<c:out value='${form.startsAt}'/>">
<label class="form-label" for="endsAt">Ends at</label><input class="form-control mb-3" type="datetime-local" step="0.001" id="endsAt" name="endsAt" required value="<c:out value='${form.endsAt}'/>">
<button class="btn btn-primary" type="submit">Save draft</button></form></section>
</c:if>
<form method="get" action="<c:out value='${eventsUrl}'/>" class="d-flex flex-wrap gap-3 mb-3">
<label>Title search<input class="form-control" name="q" maxlength="100" value="<c:out value='${param.q}'/>"></label>
<label>Status<select class="form-select" name="status"><option value="">All</option><c:forTokens var="status" items="DRAFT,PUBLISHED,CLOSED,CANCELLED" delims=","><option value="${status}" ${param.status == status ? 'selected' : ''}><c:out value="${status}"/></option></c:forTokens></select></label>
<label>Category ID<input class="form-control" name="category" inputmode="numeric" value="<c:out value='${param.category}'/>"></label>
<label>Venue ID<input class="form-control" name="venue" inputmode="numeric" value="<c:out value='${param.venue}'/>"></label><button class="btn btn-outline-dark align-self-end" type="submit">Filter</button>
</form>
<p><c:out value="${result.total}"/> events</p><div class="table-responsive"><table class="table align-middle"><thead><tr><th>Event</th><th>References</th><th>Starts (Asia/Bangkok)</th><th>Status</th><th>Actions</th></tr></thead><tbody>
<c:forEach var="item" items="${result.items}"><tr><td><c:out value="${item.title}"/></td><td><c:out value="${item.categoryName}"/><br><c:out value="${item.venueName}"/></td><td><c:out value="${item.startsLabel}"/></td><td><c:out value="${item.status}"/></td><td>
<c:url var="detailUrl" value="/organizer/events"><c:param name="id" value="${item.id}"/></c:url><a class="btn btn-sm btn-outline-dark" href="<c:out value='${detailUrl}'/>"><c:out value="${item.draft ? 'View / edit' : 'View'}"/></a>
<c:if test="${item.draft}"><form class="d-inline" method="post" action="<c:out value='${eventsUrl}'/>"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${item.id}"><button class="btn btn-sm btn-outline-danger" type="submit">Delete unreferenced draft</button></form></c:if>
</td></tr></c:forEach></tbody></table></div><c:if test="${empty result.items}"><p>No matching events.</p></c:if>
<%@ include file="../common/paging.jspf" %>
</main>
<%@ include file="../common/footer.jspf" %>
</body>
</html>
