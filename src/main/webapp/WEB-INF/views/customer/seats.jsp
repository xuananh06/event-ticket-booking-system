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
            <c:url var="holdUrl" value="/customer/bookings/hold"/>
            <a href="<c:out value='${detailUrl}'/>">Back to event details</a>
            <h1 class="h2 mt-3">Select seats: <c:out value="${event.title}"/></h1>
            <p><c:out value="${event.venueName}"/> / Starts <c:out value="${event.startsLabel}"/> (Asia/Bangkok)</p>
            <%@ include file="../common/messages.jspf" %>
            <p id="selection-help">Select 1-50 available seats on this page. Changing page or page size clears the selection. Seats are reserved only after a successful submission.</p>
            <p>AVAILABLE means available now; HELD means temporarily reserved; UNAVAILABLE means sold or inactive. Availability may change before submission.</p>
            <form id="seat-selection" method="post" action="<c:out value='${holdUrl}'/>" aria-describedby="selection-help">
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <input type="hidden" name="action" value="hold">
                <input type="hidden" name="eventId" value="${event.id}">
                <input type="hidden" name="page" value="${result.number}">
                <input type="hidden" name="size" value="${result.size}">
                <div class="table-responsive">
                    <table class="table align-middle">
                        <caption>Current-page seats and VND prices</caption>
                        <thead>
                            <tr>
                                <th scope="col">Select</th>
                                <th scope="col">Seat</th>
                                <th scope="col">Price (VND)</th>
                                <th scope="col">State</th>
                            </tr>
                        </thead>
                        <c:forEach var="row" items="${rows}">
                            <tbody>
                                <tr>
                                    <th scope="rowgroup" colspan="4">Row <c:out value="${row.label}"/></th>
                                </tr>
                                <c:forEach var="seat" items="${row.seats}">
                                    <tr>
                                        <td>
                                            <input
                                                class="form-check-input"
                                                type="checkbox"
                                                name="eventSeatId"
                                                value="${seat.id}"
                                                id="select-${seat.id}"
                                                data-price="<c:out value='${seat.priceLabel}'/>"
                                                ${selected.contains(seat.id.toString()) ? 'checked' : ''}
                                                ${seat.state != 'AVAILABLE' ? 'disabled' : ''}>
                                            <label class="visually-hidden" for="select-${seat.id}">Select row <c:out value="${seat.rowLabel}"/> seat <c:out value="${seat.seatNumber}"/></label>
                                        </td>
                                        <td>Row <c:out value="${seat.rowLabel}"/> / <c:out value="${seat.seatNumber}"/></td>
                                        <td><c:out value="${seat.priceLabel}"/></td>
                                        <td><span class="badge text-bg-secondary"><c:out value="${seat.state}"/></span></td>
                                    </tr>
                                </c:forEach>
                            </tbody>
                        </c:forEach>
                    </table>
                </div>
                <c:if test="${empty result.items}">
                    <p class="alert alert-info">No seats on this page. Return to an earlier page.</p>
                </c:if>
                <p>Selected available seats: <output id="selected-count" aria-live="polite"><c:out value="${selectedCount}"/></output></p>
                <p>Provisional selected total: <output id="selected-total" aria-live="polite"><c:out value="${provisional}"/></output> VND</p>
                <p class="form-text">The server checks availability and calculates the final total from stored prices. A successful hold lasts five minutes and cannot be extended by refresh.</p>
                <noscript><p>The total above does not update as you select seats. Submit the form to receive the server-verified reservation summary; unavailable selections are rejected together.</p></noscript>
                <button class="btn btn-primary" type="submit">Hold selected seats for five minutes</button>
            </form>
            <%@ include file="../common/inventory-paging.jspf" %>
        </main>
        <%@ include file="../common/footer.jspf" %>
        <c:url var="selectionScriptUrl" value="/assets/js/seat-selection.js"/>
        <script src="<c:out value='${selectionScriptUrl}'/>" defer></script>
    </body>
</html>
