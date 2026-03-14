-- Add credential_env_var column to email_account
-- Stores the name of the environment variable holding the IMAP password,
-- allowing credential configuration via the UI instead of only YAML.


ALTER TABLE email_account ADD COLUMN IF NOT EXISTS credential_env_var VARCHAR(100);
