-- SQL Server 2022. Select a dedicated, empty user database before execution.
-- One batch, no GO; fail on rerun without changing existing tables.
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
IF EXISTS (SELECT 1 FROM sys.tables WHERE schema_id = SCHEMA_ID(N'dbo')
    AND name IN (N'users', N'categories', N'venues', N'seats', N'events', N'bookings',
                 N'event_seats', N'booking_items', N'payments'))
    THROW 51001, 'Application tables already exist; schema rerun refused.', 1;

BEGIN TRY
    BEGIN TRANSACTION;

    CREATE TABLE dbo.users (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_users PRIMARY KEY,
        email NVARCHAR(254) COLLATE Latin1_General_100_CI_AS_SC NOT NULL,
        password_hash VARCHAR(255) COLLATE Latin1_General_100_BIN2 NOT NULL,
        full_name NVARCHAR(150) NOT NULL,
        role VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
        is_active BIT NOT NULL CONSTRAINT DF_users_active DEFAULT 1,
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_users_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_users_email UNIQUE (email),
        CONSTRAINT CK_users_email_normalized CHECK (
            email COLLATE Latin1_General_100_BIN2 = LOWER(LTRIM(RTRIM(email))) COLLATE Latin1_General_100_BIN2
            AND DATALENGTH(email) = DATALENGTH(LTRIM(RTRIM(email)))
            AND email LIKE N'%_@_%._%'),
        CONSTRAINT CK_users_password_hash CHECK (DATALENGTH(password_hash) = 60
            AND LEFT(password_hash, 7) = '$2a$12$'
            AND SUBSTRING(password_hash, 8, 53) NOT LIKE '%[^./A-Za-z0-9]%'),
        CONSTRAINT CK_users_name CHECK (LEN(LTRIM(RTRIM(full_name))) > 0),
        CONSTRAINT CK_users_role CHECK (role IN ('CUSTOMER', 'ORGANIZER', 'ADMIN') AND DATALENGTH(role) = LEN(role))
    );

    CREATE TABLE dbo.categories (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_categories PRIMARY KEY,
        name NVARCHAR(100) NOT NULL,
        is_active BIT NOT NULL CONSTRAINT DF_categories_active DEFAULT 1,
        CONSTRAINT UQ_categories_name UNIQUE (name),
        CONSTRAINT CK_categories_name CHECK (LEN(LTRIM(RTRIM(name))) > 0)
    );

    CREATE TABLE dbo.venues (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_venues PRIMARY KEY,
        name NVARCHAR(150) NOT NULL,
        address NVARCHAR(300) NOT NULL,
        is_active BIT NOT NULL CONSTRAINT DF_venues_active DEFAULT 1,
        CONSTRAINT CK_venues_name CHECK (LEN(LTRIM(RTRIM(name))) > 0),
        CONSTRAINT CK_venues_address CHECK (LEN(LTRIM(RTRIM(address))) > 0)
    );

    CREATE TABLE dbo.seats (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_seats PRIMARY KEY,
        venue_id BIGINT NOT NULL,
        row_label NVARCHAR(10) NOT NULL,
        seat_number INT NOT NULL,
        is_active BIT NOT NULL CONSTRAINT DF_seats_active DEFAULT 1,
        CONSTRAINT FK_seats_venue FOREIGN KEY (venue_id) REFERENCES dbo.venues(id),
        CONSTRAINT UQ_seats_layout UNIQUE (venue_id, row_label, seat_number),
        CONSTRAINT UQ_seats_id_venue UNIQUE (id, venue_id),
        CONSTRAINT CK_seats_number CHECK (seat_number > 0),
        CONSTRAINT CK_seats_row CHECK (LEN(LTRIM(RTRIM(row_label))) > 0)
    );

    CREATE TABLE dbo.events (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_events PRIMARY KEY,
        organizer_id BIGINT NOT NULL,
        category_id BIGINT NOT NULL,
        venue_id BIGINT NOT NULL,
        title NVARCHAR(200) NOT NULL,
        description NVARCHAR(MAX) NOT NULL,
        starts_at DATETIME2(3) NOT NULL,
        ends_at DATETIME2(3) NOT NULL,
        status VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL CONSTRAINT DF_events_status DEFAULT 'DRAFT',
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_events_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT FK_events_organizer FOREIGN KEY (organizer_id) REFERENCES dbo.users(id),
        CONSTRAINT FK_events_category FOREIGN KEY (category_id) REFERENCES dbo.categories(id),
        CONSTRAINT FK_events_venue FOREIGN KEY (venue_id) REFERENCES dbo.venues(id),
        CONSTRAINT UQ_events_id_venue UNIQUE (id, venue_id),
        CONSTRAINT CK_events_time CHECK (ends_at > starts_at),
        CONSTRAINT CK_events_title CHECK (LEN(LTRIM(RTRIM(title))) > 0),
        CONSTRAINT CK_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED') AND DATALENGTH(status) = LEN(status))
    );

    CREATE TABLE dbo.bookings (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_bookings PRIMARY KEY,
        booking_code VARCHAR(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
        customer_id BIGINT NOT NULL,
        event_id BIGINT NOT NULL,
        status VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL CONSTRAINT DF_bookings_status DEFAULT 'PENDING',
        total_amount DECIMAL(18,2) NOT NULL,
        expires_at DATETIME2(3) NOT NULL,
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_bookings_created DEFAULT SYSUTCDATETIME(),
        confirmed_at DATETIME2(3) NULL,
        cancelled_at DATETIME2(3) NULL,
        CONSTRAINT UQ_bookings_code UNIQUE (booking_code),
        CONSTRAINT UQ_bookings_id_event UNIQUE (id, event_id),
        CONSTRAINT FK_bookings_customer FOREIGN KEY (customer_id) REFERENCES dbo.users(id),
        CONSTRAINT FK_bookings_event FOREIGN KEY (event_id) REFERENCES dbo.events(id),
        CONSTRAINT CK_bookings_code CHECK (LEN(LTRIM(RTRIM(booking_code))) > 0),
        CONSTRAINT CK_bookings_total CHECK (total_amount >= 0),
        CONSTRAINT CK_bookings_expiry CHECK (expires_at > created_at AND expires_at <= DATEADD(MINUTE, 5, created_at)),
        CONSTRAINT CK_bookings_status CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED') AND DATALENGTH(status) = LEN(status)),
        CONSTRAINT CK_bookings_shape CHECK (
            (status IN ('PENDING', 'EXPIRED') AND confirmed_at IS NULL AND cancelled_at IS NULL)
            OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND cancelled_at IS NULL)
            OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)),
        CONSTRAINT CK_bookings_confirmation_time CHECK (confirmed_at IS NULL OR confirmed_at >= created_at),
        CONSTRAINT CK_bookings_cancellation_time CHECK (cancelled_at IS NULL OR
            (cancelled_at >= created_at AND (confirmed_at IS NULL OR cancelled_at >= confirmed_at)))
    );

    CREATE TABLE dbo.event_seats (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_event_seats PRIMARY KEY,
        event_id BIGINT NOT NULL,
        seat_id BIGINT NOT NULL,
        venue_id BIGINT NOT NULL,
        price DECIMAL(18,2) NOT NULL,
        state VARCHAR(12) COLLATE Latin1_General_100_BIN2 NOT NULL CONSTRAINT DF_event_seats_state DEFAULT 'AVAILABLE',
        current_booking_id BIGINT NULL,
        hold_expires_at DATETIME2(3) NULL,
        CONSTRAINT UQ_event_seats_event_seat UNIQUE (event_id, seat_id),
        CONSTRAINT UQ_event_seats_id_event UNIQUE (id, event_id),
        CONSTRAINT FK_event_seats_event_venue FOREIGN KEY (event_id, venue_id) REFERENCES dbo.events(id, venue_id),
        CONSTRAINT FK_event_seats_seat_venue FOREIGN KEY (seat_id, venue_id) REFERENCES dbo.seats(id, venue_id),
        CONSTRAINT FK_event_seats_current_booking FOREIGN KEY (current_booking_id, event_id) REFERENCES dbo.bookings(id, event_id),
        CONSTRAINT CK_event_seats_price CHECK (price >= 0),
        CONSTRAINT CK_event_seats_state CHECK (state IN ('AVAILABLE', 'HELD', 'SOLD') AND DATALENGTH(state) = LEN(state)),
        CONSTRAINT CK_event_seats_shape CHECK (
            (state = 'AVAILABLE' AND current_booking_id IS NULL AND hold_expires_at IS NULL)
            OR (state = 'HELD' AND current_booking_id IS NOT NULL AND hold_expires_at IS NOT NULL)
            OR (state = 'SOLD' AND current_booking_id IS NOT NULL AND hold_expires_at IS NULL))
    );

    CREATE TABLE dbo.booking_items (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_booking_items PRIMARY KEY,
        booking_id BIGINT NOT NULL,
        event_seat_id BIGINT NOT NULL,
        event_id BIGINT NOT NULL,
        unit_price DECIMAL(18,2) NOT NULL,
        seat_label_snapshot NVARCHAR(30) NOT NULL,
        CONSTRAINT UQ_booking_items_booking_seat UNIQUE (booking_id, event_seat_id),
        CONSTRAINT FK_booking_items_booking_event FOREIGN KEY (booking_id, event_id) REFERENCES dbo.bookings(id, event_id),
        CONSTRAINT FK_booking_items_inventory_event FOREIGN KEY (event_seat_id, event_id) REFERENCES dbo.event_seats(id, event_id),
        CONSTRAINT CK_booking_items_price CHECK (unit_price >= 0),
        CONSTRAINT CK_booking_items_label CHECK (LEN(LTRIM(RTRIM(seat_label_snapshot))) > 0)
    );

    CREATE TABLE dbo.payments (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_payments PRIMARY KEY,
        booking_id BIGINT NOT NULL,
        idempotency_key VARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL,
        request_fingerprint CHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
        amount DECIMAL(18,2) NOT NULL,
        status VARCHAR(12) COLLATE Latin1_General_100_BIN2 NOT NULL,
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_payments_created DEFAULT SYSUTCDATETIME(),
        succeeded_at DATETIME2(3) NULL,
        refunded_at DATETIME2(3) NULL,
        CONSTRAINT FK_payments_booking FOREIGN KEY (booking_id) REFERENCES dbo.bookings(id),
        CONSTRAINT UQ_payments_booking_key UNIQUE (booking_id, idempotency_key),
        CONSTRAINT CK_payments_key CHECK (LEN(LTRIM(RTRIM(idempotency_key))) > 0),
        CONSTRAINT CK_payments_fingerprint CHECK (request_fingerprint NOT LIKE '%[^0-9a-f]%' AND LEN(request_fingerprint) = 64),
        CONSTRAINT CK_payments_amount CHECK (amount >= 0),
        CONSTRAINT CK_payments_status CHECK (status IN ('FAILED', 'SUCCEEDED', 'REFUNDED') AND DATALENGTH(status) = LEN(status)),
        CONSTRAINT CK_payments_shape CHECK (
            (status = 'FAILED' AND succeeded_at IS NULL AND refunded_at IS NULL)
            OR (status = 'SUCCEEDED' AND succeeded_at IS NOT NULL AND refunded_at IS NULL)
            OR (status = 'REFUNDED' AND succeeded_at IS NOT NULL AND refunded_at IS NOT NULL)),
        CONSTRAINT CK_payments_success_time CHECK (succeeded_at IS NULL OR succeeded_at >= created_at),
        CONSTRAINT CK_payments_refund_time CHECK (refunded_at IS NULL OR refunded_at >= succeeded_at)
    );

    CREATE UNIQUE INDEX UX_payments_one_success ON dbo.payments(booking_id) WHERE succeeded_at IS NOT NULL;
    CREATE INDEX IX_events_status_start ON dbo.events(status, starts_at, id) INCLUDE (category_id, venue_id, title);
    CREATE INDEX IX_events_category_status_start ON dbo.events(category_id, status, starts_at, id);
    CREATE INDEX IX_events_organizer ON dbo.events(organizer_id, status, starts_at);
    CREATE INDEX IX_events_venue ON dbo.events(venue_id);
    CREATE INDEX IX_bookings_customer_history ON dbo.bookings(customer_id, created_at DESC, id DESC) INCLUDE (event_id, status, total_amount);
    CREATE INDEX IX_bookings_event_status ON dbo.bookings(event_id, status, expires_at);
    CREATE INDEX IX_event_seats_availability ON dbo.event_seats(event_id, state, hold_expires_at, id) INCLUDE (price, current_booking_id);
    CREATE INDEX IX_event_seats_seat_venue ON dbo.event_seats(seat_id, venue_id);
    CREATE INDEX IX_event_seats_current_booking ON dbo.event_seats(current_booking_id, event_id);
    CREATE INDEX IX_booking_items_inventory ON dbo.booking_items(event_seat_id, event_id);
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
