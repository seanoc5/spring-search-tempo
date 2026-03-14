-- 043: Add direct crawl_config ownership to filesystem rows.
--
-- This decouples stable CrawlConfig ownership from job_run metadata so
-- discovery can seed FSFolder placeholders before the first real crawl.
-- job_run_id remains the "last touched by run" reference.

DO $$
DECLARE
    folder_table TEXT;
    file_table TEXT;
BEGIN
    IF to_regclass('public.fsfolder') IS NOT NULL THEN
        folder_table := 'fsfolder';
    ELSIF to_regclass('public.fs_folder') IS NOT NULL THEN
        folder_table := 'fs_folder';
    END IF;

    IF to_regclass('public.fsfile') IS NOT NULL THEN
        file_table := 'fsfile';
    ELSIF to_regclass('public.fs_file') IS NOT NULL THEN
        file_table := 'fs_file';
    END IF;

    IF folder_table IS NULL THEN
        RAISE NOTICE 'Skipping FSFolder crawl_config_id migration: no folder table found';
    ELSE
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS crawl_config_id BIGINT', folder_table);
        EXECUTE format(
            'UPDATE %I f
             SET crawl_config_id = jr.crawl_config_id
             FROM job_run jr
             WHERE f.job_run_id = jr.id
               AND f.crawl_config_id IS NULL',
            folder_table
        );
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%I_crawl_config_id ON %I (crawl_config_id)',
            folder_table,
            folder_table
        );
    END IF;

    IF file_table IS NULL THEN
        RAISE NOTICE 'Skipping FSFile crawl_config_id migration: no file table found';
    ELSE
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS crawl_config_id BIGINT', file_table);
        EXECUTE format(
            'UPDATE %I f
             SET crawl_config_id = jr.crawl_config_id
             FROM job_run jr
             WHERE f.job_run_id = jr.id
               AND f.crawl_config_id IS NULL',
            file_table
        );
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%I_crawl_config_id ON %I (crawl_config_id)',
            file_table,
            file_table
        );
    END IF;
END $$;
