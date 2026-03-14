-- Migration: Add warning_message column to job_run table
-- This column stores non-fatal warnings such as missing or unreadable start paths

ALTER TABLE job_run
ADD COLUMN IF NOT EXISTS warning_message TEXT;

COMMENT ON COLUMN job_run.warning_message IS 'Non-fatal warning messages (e.g., missing start paths). Newline-separated.';
