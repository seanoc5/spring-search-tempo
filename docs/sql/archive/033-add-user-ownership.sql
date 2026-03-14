-- Migration: Add user ownership for multi-tenancy visibility
-- Users own sourceHosts and email accounts, enabling "default mine only" filtering

BEGIN;

-- User-to-sourceHost mapping table
-- A user can own multiple sourceHosts (e.g., user 'sean' owns sourceHost 'minti9')
CREATE TABLE IF NOT EXISTS user_source_host (
    id BIGINT PRIMARY KEY DEFAULT nextval('primary_sequence'),
    spring_user_id BIGINT NOT NULL REFERENCES spring_user(id) ON DELETE CASCADE,
    source_host VARCHAR(50) NOT NULL,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_source_host UNIQUE (spring_user_id, source_host)
);

-- Index for looking up sourceHosts by user
CREATE INDEX IF NOT EXISTS idx_user_source_host_user ON user_source_host(spring_user_id);

-- Index for looking up users by sourceHost (admin operations)
CREATE INDEX IF NOT EXISTS idx_user_source_host_host ON user_source_host(source_host);

-- Add owner to email_account for ownership-based visibility
ALTER TABLE email_account ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES spring_user(id);

-- Index for finding email accounts by owner
CREATE INDEX IF NOT EXISTS idx_email_account_owner ON email_account(owner_user_id);

COMMIT;
