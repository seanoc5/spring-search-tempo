-- 023: Add target_host column to crawl_config
-- Allows scoping crawl configs to specific machines.
-- NULL means "runs on any host."

ALTER TABLE crawl_config ADD COLUMN IF NOT EXISTS target_host VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_crawl_config_target_host ON crawl_config (target_host);
