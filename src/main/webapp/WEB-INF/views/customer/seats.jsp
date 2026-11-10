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
            <form id="seat-selection" method="post" action="<c:out value='${holdUrl}'/>" aria-describedby="selection-help selection-error">
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <input type="hidden" name="action" value="hold">
                <input type="hidden" name="eventId" value="${event.id}">
                <input type="hidden" name="page" value="${result.number}">
                <input type="hidden" name="size" value="${result.size}">
                <div class="selection-summary mb-3">
                    <p>Selected available seats: <output id="selected-count" aria-live="polite"><c:out value="${selectedCount}"/></output></p>
                    <p class="mb-0">Provisional selected total: <output id="selected-total" aria-live="polite"><c:out value="${provisional}"/></output> VND</p>
                </div>
                <div id="selection-error" class="field-error"><c:out value="${errors.eventSeatId}"/></div>
                <p id="selection-scroll-help" class="table-scroll-hint">Scroll the table horizontally when columns do not fit.</p>
                <div class="table-responsive table-scroll" role="region" tabindex="0" aria-labelledby="selection-caption" aria-describedby="selection-scroll-help">
                    <table class="table align-middle selection-table">
                        <caption id="selection-caption" class="caption-top">Current-page seats and VND prices</caption>
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
                                                class="form-check-input seat-choice-input"
                                                type="checkbox"
                                                name="eventSeatId"
                                                value="${seat.id}"
                                                id="select-${seat.id}"
                                                aria-describedby="selection-help selection-error"
                                                aria-invalid="${not empty errors.eventSeatId}"
                                                data-price="<c:out value='${seat.priceLabel}'/>"
                                                ${selected.contains(seat.id.toString()) ? 'checked' : ''}
                                                ${seat.state != 'AVAILABLE' ? 'disabled' : ''}>
                                            <label class="seat-choice" for="select-${seat.id}"><span aria-hidden="true"><c:out value="${seat.state == 'AVAILABLE' ? 'Choose' : 'Unavailable'}"/></span><span class="visually-hidden">Select row <c:out value="${seat.rowLabel}"/> seat <c:out value="${seat.seatNumber}"/></span></label>
                                        </td>
                                        <td>Row <c:out value="${seat.rowLabel}"/> / <c:out value="${seat.seatNumber}"/></td>
                                        <td class="money"><c:out value="${seat.priceLabel}"/></td>
                                        <td><span class="badge text-bg-secondary"><c:out value="${seat.state}"/></span></td>
                                    </tr>
                                </c:forEach>
                            </tbody>
                        </c:forEach>
                    </table>
                </div>
                <c:if test="${empty result.items}">
                    <p class="alert alert-info"><c:out value="${result.total == 0 ? 'No seats are listed for this event.' : 'No seats on this page. Return to an earlier page.'}"/></p>
                </c:if>
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
