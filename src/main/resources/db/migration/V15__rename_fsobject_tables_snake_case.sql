-- V15: Issue #142 — Rename FSObject hierarchy tables to snake_case.
--
-- Brings `fsfile` / `fsfolder` into line with the project's snake_case
-- table-naming convention (`content_chunks`, `email_account`, `crawl_config`,
-- `folder_audit_run`, etc.). Spring Boot's default
-- CamelCaseToUnderscoresNamingStrategy collapses `FSFile` → `fsfile`
-- (consecutive caps stay glued, no underscore between `S` and `F`). The
-- companion fix is an explicit `@Table(name = "fs_file")` / `"fs_folder"`
-- on the entities; this migration handles existing databases.
--
-- Idempotent: every statement is guarded with an EXISTS check, so this
-- migration is a no-op on fresh databases where Hibernate ddl-auto already
-- created the tables under the new name.
--
-- Functions and the search_stats materialized view (defined in
-- docs/sql/essential-postgres-features.sql) hard-code the old table names
-- in their bodies. We drop them here so the next run of
-- essential-postgres-features.sql recreates them against the renamed
-- tables. The reset script always reapplies essential after Flyway.

-- ---------------------------------------------------------------------------
-- 1. Tables
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'fsfile'
    ) THEN
        ALTER TABLE fsfile RENAME TO fs_file;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'fsfolder'
    ) THEN
        ALTER TABLE fsfolder RENAME TO fs_folder;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Indexes — Postgres preserves index ownership across a table rename,
--    but the index names still embed the legacy table token. Rename for
--    consistency so DBA tooling, EXPLAIN output, and DROP INDEX scripts
--    stay legible.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    rename_pairs TEXT[][] := ARRAY[
        ['idx_fsfile_parent_archive_uri', 'idx_fs_file_parent_archive_uri'],
        ['idx_fsfile_metadata_dup',       'idx_fs_file_metadata_dup'],
        ['idx_fsfile_content_hash',       'idx_fs_file_content_hash'],
        ['idx_fsfile_fts',                'idx_fs_file_fts'],
        ['idx_fsfile_title_trgm',         'idx_fs_file_title_trgm'],
        ['idx_fsfile_label_trgm',         'idx_fs_file_label_trgm'],
        ['idx_fsfile_author_trgm',        'idx_fs_file_author_trgm'],
        ['idx_fsfile_keywords_trgm',      'idx_fs_file_keywords_trgm'],
        ['idx_fsfile_job_run_id',         'idx_fs_file_job_run_id'],
        ['idx_fsfolder_job_run_id',       'idx_fs_folder_job_run_id']
    ];
    idx_old TEXT;
    idx_new TEXT;
    i INT;
BEGIN
    FOR i IN 1 .. array_length(rename_pairs, 1) LOOP
        idx_old := rename_pairs[i][1];
        idx_new := rename_pairs[i][2];
        IF EXISTS (
            SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = idx_old
        ) THEN
            EXECUTE format('ALTER INDEX %I RENAME TO %I', idx_old, idx_new);
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 3. Check constraints (defined in essential-postgres-features.sql with the
--    old table-name prefix; rename for consistency).
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fsfile_analysis_status_check'
    ) THEN
        ALTER TABLE fs_file
            RENAME CONSTRAINT fsfile_analysis_status_check
                TO fs_file_analysis_status_check;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fsfolder_analysis_status_check'
    ) THEN
        ALTER TABLE fs_folder
            RENAME CONSTRAINT fsfolder_analysis_status_check
                TO fs_folder_analysis_status_check;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Drop functions and the materialized view that hard-coded the legacy
--    table names. The reset script re-runs essential-postgres-features.sql
--    after migrations, which recreates them with the new names. Operators
--    upgrading an existing DB must re-run essential-postgres-features.sql
--    after this migration applies.
-- ---------------------------------------------------------------------------

DROP MATERIALIZED VIEW IF EXISTS search_stats CASCADE;
DROP FUNCTION IF EXISTS search_full_text(TEXT, INTEGER, INTEGER);
DROP FUNCTION IF EXISTS search_chunks_with_sentiment(TEXT, TEXT, INTEGER, INTEGER);
DROP FUNCTION IF EXISTS search_suggest(TEXT, INTEGER);
DROP FUNCTION IF EXISTS refresh_search_stats();
