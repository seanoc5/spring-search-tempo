-- 042: Add pattern stability tracking and file sample analysis fields.
--
-- Integration 1: Pattern stability score on FSFolder
--   - Tracks how stable classification patterns are for a folder
--   - 0 = unstable (crawl more often), 100 = stable (can cool to COLD)
--   - Fed from discovery observation reapply statistics
--
-- Integration 2: Detected folder type from file sample analysis
--   - Analyzes file samples to detect folder type (SOURCE_CODE, MEDIA, etc.)
--   - Used to improve classification suggestions

-- Add pattern_stability_score to fsfolder
DO $$
DECLARE
    target_table TEXT;
BEGIN
    IF to_regclass('public.fsfolder') IS NOT NULL THEN
        target_table := 'fsfolder';
    ELSIF to_regclass('public.fs_folder') IS NOT NULL THEN
        target_table := 'fs_folder';
    ELSE
        RAISE NOTICE 'Skipping pattern stability: neither fsfolder nor fs_folder exists';
        RETURN;
    END IF;

    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS pattern_stability_score INT NOT NULL DEFAULT 50', target_table);

    -- Index for bulk updates by source_host
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_source_host_stability ON %I (source_host, pattern_stability_score)', target_table, target_table);
END $$;

-- Add folder type analysis fields to discovery observations
ALTER TABLE crawl_discovery_folder_obs
    ADD COLUMN IF NOT EXISTS detected_folder_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS detection_confidence DECIMAL(4,3);

-- Constraint for valid folder types
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'crawl_discovery_folder_obs_type_check'
    ) THEN
        ALTER TABLE crawl_discovery_folder_obs
            ADD CONSTRAINT crawl_discovery_folder_obs_type_check
            CHECK (detected_folder_type IS NULL OR detected_folder_type IN (
                'SOURCE_CODE', 'DOCUMENTATION', 'OFFICE_DOCS', 'MEDIA',
                'DATA', 'CONFIG', 'BUILD_ARTIFACTS', 'MIXED', 'UNKNOWN'
            ));
    END IF;
END $$;

-- Index for querying by detected type
CREATE INDEX IF NOT EXISTS idx_discovery_obs_folder_type
    ON crawl_discovery_folder_obs (crawl_config_id, host, detected_folder_type)
    WHERE detected_folder_type IS NOT NULL;
