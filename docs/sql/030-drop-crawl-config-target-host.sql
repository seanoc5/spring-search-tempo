-- 030: Remove deprecated target_host assignment field from crawl_config.
-- Host provenance is tracked via source_host; routing no longer uses target_host.

DROP INDEX IF EXISTS idx_crawl_config_target_host;
ALTER TABLE crawl_config DROP COLUMN IF EXISTS target_host;
