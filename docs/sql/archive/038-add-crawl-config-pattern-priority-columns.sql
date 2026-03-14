-- 038: Add explicit pattern-priority columns for crawl_config.
-- Higher value = higher precedence during pattern matching.

ALTER TABLE crawl_config
    ADD COLUMN IF NOT EXISTS folder_priority_skip integer NOT NULL DEFAULT 500,
    ADD COLUMN IF NOT EXISTS folder_priority_semantic integer NOT NULL DEFAULT 400,
    ADD COLUMN IF NOT EXISTS folder_priority_analyze integer NOT NULL DEFAULT 300,
    ADD COLUMN IF NOT EXISTS folder_priority_index integer NOT NULL DEFAULT 200,
    ADD COLUMN IF NOT EXISTS folder_priority_locate integer NOT NULL DEFAULT 100,
    ADD COLUMN IF NOT EXISTS file_priority_skip integer NOT NULL DEFAULT 500,
    ADD COLUMN IF NOT EXISTS file_priority_semantic integer NOT NULL DEFAULT 400,
    ADD COLUMN IF NOT EXISTS file_priority_analyze integer NOT NULL DEFAULT 300,
    ADD COLUMN IF NOT EXISTS file_priority_index integer NOT NULL DEFAULT 200,
    ADD COLUMN IF NOT EXISTS file_priority_locate integer NOT NULL DEFAULT 100;

