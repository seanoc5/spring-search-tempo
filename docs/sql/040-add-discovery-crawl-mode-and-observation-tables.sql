-- 040: Add DISCOVERY crawl mode and SKIP-branch observation tables.

ALTER TABLE crawl_config
    ADD COLUMN IF NOT EXISTS crawl_mode VARCHAR(20) NOT NULL DEFAULT 'ENFORCE',
    ADD COLUMN IF NOT EXISTS discovery_keeper_max_depth INT NOT NULL DEFAULT 20,
    ADD COLUMN IF NOT EXISTS discovery_skip_max_depth INT NOT NULL DEFAULT 10,
    ADD COLUMN IF NOT EXISTS discovery_file_sample_cap INT NOT NULL DEFAULT 50,
    ADD COLUMN IF NOT EXISTS discovery_auto_suggest_enabled BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'crawl_config_crawl_mode_check'
    ) THEN
        ALTER TABLE crawl_config
            ADD CONSTRAINT crawl_config_crawl_mode_check
            CHECK (crawl_mode IN ('ENFORCE', 'DISCOVERY'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS crawl_discovery_run (
    id BIGINT PRIMARY KEY,
    crawl_config_id BIGINT NOT NULL REFERENCES crawl_config(id) ON DELETE CASCADE,
    job_run_id BIGINT REFERENCES job_run(id) ON DELETE SET NULL,
    host VARCHAR(100) NOT NULL,
    run_status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    reapply_completed_at TIMESTAMPTZ,
    reapply_changed_count INT NOT NULL DEFAULT 0,
    observed_folder_count INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_crawl_discovery_run_cfg_host_started
    ON crawl_discovery_run (crawl_config_id, host, started_at);
CREATE INDEX IF NOT EXISTS idx_crawl_discovery_run_job_run
    ON crawl_discovery_run (job_run_id);

CREATE TABLE IF NOT EXISTS crawl_discovery_folder_obs (
    id BIGINT PRIMARY KEY,
    crawl_config_id BIGINT NOT NULL REFERENCES crawl_config(id) ON DELETE CASCADE,
    host VARCHAR(100) NOT NULL,
    path TEXT NOT NULL,
    depth INT NOT NULL,
    in_skip_branch BOOLEAN NOT NULL DEFAULT TRUE,
    manual_override VARCHAR(20),
    skip_by_current_rules BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_job_run_id BIGINT REFERENCES job_run(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_crawl_discovery_folder_obs_cfg_host_path
    ON crawl_discovery_folder_obs (crawl_config_id, host, path);
CREATE INDEX IF NOT EXISTS idx_discovery_obs_cfg_host_last_seen
    ON crawl_discovery_folder_obs (crawl_config_id, host, last_seen_at);

CREATE TABLE IF NOT EXISTS crawl_discovery_file_sample (
    id BIGINT PRIMARY KEY,
    folder_obs_id BIGINT NOT NULL REFERENCES crawl_discovery_folder_obs(id) ON DELETE CASCADE,
    sample_slot SMALLINT NOT NULL CHECK (sample_slot BETWEEN 1 AND 50),
    file_name TEXT NOT NULL,
    file_size BIGINT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_crawl_discovery_file_sample_slot
    ON crawl_discovery_file_sample (folder_obs_id, sample_slot);
CREATE INDEX IF NOT EXISTS idx_discovery_sample_folder
    ON crawl_discovery_file_sample (folder_obs_id);
