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
                <h1 class="h2">Reservation checkout</h1>
                <h2 class="h4"><c:out value="${booking.eventTitle}"/></h2>
                <dl class="row mt-4">
                    <dt class="col-sm-4">Booking code</dt>
                    <dd class="col-sm-8"><c:out value="${booking.code}"/></dd>
                    <dt class="col-sm-4">State</dt>
                    <dd class="col-sm-8" id="booking-state"><c:out value="${summary.state}"/></dd>
                    <dt class="col-sm-4">Quantity</dt>
                    <dd class="col-sm-8"><c:out value="${summary.items.size()}"/></dd>
                    <dt class="col-sm-4">Original hold deadline</dt>
                    <dd class="col-sm-8"><time datetime="<c:out value='${booking.expiresUtc}'/>"><c:out value="${booking.expiresLabel}"/> (Asia/Bangkok)</time></dd>
                    <dt class="col-sm-4">Remaining at server check</dt>
                    <dd class="col-sm-8"><c:out value="${summary.remainingSeconds}"/> seconds</dd>
                </dl>
                <c:choose>
                    <c:when test="${summary.state == 'PENDING'}">
                        <p class="alert alert-info">These seats are temporarily held. This reservation is not a confirmed ticket. Payment is not available yet.</p>
                    </c:when>
                    <c:when test="${summary.state == 'EXPIRED'}">
                        <p class="alert alert-warning">This reservation has expired. The seats may already have been reserved by someone else.</p>
                    </c:when>
                    <c:otherwise>
                        <p>This reservation is read-only.</p>
                    </c:otherwise>
                </c:choose>
                <p>Refreshing or signing in again does not extend the original deadline. Remaining time is checked by the server; this page does not renew or confirm a reservation.</p>
                <div class="table-responsive">
                    <table class="table">
                        <caption>Reserved seat and unit-price snapshots</caption>
                        <thead>
                            <tr>
                                <th scope="col">Seat</th>
                                <th scope="col">Unit price (VND)</th>
                            </tr>
                        </thead>
                        <tbody>
                            <c:forEach var="item" items="${summary.items}">
                                <tr>
                                    <td><c:out value="${item.seatLabel}"/></td>
                                    <td><c:out value="${item.priceLabel}"/></td>
                                </tr>
                            </c:forEach>
                        </tbody>
                        <tfoot>
                            <tr>
                                <th scope="row">Total (VND)</th>
                                <td><c:out value="${booking.totalLabel}"/></td>
                            </tr>
                        </tfoot>
                    </table>
                </div>
                <c:if test="${summary.canNormalize}">
                    <c:url var="expireUrl" value="/customer/bookings/expire"/>
                    <form method="post" action="<c:out value='${expireUrl}'/>">
                        <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                        <input type="hidden" name="action" value="expire">
                        <input type="hidden" name="bookingId" value="${booking.id}">
                        <button class="btn btn-outline-dark mb-3" type="submit">Release this expired hold</button>
                    </form>
                </c:if>
                <c:url var="selectUrl" value="/customer/events/seats">
                    <c:param name="eventId" value="${booking.eventId}"/>
                </c:url>
                <a href="<c:out value='${selectUrl}'/>">View current seats</a>
            </section>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>
