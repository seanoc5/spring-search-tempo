BEGIN;

CREATE TABLE IF NOT EXISTS host_crawl_session (
    id bigint NOT NULL,
    source_host_id bigint NOT NULL,
    crawl_config_id bigint NOT NULL,
    job_run_id bigint NOT NULL,
    session_type character varying(32) NOT NULL,
    selection_policy character varying(128),
    selection_reason_summary text,
    status character varying(20) NOT NULL,
    started_at timestamp(6) with time zone NOT NULL,
    last_updated timestamp(6) with time zone NOT NULL,
    completed_at timestamp(6) with time zone,
    error_message text
);

CREATE TABLE IF NOT EXISTS host_crawl_session_folder (
    id bigint NOT NULL,
    host_crawl_session_id bigint NOT NULL,
    fs_folder_id bigint,
    remote_crawl_task_id bigint,
    selected_path text NOT NULL,
    analysis_status text NOT NULL,
    selection_reason character varying(32) NOT NULL,
    selection_reason_detail text,
    selected_at timestamp(6) with time zone NOT NULL,
    claimed_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    result_status character varying(32) NOT NULL,
    files_seen integer,
    files_changed integer,
    error_message text
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'host_crawl_session_pkey') THEN
        ALTER TABLE host_crawl_session ADD CONSTRAINT host_crawl_session_pkey PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_host_crawl_session_job_run') THEN
        ALTER TABLE host_crawl_session ADD CONSTRAINT uk_host_crawl_session_job_run UNIQUE (job_run_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'host_crawl_session_folder_pkey') THEN
        ALTER TABLE host_crawl_session_folder ADD CONSTRAINT host_crawl_session_folder_pkey PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_hcsf_session_path') THEN
        ALTER TABLE host_crawl_session_folder ADD CONSTRAINT uk_hcsf_session_path UNIQUE (host_crawl_session_id, selected_path);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_host_crawl_session_source_host') THEN
        ALTER TABLE host_crawl_session
            ADD CONSTRAINT fk_host_crawl_session_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_host_crawl_session_crawl_config') THEN
        ALTER TABLE host_crawl_session
            ADD CONSTRAINT fk_host_crawl_session_crawl_config
            FOREIGN KEY (crawl_config_id) REFERENCES crawl_config(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_host_crawl_session_job_run') THEN
        ALTER TABLE host_crawl_session
            ADD CONSTRAINT fk_host_crawl_session_job_run
            FOREIGN KEY (job_run_id) REFERENCES job_run(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_hcsf_session') THEN
        ALTER TABLE host_crawl_session_folder
            ADD CONSTRAINT fk_hcsf_session
            FOREIGN KEY (host_crawl_session_id) REFERENCES host_crawl_session(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_hcsf_fsfolder') THEN
        ALTER TABLE host_crawl_session_folder
            ADD CONSTRAINT fk_hcsf_fsfolder
            FOREIGN KEY (fs_folder_id) REFERENCES fsfolder(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_hcsf_remote_task') THEN
        ALTER TABLE host_crawl_session_folder
            ADD CONSTRAINT fk_hcsf_remote_task
            FOREIGN KEY (remote_crawl_task_id) REFERENCES remote_crawl_task(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_host_crawl_session_source_host ON host_crawl_session (source_host_id, status);
CREATE INDEX IF NOT EXISTS idx_host_crawl_session_crawl_config ON host_crawl_session (crawl_config_id, started_at);
CREATE INDEX IF NOT EXISTS idx_hcsf_session_status ON host_crawl_session_folder (host_crawl_session_id, result_status);
CREATE INDEX IF NOT EXISTS idx_hcsf_remote_task_id ON host_crawl_session_folder (remote_crawl_task_id);

COMMIT;
