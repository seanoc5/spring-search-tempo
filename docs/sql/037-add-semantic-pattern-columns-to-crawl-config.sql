-- 037: Add SEMANTIC pattern columns to crawl_config.
-- Enables distinct EMBED (SEMANTIC) processing level from Discovery apply.

ALTER TABLE crawl_config
    ADD COLUMN IF NOT EXISTS folder_patterns_semantic TEXT;

ALTER TABLE crawl_config
    ADD COLUMN IF NOT EXISTS file_patterns_semantic TEXT;
