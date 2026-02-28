-- Migration 016: Add isRead column to email_message for read/unread tracking

ALTER TABLE email_message ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for efficient queries filtering by account and read status
CREATE INDEX IF NOT EXISTS idx_email_message_account_read ON email_message(email_account_id, is_read);
