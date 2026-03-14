-- 031: Add baseline manifest fields on fs_folder for CrawlConfig validation UI.
-- Purpose: store capped sample file metadata ("starter test data") for per-folder diffing.

DO $$
DECLARE
    target_table TEXT;
BEGIN
    -- Support both naming variants seen in this codebase across migrations.
    IF to_regclass('public.fs_folder') IS NOT NULL THEN
        target_table := 'fs_folder';
    ELSIF to_regclass('public.fsfolder') IS NOT NULL THEN
        target_table := 'fsfolder';
    ELSE
        RAISE NOTICE 'Skipping baseline manifest migration: neither fs_folder nor fsfolder exists';
        RETURN;
    END IF;

    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_manifest JSONB', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_captured_at TIMESTAMPTZ', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_source_job_run_id BIGINT', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_total_files INTEGER', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_sample_files INTEGER', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_sampling_policy VARCHAR(64)', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_seed VARCHAR(64)', target_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS baseline_version INTEGER NOT NULL DEFAULT 1', target_table);

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_fsfolder_baseline_captured_at ON %I (baseline_captured_at)',
        target_table
    );
END $$;
