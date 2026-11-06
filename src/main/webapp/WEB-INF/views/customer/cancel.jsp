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
            <section class="account-card mt-0">
                <h1 class="h2">Confirm whole-booking cancellation</h1>
                <%@ include file="../common/messages.jspf" %>
                <%@ include file="booking-links.jspf" %>
                <h2 class="h4"><c:out value="${booking.eventTitle}"/></h2>
                <dl class="row my-4">
                    <dt class="col-sm-4">Booking code</dt>
                    <dd class="col-sm-8"><c:out value="${booking.code}"/></dd>
                    <dt class="col-sm-4">Current state</dt>
                    <dd class="col-sm-8" id="booking-state"><c:out value="${summary.state}"/></dd>
                    <dt class="col-sm-4">Seats</dt>
                    <dd class="col-sm-8"><c:out value="${summary.items.size()}"/></dd>
                    <dt class="col-sm-4">Stored total (VND)</dt>
                    <dd class="col-sm-8"><c:out value="${booking.totalLabel}"/></dd>
                </dl>
                <c:choose>
                    <c:when test="${canCancel}">
                        <c:choose>
                            <c:when test="${summary.state == 'CONFIRMED'}">
                                <p id="refund-preview">Full simulated refund: <strong><c:out value="${booking.totalLabel}"/> VND</strong>. No real money is transferred.</p>
                            </c:when>
                            <c:otherwise>
                                <p id="refund-preview">No successful payment has been collected. Cancellation releases the entire live hold without a refund.</p>
                            </c:otherwise>
                        </c:choose>
                        <p>All reserved seats will be released together. This cannot be undone. Eligibility is checked again using database time when you submit.</p>
                        <ul>
                            <c:forEach var="item" items="${summary.items}">
                                <li><c:out value="${item.seatLabel}"/>: <c:out value="${item.priceLabel}"/> VND</li>
                            </c:forEach>
                        </ul>
                        <c:url var="cancelAction" value="/customer/bookings/cancel"/>
                        <form method="post" action="<c:out value='${cancelAction}'/>">
                            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                            <input type="hidden" name="action" value="cancel">
                            <input type="hidden" name="bookingId" value="${booking.id}">
                            <%@ include file="booking-context.jspf" %>
                            <div class="form-check my-3">
                                <input class="form-check-input" id="confirm-cancellation" type="checkbox" name="confirm" value="yes" required>
                                <label class="form-check-label" for="confirm-cancellation">I confirm cancellation of the entire booking.</label>
                            </div>
                            <button class="btn btn-danger" type="submit">Confirm whole-booking cancellation</button>
                        </form>
                    </c:when>
                    <c:otherwise>
                        <p>This booking cannot accept a new cancellation. It may already be cancelled, expired or past event start. Review its current details.</p>
                    </c:otherwise>
                </c:choose>
                <div class="d-flex flex-wrap gap-3 mt-4">
                    <a href="<c:out value='${detailUrl}'/>">Return to booking details</a>
                    <a href="<c:out value='${historyUrl}'/>">Return to My bookings</a>
                </div>
            </section>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
