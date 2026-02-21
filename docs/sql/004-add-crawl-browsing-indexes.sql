-- Add indexes for CrawlConfig file browsing functionality
-- These indexes support efficient queries for counting and listing files/folders by CrawlConfig

-- Index for looking up files by their job run
-- Used for: countByJobRunId(), findByJobRunId() queries
CREATE INDEX IF NOT EXISTS idx_fsfile_job_run_id ON fsfile(job_run_id);

-- Index for looking up folders by their job run
-- Used for: countByJobRunId(), findByJobRunId() queries
CREATE INDEX IF NOT EXISTS idx_fsfolder_job_run_id ON fsfolder(job_run_id);

-- Index for looking up job runs by crawl config
-- Used for: subquery joins when counting files/folders by crawl config
CREATE INDEX IF NOT EXISTS idx_job_run_crawl_config_id ON job_run(crawl_config_id);

-- Verify indexes were created
SELECT indexname, tablename
FROM pg_indexes
WHERE tablename IN ('fsfile', 'fsfolder', 'job_run')
  AND indexname IN ('idx_fsfile_job_run_id', 'idx_fsfolder_job_run_id', 'idx_job_run_crawl_config_id')
ORDER BY tablename, indexname;
