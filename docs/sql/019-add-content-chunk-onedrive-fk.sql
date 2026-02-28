-- Add OneDrive item FK to content_chunks table
-- A ContentChunk belongs to exactly one of: FSFile, EmailMessage, BrowserBookmark, or OneDriveItem

ALTER TABLE content_chunks
    ADD COLUMN IF NOT EXISTS one_drive_item_id BIGINT;

ALTER TABLE content_chunks
    ADD CONSTRAINT fk_content_chunk_onedrive_item
    FOREIGN KEY (one_drive_item_id) REFERENCES one_drive_item(id);

CREATE INDEX IF NOT EXISTS idx_content_chunk_onedrive_item ON content_chunks (one_drive_item_id);
