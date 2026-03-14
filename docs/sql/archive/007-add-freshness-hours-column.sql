-- Add freshnessHours column to crawl_config table
-- This controls the "recent crawl" skip threshold for overlapping crawls

ALTER TABLE crawl_config
ADD COLUMN IF NOT EXISTS freshness_hours INTEGER;

COMMENT ON COLUMN crawl_config.freshness_hours IS 'Hours threshold for recent crawl skip logic. NULL = use system default (24 hours).';
