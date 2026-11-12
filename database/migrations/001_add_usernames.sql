-- Add optional usernames without changing existing account or booking data.
-- Select the existing application database. Safe to repeat after successful application.
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
IF OBJECT_ID(N'dbo.users', N'U') IS NULL
    THROW 51003, 'The application users table is required before this migration.', 1;

BEGIN TRY
    BEGIN TRANSACTION;
    DECLARE @lock_result INT;
    EXEC @lock_result = sys.sp_getapplock @Resource=N'EventBookingUsernameMigration',
        @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=5000;
    IF @lock_result < 0
        THROW 51004, 'Username migration lock unavailable; retry later.', 1;

    IF COL_LENGTH(N'dbo.users', N'username') IS NULL
        EXEC(N'ALTER TABLE dbo.users ADD username NVARCHAR(30) COLLATE Latin1_General_100_BIN2 NULL;');

    IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.users')
        AND name=N'username' AND system_type_id=231 AND max_length=60
        AND is_nullable=1 AND is_computed=0 AND collation_name=N'Latin1_General_100_BIN2')
        THROW 51003, 'The existing username column is incompatible; no data was changed.', 1;

    -- Defer binding until after the new column exists in this same batch.
    -- Reassert the complete check, including on a repeat; never normalize existing data.
    IF EXISTS (SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id=OBJECT_ID(N'dbo.users') AND name=N'CK_users_username')
        EXEC(N'ALTER TABLE dbo.users DROP CONSTRAINT CK_users_username;');
    EXEC(N'ALTER TABLE dbo.users WITH CHECK ADD CONSTRAINT CK_users_username
        CHECK (username IS NULL OR (DATALENGTH(username) BETWEEN 6 AND 60
            AND username NOT LIKE N''%[^-a-z0-9._]%''));');

    IF NOT EXISTS (SELECT 1 FROM sys.indexes
        WHERE object_id=OBJECT_ID(N'dbo.users') AND name=N'UX_users_username')
        EXEC(N'CREATE UNIQUE INDEX UX_users_username ON dbo.users(username) WHERE username IS NOT NULL;');
    IF NOT EXISTS (SELECT 1 FROM sys.indexes i WHERE i.object_id=OBJECT_ID(N'dbo.users')
        AND i.name=N'UX_users_username' AND i.is_unique=1 AND i.is_disabled=0 AND i.has_filter=1
        AND REPLACE(REPLACE(REPLACE(REPLACE(i.filter_definition,N'[',N''),N']',N''),N'(',N''),N')',N'')=N'username IS NOT NULL'
        AND (SELECT COUNT(*) FROM sys.index_columns k WHERE k.object_id=i.object_id
            AND k.index_id=i.index_id AND k.key_ordinal>0)=1
        AND EXISTS (SELECT 1 FROM sys.index_columns k JOIN sys.columns c
            ON c.object_id=k.object_id AND c.column_id=k.column_id
            WHERE k.object_id=i.object_id AND k.index_id=i.index_id
                AND k.key_ordinal=1 AND c.name=N'username'))
        THROW 51003, 'The existing username index is incompatible; no data was changed.', 1;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
