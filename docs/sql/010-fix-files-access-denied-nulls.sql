-- Migration: Fix NULL values in files_access_denied column
-- The previous migration (009) added the column with DEFAULT 0,
-- but that only applies to new rows. Existing rows have NULL.

UPDATE job_run SET files_access_denied = 0 WHERE files_access_denied IS NULL;
