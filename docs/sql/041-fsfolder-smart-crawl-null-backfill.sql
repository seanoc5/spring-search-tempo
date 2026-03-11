-- 041: Backfill smart-crawl defaults on FS folder table.
-- Fixes legacy rows where change_score / crawl_temperature are NULL,
-- which can fail Kotlin non-null entity hydration.

DO $$
DECLARE
    target_table TEXT;
BEGIN
    -- Support both naming variants seen in this codebase.
    IF to_regclass('public.fsfolder') IS NOT NULL THEN
        target_table := 'fsfolder';
    ELSIF to_regclass('public.fs_folder') IS NOT NULL THEN
        target_table := 'fs_folder';
    ELSE
        RAISE NOTICE 'Skipping smart-crawl backfill: neither fsfolder nor fs_folder exists';
        RETURN;
    END IF;

    -- Ensure columns exist for older schemas.
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS crawl_temperature VARCHAR(20)', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS change_score INTEGER', target_table);

    -- Backfill existing NULL values.
    EXECUTE format('UPDATE %I SET crawl_temperature = ''WARM'' WHERE crawl_temperature IS NULL', target_table);
    EXECUTE format('UPDATE %I SET change_score = 0 WHERE change_score IS NULL', target_table);

    -- Enforce defaults + non-null for future rows.
    EXECUTE format('ALTER TABLE %I ALTER COLUMN crawl_temperature SET DEFAULT ''WARM''', target_table);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN crawl_temperature SET NOT NULL', target_table);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN change_score SET DEFAULT 0', target_table);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN change_score SET NOT NULL', target_table);
END $$;

