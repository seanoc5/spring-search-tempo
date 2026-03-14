-- 020-multi-host-support.sql
-- Multi-host support: increase allocationSize and add sourceHost column.
-- Allows multiple Tempo instances to share one PostgreSQL database.

-- 1. Alter sequence increment to match new allocationSize (200)
ALTER SEQUENCE primary_sequence INCREMENT BY 200;

-- 2. Add sourceHost column to all SaveableObject-backed tables
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE fsfolder ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE crawl_config ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE public.email_message ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE email_account ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE email_folder ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE job_run ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE browser_bookmark ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE one_drive_account ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);
ALTER TABLE one_drive_item ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);

-- 3. Add sourceHost column to ContentChunk (doesn't extend SaveableObject)
ALTER TABLE content_chunks ADD COLUMN IF NOT EXISTS source_host VARCHAR(50);

-- 4. Indexes for filtering by host on high-volume tables
CREATE INDEX IF NOT EXISTS idx_fsfile_source_host ON fsfile (source_host);
CREATE INDEX IF NOT EXISTS idx_fsfolder_source_host ON fsfolder (source_host);
CREATE INDEX IF NOT EXISTS idx_emailmessage_source_host ON email_message (source_host);
CREATE INDEX IF NOT EXISTS idx_content_chunks_source_host ON content_chunks (source_host);
CREATE INDEX IF NOT EXISTS idx_jobrun_source_host ON job_run (source_host);
