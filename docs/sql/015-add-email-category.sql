-- Migration: Add email categorization fields
-- Supports automatic categorization of emails by type (personal, work, promotions, etc.)

ALTER TABLE email_message ADD COLUMN IF NOT EXISTS category VARCHAR(20) DEFAULT 'UNCATEGORIZED';
ALTER TABLE email_message ADD COLUMN IF NOT EXISTS category_confidence DOUBLE PRECISION;
ALTER TABLE email_message ADD COLUMN IF NOT EXISTS categorized_at TIMESTAMP WITH TIME ZONE;

-- Index for filtering by category
CREATE INDEX IF NOT EXISTS idx_email_message_category ON email_message(category);

-- Index for finding uncategorized messages (for batch processing)
CREATE INDEX IF NOT EXISTS idx_email_message_uncategorized
    ON email_message(categorized_at)
    WHERE categorized_at IS NULL;
