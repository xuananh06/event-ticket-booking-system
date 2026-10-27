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
            <c:url var="resourceUrl" value="/admin/${kind}"/>
            <div class="d-flex flex-wrap justify-content-between align-items-center gap-3 mb-4">
                <h1 class="h2"><c:out value="${pageTitle}"/></h1>
                <a class="btn btn-outline-dark" href="<c:out value='${resourceUrl}'/>">New record</a>
            </div>
            <%@ include file="../common/messages.jspf" %>
            <section class="account-card mt-0">
                <h2 class="h4"><c:out value="${empty form.id ? 'Create record' : 'Edit record'}"/></h2>
                <form method="post" action="<c:out value='${resourceUrl}'/>">
                    <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                    <input type="hidden" name="action" value="save">
                    <input type="hidden" name="id" value="<c:out value='${form.id}'/>">
                    <c:choose>
                        <c:when test="${kind == 'seats'}">
                            <label class="form-label" for="venueId">Venue ID</label>
                            <input
                                class="form-control mb-2"
                                id="venueId"
                                name="venueId"
                                inputmode="numeric"
                                list="venue-options"
                                required
                                value="<c:out value='${form.venueId}'/>">
                            <datalist id="venue-options">
                                <c:forEach var="venue" items="${venues}">
                                    <option value="<c:out value='${venue.id}'/>"><c:out value="${venue.name}"/> (<c:out value="${venue.active ? 'active' : 'inactive'}"/>)</option>
                                </c:forEach>
                            </datalist>
                            <p class="form-text">Use a venue ID from the venue list. Suggestions show up to 50 venues. Current venue: <c:out value="${currentVenue}"/>.</p>
                            <label class="form-label" for="rowLabel">Row label</label>
                            <input
                                class="form-control mb-3"
                                id="rowLabel"
                                name="rowLabel"
                                required
                                maxlength="10"
                                value="<c:out value='${form.rowLabel}'/>">
                            <label class="form-label" for="seatNumber">Seat number</label>
                            <input
                                class="form-control mb-3"
                                id="seatNumber"
                                name="seatNumber"
                                type="number"
                                min="1"
                                max="2147483647"
                                required
                                value="<c:out value='${form.seatNumber}'/>">
                        </c:when>
                        <c:otherwise>
                            <label class="form-label" for="name">Name</label>
                            <input
                                class="form-control mb-3"
                                id="name"
                                name="name"
                                required
                                maxlength="${kind == 'categories' ? 100 : 150}"
                                value="<c:out value='${form.name}'/>">
                            <c:if test="${kind == 'venues'}">
                                <label class="form-label" for="address">Address</label>
                                <input
                                    class="form-control mb-3"
                                    id="address"
                                    name="address"
                                    required
                                    maxlength="300"
                                    value="<c:out value='${form.address}'/>">
                            </c:if>
                        </c:otherwise>
                    </c:choose>
                    <label class="form-label" for="active">State</label>
                    <select class="form-select mb-3" id="active" name="active">
                        <option value="true" ${form.active != 'false' ? 'selected' : ''}>Active</option>
                        <option value="false" ${form.active == 'false' ? 'selected' : ''}>Inactive</option>
                    </select>
                    <button class="btn btn-primary" type="submit">Save record</button>
                </form>
            </section>
            <form class="d-flex flex-wrap gap-3 mb-3" method="get" action="<c:out value='${resourceUrl}'/>">
                <label>State <select class="form-select" name="filterActive">
                    <option value="">All</option>
                    <option value="true" ${param.filterActive == 'true' ? 'selected' : ''}>Active</option>
                    <option value="false" ${param.filterActive == 'false' ? 'selected' : ''}>Inactive</option>
                </select></label>
                <c:if test="${kind == 'seats'}">
                    <label>Venue ID <input
                        class="form-control"
                        name="filterVenue"
                        inputmode="numeric"
                        value="<c:out value='${param.filterVenue}'/>"></label>
                </c:if>
                <label>Page size <input
                    class="form-control"
                    type="number"
                    name="size"
                    min="1"
                    max="50"
                    value="<c:out value='${result.size}'/>"></label>
                <button class="btn btn-outline-dark align-self-end" type="submit">Filter</button>
            </form>
            <p><c:out value="${result.total}"/> records</p>
            <div class="table-responsive">
                <table class="table align-middle">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Record</th>
                            <th>State</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="item" items="${result.items}">
                            <tr>
                                <td><c:out value="${item.id}"/></td>
                                <td>
                                    <c:choose>
                                        <c:when test="${kind == 'seats'}"><c:out value="${item.venueName}"/> (ID <c:out value="${item.venueId}"/>) / <c:out value="${item.rowLabel}"/> <c:out value="${item.seatNumber}"/></c:when>
                                        <c:otherwise>
                                            <c:out value="${item.name}"/>
                                            <c:if test="${kind == 'venues'}">
                                                <br><c:out value="${item.address}"/>
                                            </c:if>
                                        </c:otherwise>
                                    </c:choose>
                                </td>
                                <td><span class="badge text-bg-secondary"><c:out value="${item.active ? 'Active' : 'Inactive'}"/></span></td>
                                <td>
                                    <div class="d-flex flex-wrap gap-2">
                                        <c:url var="editUrl" value="/admin/${kind}">
                                            <c:param name="id" value="${item.id}"/>
                                        </c:url>
                                        <a class="btn btn-sm btn-outline-dark" href="<c:out value='${editUrl}'/>">Edit</a>
                                        <form method="post" action="<c:out value='${resourceUrl}'/>">
                                            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                                            <input type="hidden" name="id" value="<c:out value='${item.id}'/>">
                                            <input type="hidden" name="action" value="status">
                                            <input type="hidden" name="active" value="${not item.active}">
                                            <button class="btn btn-sm btn-outline-dark" type="submit"><c:out value="${item.active ? 'Deactivate' : 'Activate'}"/></button>
                                        </form>
                                        <form method="post" action="<c:out value='${resourceUrl}'/>">
                                            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                                            <input type="hidden" name="id" value="<c:out value='${item.id}'/>">
                                            <input type="hidden" name="action" value="delete">
                                            <button class="btn btn-sm btn-outline-danger" type="submit">Delete unreferenced record</button>
                                        </form>
                                    </div>
                                </td>
                            </tr>
                        </c:forEach>
                    </tbody>
                </table>
            </div>
            <c:if test="${empty result.items}">
                <p>No matching records.</p>
            </c:if>
            <%@ include file="../common/paging.jspf" %>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
