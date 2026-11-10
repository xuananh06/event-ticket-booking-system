<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
    <head><%@ include file="../common/head.jspf" %></head>
    <body>
        <%@ include file="../common/navigation.jspf" %>
        <main id="main-content" class="container page-content">
            <h1>My bookings</h1>
            <p>States reflect the server check. Viewing history does not extend, expire or modify a reservation.</p>
            <%@ include file="../common/messages.jspf" %>
            <c:url var="listUrl" value="/customer/bookings"/>
            <form method="get" action="<c:out value='${listUrl}'/>" class="d-flex flex-wrap align-items-end gap-3 mb-4">
                <div>
                    <label class="form-label" for="booking-status">State</label>
                    <select class="form-select" id="booking-status" name="status">
                        <option value="">All states</option>
                        <c:forEach var="state" items="${['PENDING','CONFIRMED','EXPIRED','CANCELLED']}">
                            <option value="${state}" ${historyStatus == state ? 'selected' : ''}><c:out value="${state}"/></option>
                        </c:forEach>
                    </select>
                </div>
                <div>
                    <label class="form-label" for="booking-size">Records per page</label>
                    <input class="form-control" id="booking-size" name="size" type="number" min="1" max="50" value="${historySize}">
                </div>
                <button class="btn btn-primary" type="submit">Filter bookings</button>
            </form>
            <p id="bookings-scroll-help" class="table-scroll-hint">Scroll the table horizontally when columns do not fit.</p>
            <div class="table-responsive table-scroll" role="region" tabindex="0" aria-labelledby="bookings-caption" aria-describedby="bookings-scroll-help">
                <table class="table table-wide">
                    <caption id="bookings-caption" class="caption-top">Your reservations, newest first</caption>
                    <thead>
                        <tr>
                            <th scope="col">Booking</th>
                            <th scope="col">Event</th>
                            <th scope="col">Seats</th>
                            <th scope="col">Total (VND)</th>
                            <th scope="col">State</th>
                            <th scope="col">Created (Asia/Bangkok)</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="entry" items="${result.items}">
                            <c:url var="bookingUrl" value="/customer/bookings/detail">
                                <c:param name="bookingId" value="${entry.booking.id}"/>
                                <c:param name="page" value="${result.number}"/>
                                <c:param name="size" value="${result.size}"/>
                                <c:param name="status" value="${historyStatus}"/>
                            </c:url>
                            <tr>
                                <td><a href="<c:out value='${bookingUrl}'/>"><c:out value="${entry.booking.code}"/></a></td>
                                <td><c:out value="${entry.booking.eventTitle}"/></td>
                                <td><c:out value="${entry.quantity}"/></td>
                                <td class="money"><c:out value="${entry.booking.totalLabel}"/></td>
                                <td class="state-value"><c:out value="${entry.state}"/></td>
                                <td><c:out value="${entry.booking.createdLabel}"/></td>
                            </tr>
                        </c:forEach>
                        <c:if test="${empty result.items}">
                            <tr><td colspan="6">No bookings on this page.</td></tr>
                        </c:if>
                    </tbody>
                </table>
            </div>
            <nav aria-label="Booking pagination" class="d-flex flex-wrap gap-3 my-4">
                <c:forEach var="direction" items="${['previous','next']}">
                    <c:if test="${(direction == 'previous' and result.previous) or (direction == 'next' and result.next)}">
                        <c:url var="pageUrl" value="/customer/bookings">
                            <c:param name="page" value="${result.number + (direction == 'next' ? 1 : -1)}"/>
                            <c:param name="size" value="${result.size}"/>
                            <c:param name="status" value="${historyStatus}"/>
                        </c:url>
                        <a href="<c:out value='${pageUrl}'/>"><c:out value="${direction == 'next' ? 'Next' : 'Previous'}"/></a>
                    </c:if>
                </c:forEach>
                <span>Page <c:out value="${result.number}"/>; <c:out value="${result.total}"/> records</span>
            </nav>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
