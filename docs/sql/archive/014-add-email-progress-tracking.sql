-- Migration: Add progress tracking fields to job_run
-- Used for showing "X of Y processed" during email sync and other batch jobs

ALTER TABLE job_run ADD COLUMN IF NOT EXISTS expected_total BIGINT;
ALTER TABLE job_run ADD COLUMN IF NOT EXISTS processed_count BIGINT DEFAULT 0;
ALTER TABLE job_run ADD COLUMN IF NOT EXISTS current_step_name TEXT;

-- Add index for finding running jobs with progress
CREATE INDEX IF NOT EXISTS idx_job_run_running_progress
    ON job_run(run_status)
    WHERE run_status = 'RUNNING';
