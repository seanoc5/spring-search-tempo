-- Add metadata columns to fsfile table if they don't exist
-- These columns are defined in FSFile.kt but may be missing from the database

ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS author TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS title TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS subject TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS keywords TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS comments TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS creation_date TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS modified_date TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS language TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS content_type TEXT;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS page_count INTEGER;

-- Verify columns were added
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'fsfile'
AND column_name IN ('author', 'title', 'subject', 'keywords', 'content_type')
ORDER BY column_name;
