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
            <h1 class="h2">Upcoming events</h1>
            <p class="text-secondary">Explore your university community. Times are shown in Asia/Bangkok.</p>
            <c:url var="eventsUrl" value="/events"/>
            <form method="get" action="<c:out value='${eventsUrl}'/>" class="account-card mt-0">
                <div class="row g-3">
                    <div class="col-md-6">
                        <label class="form-label" for="q">Title search</label>
                        <input class="form-control" id="q" name="q" maxlength="100" value="<c:out value='${param.q}'/>">
                    </div>
                    <div class="col-md-3">
                        <label class="form-label" for="category">Category ID</label>
                        <input
                            class="form-control"
                            id="category"
                            name="category"
                            list="category-options"
                            inputmode="numeric"
                            value="<c:out value='${param.category}'/>">
                    </div>
                    <div class="col-md-3">
                        <label class="form-label" for="venue">Venue ID</label>
                        <input
                            class="form-control"
                            id="venue"
                            name="venue"
                            list="venue-options"
                            inputmode="numeric"
                            value="<c:out value='${param.venue}'/>">
                    </div>
                    <div class="col-md-4">
                        <label class="form-label" for="from">From date</label>
                        <input
                            class="form-control"
                            id="from"
                            name="from"
                            type="date"
                            value="<c:out value='${param.from}'/>">
                    </div>
                    <div class="col-md-4">
                        <label class="form-label" for="to">Through date</label>
                        <input class="form-control" id="to" name="to" type="date" value="<c:out value='${param.to}'/>">
                    </div>
                    <div class="col-md-4">
                        <label class="form-label" for="size">Events per page</label>
                        <input
                            class="form-control"
                            id="size"
                            name="size"
                            type="number"
                            min="1"
                            max="50"
                            value="<c:out value='${result.size}'/>">
                    </div>
                </div>
                <datalist id="category-options">
                    <c:forEach var="category" items="${categories}">
                        <option value="${category.id}"><c:out value="${category.name}"/></option>
                    </c:forEach>
                </datalist>
                <datalist id="venue-options">
                    <c:forEach var="venue" items="${venues}">
                        <option value="${venue.id}"><c:out value="${venue.name}"/></option>
                    </c:forEach>
                </datalist>
                <p class="form-text mt-3">Use the name suggestions to choose an ID. Suggestions show up to 50 active categories and venues. Search treats punctuation literally.</p>
                <button class="btn btn-primary" type="submit">Find events</button>
                <a class="btn btn-outline-dark" href="<c:out value='${eventsUrl}'/>">Clear filters</a>
            </form>
            <p><c:out value="${result.total}"/> matching events</p>
            <div class="row g-4">
                <c:forEach var="event" items="${result.items}">
                    <div class="col-md-6 col-xl-4">
                        <article class="card h-100">
                            <div class="card-body d-flex flex-column">
                                <h2 class="h5"><c:out value="${event.title}"/></h2>
                                <p><c:out value="${event.categoryName}"/> / <c:out value="${event.venueName}"/></p>
                                <p class="text-secondary">Starts: <c:out value="${event.startsLabel}"/><br>Ends: <c:out value="${event.endsLabel}"/> (Asia/Bangkok)</p>
                                <c:url var="detailUrl" value="/events/detail">
                                    <c:param name="id" value="${event.id}"/>
                                </c:url>
                                <a class="btn btn-outline-dark mt-auto" href="<c:out value='${detailUrl}'/>">View event</a>
                            </div>
                        </article>
                    </div>
                </c:forEach>
            </div>
            <c:if test="${empty result.items}">
                <div class="alert alert-info">No matching events on this page. Adjust your filters or return to an earlier page.</div>
            </c:if>
            <%@ include file="../common/paging.jspf" %>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
