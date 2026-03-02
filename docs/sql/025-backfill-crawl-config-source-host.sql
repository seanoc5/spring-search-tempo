-- 025: Backfill crawl_config.source_host for legacy rows created before host stamping.
-- Prefer target_host when present; otherwise mark as legacy-local.

UPDATE crawl_config
SET source_host = COALESCE(target_host, 'legacy-local')
WHERE source_host IS NULL;
