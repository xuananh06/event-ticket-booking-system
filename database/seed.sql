-- Public demo accounts only; never run against a populated database.
-- Password: DemoOnly!ChangeMe; bcrypt $2a$, cost 12, independent random salts.
SET NOCOUNT ON;
SET XACT_ABORT ON;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;

IF DB_NAME() IN (N'master', N'model', N'msdb', N'tempdb')
    THROW 51000, 'Select a dedicated application database.', 1;

BEGIN TRY
    BEGIN TRANSACTION;
    IF EXISTS (SELECT 1 FROM dbo.users) OR EXISTS (SELECT 1 FROM dbo.categories)
        OR EXISTS (SELECT 1 FROM dbo.venues) OR EXISTS (SELECT 1 FROM dbo.seats)
        OR EXISTS (SELECT 1 FROM dbo.events) OR EXISTS (SELECT 1 FROM dbo.bookings)
        OR EXISTS (SELECT 1 FROM dbo.event_seats) OR EXISTS (SELECT 1 FROM dbo.booking_items)
        OR EXISTS (SELECT 1 FROM dbo.payments)
        THROW 51002, 'Seed requires empty application tables; no existing rows were changed.', 1;

    INSERT dbo.users (email, password_hash, full_name, role) VALUES
        (N'admin@example.test', '$2a$12$8sLxuN1aT.wm806ikXm4iufWpbJ0kqT38XeqMH54GxaOl0aCLE2wu', N'Demo Admin', 'ADMIN'),
        (N'organizer@example.test', '$2a$12$iIT9r.59GXH7qc6CL9dDaOSlOMEeK2slLSSFYB.2k8HZTicE0DvMm', N'Demo Organizer', 'ORGANIZER'),
        (N'customer@example.test', '$2a$12$jcyl9.1vEgH1cH6GkDHRxOrm44P8jZbLrHziGlB1YyYU57GoSqGpq', N'Demo Customer', 'CUSTOMER');

    INSERT dbo.categories (name) VALUES (N'Music'), (N'Workshops');
    INSERT dbo.venues (name, address) VALUES (N'University Auditorium', N'University campus, Ho Chi Minh City');
    DECLARE @venue BIGINT = CONVERT(BIGINT, SCOPE_IDENTITY());
    INSERT dbo.seats (venue_id, row_label, seat_number)
        SELECT @venue, layout.row_label, layout.seat_number
        FROM (VALUES (N'A',1),(N'A',2),(N'A',3),(N'A',4),(N'B',1),(N'B',2),(N'B',3),(N'B',4)) layout(row_label,seat_number);

    DECLARE @organizer BIGINT = (SELECT id FROM dbo.users WHERE email = N'organizer@example.test');
    DECLARE @music BIGINT = (SELECT id FROM dbo.categories WHERE name = N'Music');
    DECLARE @seminar BIGINT = (SELECT id FROM dbo.categories WHERE name = N'Workshops');
    DECLARE @now DATETIME2(3) = SYSUTCDATETIME();
    INSERT dbo.events (organizer_id, category_id, venue_id, title, description, starts_at, ends_at, status)
    VALUES (@organizer, @music, @venue, N'Student Music Night', N'A live music program for university students.',
            DATEADD(DAY, 14, @now), DATEADD(HOUR, 2, DATEADD(DAY, 14, @now)), 'PUBLISHED'),
           (@organizer, @seminar, @venue, N'Technology Workshop', N'A workshop on Java programming.',
            DATEADD(DAY, 21, @now), DATEADD(HOUR, 3, DATEADD(DAY, 21, @now)), 'DRAFT');

    INSERT dbo.event_seats (event_id, seat_id, venue_id, price)
        SELECT e.id, s.id, e.venue_id, CASE WHEN e.category_id = @music THEN 150000.00 ELSE 50000.00 END
        FROM dbo.events e JOIN dbo.seats s ON s.venue_id = e.venue_id;
    -- Booking, item and payment history intentionally start empty in P1.
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
