-- 039: Ensure crawl_config.smart_crawl_enabled is always non-null with default false.
-- Safe for existing databases where the column may have been created nullable by dev ddl-auto updates.

ALTER TABLE crawl_config
    ADD COLUMN IF NOT EXISTS smart_crawl_enabled BOOLEAN;

UPDATE crawl_config
SET smart_crawl_enabled = FALSE
WHERE smart_crawl_enabled IS NULL;

ALTER TABLE crawl_config
    ALTER COLUMN smart_crawl_enabled SET DEFAULT FALSE;

ALTER TABLE crawl_config
    ALTER COLUMN smart_crawl_enabled SET NOT NULL;
