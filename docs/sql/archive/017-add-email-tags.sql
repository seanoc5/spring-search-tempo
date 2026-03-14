-- Migration 017: Create email_tag table and join table for message tagging

CREATE TABLE IF NOT EXISTS email_tag (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    color VARCHAR(7) NOT NULL DEFAULT '#6c757d',
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Join table for many-to-many relationship between messages and tags
CREATE TABLE IF NOT EXISTS email_message_tags (
    email_message_id BIGINT NOT NULL REFERENCES email_message(id) ON DELETE CASCADE,
    email_tag_id BIGINT NOT NULL REFERENCES email_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (email_message_id, email_tag_id)
);

-- Index for finding all messages with a specific tag
CREATE INDEX IF NOT EXISTS idx_email_message_tags_tag_id ON email_message_tags(email_tag_id);

-- Seed 'junk' system tag (protected from deletion/editing)
INSERT INTO email_tag (id, name, color, is_system)
VALUES (1, 'junk', '#dc3545', TRUE)
ON CONFLICT (name) DO NOTHING;

-- Create sequence for email_tag if using standard primary_sequence
-- (email_tag uses same sequence as other entities)
