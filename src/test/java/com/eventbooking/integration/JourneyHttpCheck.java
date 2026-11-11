package com.eventbooking.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static com.eventbooking.dao.JdbcAccess.*;
import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

/** One real application graph; only its initial Admin uses a privileged fixture helper. */
class JourneyHttpCheck {
    @Test
    void adminPublicationPurchaseRefundRebookingAndIndependentStatisticsReconcile() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        for (String route : List.of("admin/users", "organizer/events", "customer/bookings")) {
            guest.redirect(guest.get(route), "login");
        }
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(503, guest.get("events"));
            status(200, guest.get("login"));
            System.out.println("HTTP integrated journey guest/unavailable boundaries: PASS; no connected journey claimed.");
            return;
        }
        try (Journey x = new Journey()) {
            x.setup();
            x.reconcile(0, 0, 0, "0.00", "0.00");
            var initial = x.snapshot();
            var listed = guest.get(x.catalog()); status(200, listed);
            assertTrue(listed.body().contains("2 matching events"));
            var second = guest.get(PaymentHttpCheck.link(listed, "Next")); status(200, second);
            assertTrue(second.body().contains(x.targetTitle));
            var detail = guest.get(PaymentHttpCheck.link(second, "View event")); status(200, detail);
            assertTrue(detail.body().contains("&lt;description&gt;"));
            status(200, guest.get(PaymentHttpCheck.link(detail, "View seat availability and prices")));
            assertEquals(initial, x.snapshot(), "Public browsing must not allocate or modify data.");

            long first = x.hold(x.firstEmail);
            LocalDateTime deadline = x.deadline(first);
            x.assertBooking(first, x.firstCustomer, "PENDING", 0, 0);
            x.reconcile(1, 0, 0, "0.00", "0.00");

            HttpBrowser customer = x.login(x.firstEmail, "customer");
            var failedForm = FinancialForms.form(customer.get(x.detail(first)), "pay");
            var failed = x.follow(customer, customer.post(failedForm.action(), failedForm.submit("failure")), first);
            assertTrue(failed.body().contains("Original outcome: <strong>failure</strong>"));
            assertEquals(deadline, x.deadline(first));
            x.assertBooking(first, x.firstCustomer, "PENDING", 1, 0);
            x.reconcile(1, 0, 0, "0.00", "0.00");

            customer = x.login(x.firstEmail, "customer");
            var successful = FinancialForms.form(customer.get(x.detail(first)), "pay");
            assertFalse(successful.fields().get("paymentKey").equals(failedForm.fields().get("paymentKey")), "A new attempt has its own key.");
            var confirmed = x.follow(customer, customer.post(successful.action(), successful.submit("success")), first);
            assertTrue(confirmed.body().contains("Current booking state: <strong>CONFIRMED</strong>"));
            x.assertBooking(first, x.firstCustomer, "CONFIRMED", 2, 1);
            assertEquals(deadline, x.deadline(first));
            var paid = x.snapshot();
            var replay = FinancialForms.form(confirmed, "replay");
            assertTrue(successful.fields().get("paymentKey").equals(replay.fields().get("paymentKey")), "Replay must use the recorded key.");
            x.follow(customer, customer.post(replay.action(), replay.fields()), first);
            assertEquals(paid, x.snapshot());
            x.reconcile(0, 1, 0, "300000.00", "0.00");

            customer = x.login(x.firstEmail, "customer");
            var cancelPage = customer.get("customer/bookings/cancel?bookingId=" + first + Journey.CONTEXT); status(200, cancelPage);
            var cancellation = FinancialForms.form(cancelPage, "cancel");
            var beforeInvalid = x.snapshot();
            var invalid = customer.post(cancellation.action(), cancellation.fields()); status(400, invalid);
            invalidField(invalid, "confirm-cancellation", "cancellation-error");
            assertEquals(beforeInvalid, x.snapshot());
            cancellation = FinancialForms.form(invalid, "cancel");
            var values = new LinkedHashMap<>(cancellation.fields()); values.put("confirm", "yes");
            var cancelled = x.follow(customer, customer.post(cancellation.action(), values), first);
            assertTrue(cancelled.body().contains("refunded in full"));
            assertTrue(cancelled.body().contains("REFUNDED"));
            x.assertBooking(first, x.firstCustomer, "CANCELLED", 2, 1);
            assertEquals(deadline, x.deadline(first));
            assertEquals(12, x.id("SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='AVAILABLE' AND current_booking_id IS NULL AND hold_expires_at IS NULL", x.target));
            x.reconcile(0, 0, 1, "300000.00", "300000.00");

            long secondBooking = x.hold(x.secondEmail);
            assertNotEquals(first, secondBooking);
            LocalDateTime nextDeadline = x.deadline(secondBooking);
            x.assertBooking(secondBooking, x.secondCustomer, "PENDING", 0, 0);
            x.reconcile(0, 0, 1, 1, 0, "300000.00", "300000.00");
            var rebooked = x.snapshot();
            customer = x.login(x.firstEmail, "customer");
            values.put("_csrf", token(customer.get("customer/home").body()));
            x.follow(customer, customer.post(cancellation.action(), values), first);
            var oldPage = customer.get(x.detail(first)); status(200, oldPage);
            var oldReplay = FinancialForms.form(oldPage, "replay");
            var replayed = x.follow(customer, customer.post(oldReplay.action(), oldReplay.fields()), first);
            assertTrue(replayed.body().contains("Current attempt state: <strong>REFUNDED</strong>"));
            status(409, customer.post("customer/bookings/expire", fields("_csrf", token(replayed.body()), "action", "expire", "bookingId", Long.toString(first))));
            assertEquals(rebooked, x.snapshot(), "Old cancellation, replay and expiry must preserve the new allocation.");
            assertEquals(nextDeadline, x.deadline(secondBooking));
            x.assertBooking(secondBooking, x.secondCustomer, "PENDING", 0, 0);

            customer = x.login(x.secondEmail, "customer");
            var newPayment = FinancialForms.form(customer.get(x.detail(secondBooking)), "pay");
            x.follow(customer, customer.post(newPayment.action(), newPayment.submit("success")), secondBooking);
            x.assertBooking(secondBooking, x.secondCustomer, "CONFIRMED", 1, 1);
            x.reconcile(0, 0, 1, 0, 1, "600000.00", "300000.00");
            x.finalHistoryAndOwnership(first, secondBooking);
            x.assertFinalGraph();
        }
        System.out.println("HTTP Admin-created graph, Organizer publication, page-two multi-seat purchase, failed/retried/replayed payment, one full refund, second-Customer rebooking and independent SQL/rendered statistics: PASS");
    }

    /** Reused by the ignored targeted-browser bootstrap; never creates final financial states. */
    static final class Journey implements AutoCloseable {
        static final String CONTEXT = "&page=2&size=10&status=PENDING&paymentPage=1&paymentSize=1";
        final P3Fixture f = new P3Fixture();
        final String organizerEmail = f.db.email(), otherOrganizerEmail = f.db.email();
        final String firstEmail = f.db.email(), secondEmail = f.db.email();
        final String categoryName = f.prefix + "Journey category", venueName = f.prefix + "Journey venue";
        final String targetTitle = f.prefix + "Journey beta";
        final List<String> titles = List.of(f.prefix + "Journey alpha", targetTitle);
        long organizer, otherOrganizer, firstCustomer, secondCustomer, category, venue, target;
        List<String> selected;
        Map<String, String> baseline;

        Journey() throws Exception { }

        void setup() throws Exception {
            HttpBrowser admin = login(f.email(f.admin), "admin");
            organizer = account(admin, organizerEmail, "ORGANIZER");
            otherOrganizer = account(admin, otherOrganizerEmail, "ORGANIZER");
            firstCustomer = account(admin, firstEmail, "CUSTOMER");
            secondCustomer = account(admin, secondEmail, "CUSTOMER");
            var form = form(admin.get("admin/categories"), "save", null);
            admin.redirect(admin.post(form.action(), form.fields("action", "save", "id", "", "name", categoryName, "active", "true")), "admin/categories");
            category = id("SELECT id FROM dbo.categories WHERE name=?", categoryName); f.categories.add(category);
            form = form(admin.get("admin/venues"), "save", null);
            admin.redirect(admin.post(form.action(), form.fields("action", "save", "id", "", "name", venueName, "address", "Journey campus", "active", "true")), "admin/venues");
            venue = id("SELECT id FROM dbo.venues WHERE name=?", venueName); f.venues.add(venue);
            for (int number = 1; number <= 12; number++) {
                form = form(admin.get("admin/seats"), "save", null);
                admin.redirect(admin.post(form.action(), form.fields("action", "save", "id", "", "venueId", Long.toString(venue), "rowLabel", "R", "seatNumber", Integer.toString(number), "active", "true")), "admin/seats");
                f.seats.add(id("SELECT id FROM dbo.seats WHERE venue_id=? AND row_label='R' AND seat_number=?", venue, number));
            }
            HttpBrowser owner = login(organizerEmail, "organizer");
            for (int i = 0; i < titles.size(); i++) {
                int days = 14 + i * 7;
                var now = f.access.read(c -> { try (var s = statement(c, "SELECT CONVERT(datetime2(3),DATEADD(DAY,?,SYSUTCDATETIME()))", days); var r = s.executeQuery()) { assertTrue(r.next()); return r.getObject(1, LocalDateTime.class); } });
                var draft = form(owner.get("organizer/events"), "save", null);
                // Dedicated Unicode/markup fixture proves request, storage and escaped rendering.
                owner.redirect(owner.post(draft.action(), draft.fields("action", "save", "id", "", "title", titles.get(i), "description", "Unicode fixture 界 <description>\nSecond line", "category", Long.toString(category), "venue", Long.toString(venue), "startsAt", com.eventbooking.service.EventTimes.input(now), "endsAt", com.eventbooking.service.EventTimes.input(now.plusHours(2)))), "organizer/events");
                long event = id("SELECT id FROM dbo.events WHERE organizer_id=? AND title=?", organizer, titles.get(i)); f.events.add(event);
                String route = "organizer/events/inventory?eventId=" + event;
                var generation = FinancialForms.form(owner.get(route), "generate");
                var values = new LinkedHashMap<>(generation.fields()); values.put("price", "100000.00");
                owner.redirect(owner.post(generation.action(), values), route);
                if (i == 1) {
                    target = event;
                    selected = ids("SELECT es.id FROM dbo.event_seats es JOIN dbo.seats s ON s.id=es.seat_id WHERE es.event_id=? ORDER BY s.row_label COLLATE Latin1_General_100_BIN2,s.seat_number,es.id OFFSET 10 ROWS FETCH NEXT 2 ROWS ONLY", target);
                    for (int j = 0; j < 2; j++) {
                        var pricing = form(owner.get(route + "&page=2&size=10"), "price", "eventSeatId", Long.parseLong(selected.get(j)));
                        owner.redirect(owner.post(pricing.action(), pricing.fields("action", "price", "eventId", Long.toString(target), "eventSeatId", selected.get(j), "page", "2", "size", "10", "price", j == 0 ? "150000.25" : "149999.75")), route);
                    }
                }
                var publication = FinancialForms.form(owner.get(route), "publish");
                var confirmed = new LinkedHashMap<>(publication.fields()); confirmed.put("confirmation", "publish");
                owner.redirect(owner.post(publication.action(), confirmed), route);
                assertEquals(1, id("SELECT COUNT_BIG(*) FROM dbo.events WHERE id=? AND organizer_id=? AND status='PUBLISHED'", event, organizer));
            }
            assertEquals(2, selected.size());
            baseline = metrics("admin", f.admin);
        }

        private long account(HttpBrowser admin, String email, String role) throws Exception {
            var create = form(admin.get("admin/users"), "save", null);
            admin.redirect(admin.post(create.action(), create.fields("action", "save", "id", "", "fullName", "Journey " + role, "email", email, "role", role, "active", "true", "password", P3Fixture.PASSWORD, "confirmation", P3Fixture.PASSWORD)), "admin/users");
            return id("SELECT id FROM dbo.users WHERE email=? AND role=? AND is_active=1", email, role);
        }

        HttpBrowser login(String email, String role) throws Exception {
            HttpBrowser browser = new HttpBrowser(); browser.login(email, P3Fixture.PASSWORD, role + "/home"); return browser;
        }

        String catalog() { return "events?q=" + URLEncoder.encode(f.prefix + "Journey", StandardCharsets.UTF_8) + "&category=" + category + "&venue=" + venue + "&size=1"; }
        String selection() { return "customer/events/seats?eventId=" + target + "&page=2&size=10"; }
        String detail(long booking) { return "customer/bookings/detail?bookingId=" + booking + CONTEXT; }

        long hold(String email) throws Exception {
            HttpBrowser customer = login(email, "customer");
            var page = customer.get(selection()); status(200, page);
            assertTrue(page.body().contains("Page 2 &middot;"));
            var hold = FinancialForms.form(page, "hold");
            assertNull(URI.create(hold.action()).getRawQuery(), "Mutation URLs must remain query-free.");
            assertEquals("2", hold.fields().get("page")); assertEquals("10", hold.fields().get("size"));
            for (String inventory : selected) { assertTrue(page.body().contains("id=\"select-" + inventory + "\"")); }
            var response = customer.post(hold.action(), hold.fields(), "eventSeatId", selected); status(303, response);
            var matcher = Pattern.compile("bookingId=([0-9]+)$").matcher(response.headers().firstValue("Location").orElseThrow());
            assertTrue(matcher.find(), "The actual hold must redirect to its checkout.");
            long booking = Long.parseLong(matcher.group(1));
            var checkout = customer.get(response.headers().firstValue("Location").orElseThrow()); status(200, checkout);
            assertTrue(checkout.body().contains("id=\"stored-total\">300000.00</strong>"));
            assertEquals(300000, id("SELECT DATEDIFF_BIG(MILLISECOND,created_at,expires_at) FROM dbo.bookings WHERE id=?", booking));
            return booking;
        }

        HttpResponse<String> follow(HttpBrowser browser, HttpResponse<String> response, long booking) throws Exception {
            var page = PaymentHttpCheck.follow(browser, response, booking);
            String location = response.headers().firstValue("Location").orElseThrow();
            assertTrue(location.contains("page=2&size=10&status=PENDING&paymentPage=1&paymentSize=1"));
            return page;
        }

        LocalDateTime deadline(long booking) {
            return f.access.read(c -> { try (var s = statement(c, "SELECT expires_at FROM dbo.bookings WHERE id=? AND event_id=?", booking, target); var r = s.executeQuery()) { assertTrue(r.next()); return r.getObject(1, LocalDateTime.class); } });
        }

        void assertBooking(long booking, long customer, String state, long attempts, long successes) {
            assertEquals(1, id("SELECT COUNT_BIG(*) FROM dbo.bookings WHERE id=? AND event_id=? AND customer_id=? AND status=? AND total_amount=300000.00", booking, target, customer, state));
            assertEquals(2, id("SELECT COUNT_BIG(*) FROM dbo.booking_items WHERE booking_id=? AND event_id=?", booking, target));
            assertEquals(selected.stream().map(Long::parseLong).sorted().toList(), ids("SELECT event_seat_id FROM dbo.booking_items WHERE booking_id=? ORDER BY event_seat_id", booking).stream().map(Long::parseLong).toList());
            assertEquals(2, id("SELECT COUNT_BIG(*) FROM dbo.booking_items bi JOIN dbo.event_seats es ON es.id=bi.event_seat_id WHERE bi.booking_id=? AND bi.unit_price=es.price", booking));
            assertEquals(1, id("SELECT COUNT_BIG(*) FROM dbo.bookings b WHERE b.id=? AND b.total_amount=(SELECT SUM(unit_price) FROM dbo.booking_items WHERE booking_id=b.id)", booking));
            assertEquals(attempts, id("SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=?", booking));
            assertEquals(successes, id("SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=? AND succeeded_at IS NOT NULL", booking));
            assertEquals(attempts, id("SELECT COUNT_BIG(*) FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.id=? AND p.amount=b.total_amount", booking));
            String current = state.equals("PENDING") ? "HELD" : "SOLD";
            if (state.equals("CANCELLED")) {
                assertEquals(1, id("SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=? AND status='REFUNDED' AND amount=300000.00 AND succeeded_at IS NOT NULL AND refunded_at IS NOT NULL", booking));
            } else {
                assertEquals(2, id("SELECT COUNT_BIG(*) FROM dbo.event_seats es JOIN dbo.booking_items bi ON bi.event_seat_id=es.id WHERE bi.booking_id=? AND es.current_booking_id=? AND es.state=?", booking, booking, current));
                assertEquals(0, id("SELECT COUNT_BIG(*) FROM dbo.event_seats es JOIN dbo.bookings b ON b.id=es.current_booking_id WHERE b.id=? AND ((es.state='HELD' AND es.hold_expires_at<>b.expires_at) OR (es.state='SOLD' AND es.hold_expires_at IS NOT NULL))", booking));
            }
        }

        void reconcile(long pending, long confirmed, long cancelled, String gross, String refunded) throws Exception {
            reconcile(pending, confirmed, cancelled, 0, 0, gross, refunded);
        }

        void reconcile(long pending, long confirmed, long cancelled, long secondPending, long secondConfirmed, String gross, String refunded) throws Exception {
            Map<String, String> first = metrics("customer", firstCustomer), second = metrics("customer", secondCustomer);
            assertEquals(Long.toString(pending + confirmed + cancelled), first.get("total-bookings")); assertEquals("0", first.get("expired-bookings"));
            assertEquals(Long.toString(pending), first.get("pending-bookings")); assertEquals(Long.toString(confirmed), first.get("confirmed-bookings")); assertEquals(Long.toString(cancelled), first.get("cancelled-bookings"));
            assertEquals(Long.toString(secondPending + secondConfirmed), second.get("total-bookings")); assertEquals(Long.toString(secondPending), second.get("pending-bookings")); assertEquals(Long.toString(secondConfirmed), second.get("confirmed-bookings")); assertEquals("0", second.get("expired-bookings")); assertEquals("0", second.get("cancelled-bookings"));
            long tickets = confirmed * 2 + Long.parseLong(second.get("confirmed-bookings")) * 2;
            var owner = metrics("organizer", organizer);
            assertEquals("2", owner.get("total-events")); assertEquals("2", owner.get("upcoming-published-events"));
            assertSales(owner, tickets, gross, refunded);
            var global = metrics("admin", f.admin);
            assertEquals(baseline.get("total-accounts"), global.get("total-accounts")); assertEquals(baseline.get("total-events"), global.get("total-events"));
            assertEquals(Long.parseLong(baseline.get("total-bookings")) + Long.parseLong(first.get("total-bookings")) + Long.parseLong(second.get("total-bookings")), Long.parseLong(global.get("total-bookings")));
            assertSales(global, Long.parseLong(baseline.get("tickets-sold")) + tickets, new BigDecimal(baseline.get("gross-payments")).add(new BigDecimal(gross)).toPlainString(), new BigDecimal(baseline.get("refunded-payments")).add(new BigDecimal(refunded)).toPlainString());
            var untouched = snapshot();
            home(firstEmail, "customer", first); home(secondEmail, "customer", second); home(organizerEmail, "organizer", owner); home(otherOrganizerEmail, "organizer", metrics("organizer", otherOrganizer)); home(f.email(f.admin), "admin", global);
            assertEquals("0", metrics("organizer", otherOrganizer).get("total-events"));
            assertSales(metrics("organizer", otherOrganizer), 0, "0.00", "0.00");
            assertEquals(untouched, snapshot(), "Statistics GET/HEAD and authentication must not change stored business data.");
        }

        private void home(String email, String role, Map<String, String> expected) throws Exception {
            HttpBrowser browser = login(email, role); var page = browser.get(role + "/home"); status(200, page); status(200, browser.head(role + "/home"));
            for (var entry : expected.entrySet()) {
                var matcher = Pattern.compile("<p\\b[^>]*id=\"metric-" + Pattern.quote(entry.getKey()) + "\"[^>]*>\\s*([0-9]+(?:\\.[0-9]{2})?)\\s*</p>").matcher(page.body());
                assertTrue(matcher.find(), "Missing rendered metric: " + entry.getKey()); assertEquals(entry.getValue(), matcher.group(1), entry.getKey()); assertFalse(matcher.find());
            }
        }

        /** Independent aggregate queries use the documented grains, never StatisticsDao/Service. */
        Map<String, String> metrics(String role, long actor) {
            String sql;
            if (role.equals("customer")) {
                sql = "DECLARE @now datetime2(3)=SYSUTCDATETIME(); SELECT COUNT_BIG(*) [total-bookings],"
                        + "COALESCE(SUM(CONVERT(bigint,CASE WHEN status='PENDING' AND expires_at>@now THEN 1 ELSE 0 END)),0) [pending-bookings],"
                        + "COALESCE(SUM(CONVERT(bigint,CASE WHEN status='CONFIRMED' THEN 1 ELSE 0 END)),0) [confirmed-bookings],"
                        + "COALESCE(SUM(CONVERT(bigint,CASE WHEN status='EXPIRED' OR (status='PENDING' AND expires_at<=@now) THEN 1 ELSE 0 END)),0) [expired-bookings],"
                        + "COALESCE(SUM(CONVERT(bigint,CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END)),0) [cancelled-bookings] FROM dbo.bookings WHERE customer_id=" + actor;
            } else {
                String scope = role.equals("organizer") ? " AND e.organizer_id=" + actor : "";
                String counts = role.equals("organizer")
                        ? "(SELECT COUNT_BIG(*) FROM dbo.events e WHERE 1=1" + scope + ") [total-events],(SELECT COUNT_BIG(*) FROM dbo.events e WHERE status='PUBLISHED' AND starts_at>@now" + scope + ") [upcoming-published-events],"
                        : "(SELECT COUNT_BIG(*) FROM dbo.users) [total-accounts],(SELECT COUNT_BIG(*) FROM dbo.events) [total-events],(SELECT COUNT_BIG(*) FROM dbo.bookings) [total-bookings],";
                sql = "DECLARE @now datetime2(3)=SYSUTCDATETIME(); SELECT " + counts
                        + "(SELECT COUNT_BIG(*) FROM dbo.booking_items i JOIN dbo.bookings b ON b.id=i.booking_id JOIN dbo.events e ON e.id=b.event_id WHERE b.status='CONFIRMED'" + scope + ") [tickets-sold],"
                        + "(SELECT COALESCE(SUM(p.amount),CONVERT(decimal(38,2),0)) FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id JOIN dbo.events e ON e.id=b.event_id WHERE p.succeeded_at IS NOT NULL" + scope + ") [gross-payments],"
                        + "(SELECT COALESCE(SUM(p.amount),CONVERT(decimal(38,2),0)) FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id JOIN dbo.events e ON e.id=b.event_id WHERE p.status='REFUNDED'" + scope + ") [refunded-payments]";
            }
            return f.access.read(c -> {
                Map<String, String> result = new LinkedHashMap<>();
                try (var s = statement(c, sql); var r = s.executeQuery()) { assertTrue(r.next()); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) { result.put(r.getMetaData().getColumnLabel(i), r.getString(i)); } }
                if (role.equals("customer")) { assertEquals(Long.parseLong(result.get("total-bookings")), List.of("pending-bookings", "confirmed-bookings", "expired-bookings", "cancelled-bookings").stream().mapToLong(key -> Long.parseLong(result.get(key))).sum()); }
                else { result.put("net-revenue", new BigDecimal(result.get("gross-payments")).subtract(new BigDecimal(result.get("refunded-payments"))).toPlainString()); }
                return result;
            });
        }

        private static void assertSales(Map<String, String> metrics, long tickets, String gross, String refunded) {
            assertEquals(Long.toString(tickets), metrics.get("tickets-sold")); assertEquals(gross, metrics.get("gross-payments")); assertEquals(refunded, metrics.get("refunded-payments")); assertEquals(new BigDecimal(gross).subtract(new BigDecimal(refunded)).toPlainString(), metrics.get("net-revenue"));
        }

        void finalHistoryAndOwnership(long first, long second) throws Exception {
            var before = snapshot(); HttpBrowser a = login(firstEmail, "customer"), b = login(secondEmail, "customer");
            String firstCode = code(first), secondCode = code(second);
            var history = a.get("customer/bookings?status=CANCELLED&size=1"); status(200, history); assertTrue(history.body().contains(firstCode)); assertFalse(history.body().contains(secondCode));
            var old = a.get(detail(first).replace("paymentPage=1", "paymentPage=2")); status(200, old); assertTrue(old.body().contains("FAILED"));
            var denied = b.get(detail(first)); status(404, denied); assertFalse(denied.body().contains(firstCode));
            denied = a.get(detail(second)); status(404, denied); assertFalse(denied.body().contains(secondCode));
            status(200, b.get("customer/bookings?status=CONFIRMED&size=1")); status(200, a.head(detail(first))); status(200, b.head(detail(second)));
            status(404, login(otherOrganizerEmail, "organizer").get("organizer/events/inventory?eventId=" + target));
            status(403, login(f.email(f.admin), "admin").get(detail(first)));
            status(200, new HttpBrowser().get("events/seats?eventId=" + target));
            assertEquals(before, snapshot(), "Read/ownership checks must preserve every table.");
        }

        void assertFinalGraph() {
            assertEquals(2, id("SELECT COUNT_BIG(*) FROM dbo.bookings WHERE event_id=?", target));
            assertEquals(2, id("SELECT COUNT_BIG(*) FROM dbo.bookings WHERE event_id=? AND DATEDIFF_BIG(MILLISECOND,created_at,expires_at)=300000", target));
            long first = id("SELECT id FROM dbo.bookings WHERE event_id=? AND customer_id=?", target, firstCustomer);
            long second = id("SELECT id FROM dbo.bookings WHERE event_id=? AND customer_id=?", target, secondCustomer);
            assertBooking(first, firstCustomer, "CANCELLED", 2, 1); assertBooking(second, secondCustomer, "CONFIRMED", 1, 1);
            assertSales(metrics("organizer", organizer), 2, "600000.00", "300000.00");
        }

        private String code(long booking) { return f.access.read(c -> { try (var s = statement(c, "SELECT booking_code FROM dbo.bookings WHERE id=? AND event_id=?", booking, target); var r = s.executeQuery()) { assertTrue(r.next()); return r.getString(1); } }); }
        long id(String sql, Object... parameters) { return f.access.read(c -> scalar(c, sql, parameters)); }
        private List<String> ids(String sql, Object... parameters) { return f.access.read(c -> { List<String> result = new ArrayList<>(); try (var s = statement(c, sql, parameters); var r = s.executeQuery()) { while (r.next()) { result.add(r.getString(1)); } } return List.copyOf(result); }); }

        Map<String, String> snapshot() {
            return f.access.read(c -> {
                Map<String, String> result = new LinkedHashMap<>();
                for (String table : List.of("users", "categories", "venues", "seats", "events", "bookings", "event_seats", "booking_items", "payments")) {
                    try (var s = statement(c, "SELECT COUNT_BIG(*),CONVERT(varchar(64),HASHBYTES('SHA2_256',COALESCE((SELECT * FROM dbo." + table + " ORDER BY id FOR JSON PATH,INCLUDE_NULL_VALUES),N'[]')),2) FROM dbo." + table); var r = s.executeQuery()) { assertTrue(r.next()); assertTrue(r.getString(2).matches("[0-9A-F]{64}")); result.put(table, r.getLong(1) + ":" + r.getString(2)); }
                }
                return result;
            });
        }

        @Override public void close() throws Exception {
            try {
                // Recover exact registered names even if a successful POST was followed by an assertion failure.
                collect(f.categories, "SELECT id FROM dbo.categories WHERE name=?", categoryName);
                collect(f.venues, "SELECT id FROM dbo.venues WHERE name=?", venueName);
                for (String title : titles) { collect(f.events, "SELECT e.id FROM dbo.events e JOIN dbo.users u ON u.id=e.organizer_id WHERE u.email=? AND e.title=?", organizerEmail, title); }
                for (long ownedVenue : f.venues) { for (int number = 1; number <= 12; number++) { collect(f.seats, "SELECT id FROM dbo.seats WHERE venue_id=? AND row_label='R' AND seat_number=?", ownedVenue, number); } }
                f.access.write(c -> {
                    for (long event : f.events) {
                        for (String sql : List.of("UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE event_id=?", "DELETE p FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.event_id=?", "DELETE dbo.booking_items WHERE event_id=?", "DELETE dbo.bookings WHERE event_id=?")) { try (var s = statement(c, sql, event)) { s.executeUpdate(); } }
                    }
                    return null;
                });
            } finally {
                try { f.close(); }
                catch (SQLException failure) {
                    throw new AssertionError("Owned journey cleanup failed; SQLState=" + failure.getSQLState() + ", vendorCode=" + failure.getErrorCode());
                }
            }
        }

        private void collect(List<Long> destination, String sql, Object... parameters) { for (String value : ids(sql, parameters)) { long id = Long.parseLong(value); if (!destination.contains(id)) { destination.add(id); } } }
    }
}
