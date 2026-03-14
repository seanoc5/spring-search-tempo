-- 026: Remote crawl task queue for server-assigned folder work.
-- Supports client/server crawling where remote agents poll for next folders.

CREATE TABLE IF NOT EXISTS remote_crawl_task (
    id BIGINT PRIMARY KEY DEFAULT nextval('primary_sequence'),
    session_id BIGINT NOT NULL,
    crawl_config_id BIGINT NOT NULL,
    host VARCHAR(50) NOT NULL,
    folder_path TEXT NOT NULL,
    remote_uri TEXT NOT NULL,
    analysis_status TEXT NOT NULL,
    task_status TEXT NOT NULL DEFAULT 'PENDING',
    claim_token TEXT,
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    priority INT NOT NULL DEFAULT 0,
    depth INT,
    last_error TEXT,
    date_created TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_remote_crawl_task_session_uri UNIQUE (session_id, remote_uri),
    CONSTRAINT remote_crawl_task_analysis_status_check
        CHECK (analysis_status IN ('SKIP','LOCATE','INDEX','ANALYZE','SEMANTIC')),
    CONSTRAINT remote_crawl_task_task_status_check
        CHECK (task_status IN ('PENDING','CLAIMED','COMPLETED','FAILED','SKIPPED'))
);

CREATE INDEX IF NOT EXISTS idx_remote_crawl_task_session_status
    ON remote_crawl_task (session_id, task_status);

CREATE INDEX IF NOT EXISTS idx_remote_crawl_task_claim
    ON remote_crawl_task (session_id, claim_token);

CREATE INDEX IF NOT EXISTS idx_remote_crawl_task_host
    ON remote_crawl_task (host);
