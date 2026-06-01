-- IMAP mailbox mirror configuration (issue #22, ADR-005).
--
-- Stores source → destination IMAP account pairs and per-pair folder
-- mapping rules. The actual copy job (ImapMirrorService / MirrorJob) and
-- progress tracking are separate tickets; this is the foundation.
--
-- Folder mappings are persisted as JSON to keep the schema simple — a
-- separate mapping table can be introduced later if cross-mirror reuse
-- becomes a requirement (see ADR-005 guardrails).
--
-- Per CLAUDE.md: no CONCURRENTLY (Flyway runs at startup before traffic).

CREATE TABLE IF NOT EXISTS mirror_config (
    id                              BIGINT PRIMARY KEY,
    uri                             TEXT NOT NULL UNIQUE,
    status                          TEXT,
    analysis_status                 TEXT,
    label                           TEXT,
    description                     TEXT,
    type                            TEXT,
    crawl_depth                     INTEGER,
    size                            BIGINT,
    version                         BIGINT NOT NULL DEFAULT 0,
    archived                        BOOLEAN,
    date_created                    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated                    TIMESTAMP WITH TIME ZONE NOT NULL,
    job_run_id                      BIGINT,
    source_host                     VARCHAR(50),
    located_at                      TIMESTAMP WITH TIME ZONE,
    skip_detected                   BOOLEAN,
    analysis_status_reason          TEXT,
    analysis_status_set_by          TEXT,

    source_account_id               BIGINT NOT NULL REFERENCES email_account(id),
    dest_account_id                 BIGINT NOT NULL REFERENCES email_account(id),
    name                            TEXT NOT NULL,
    enabled                         BOOLEAN NOT NULL DEFAULT TRUE,
    folder_mappings                 TEXT DEFAULT '[]',
    append_rate_limit_per_second    INTEGER DEFAULT 10,
    last_run_started_at             TIMESTAMP WITH TIME ZONE,
    last_run_completed_at           TIMESTAMP WITH TIME ZONE,
    last_error                      TEXT,

    CONSTRAINT mirror_config_source_ne_dest CHECK (source_account_id <> dest_account_id)
);

CREATE INDEX IF NOT EXISTS idx_mirror_config_source ON mirror_config(source_account_id);
CREATE INDEX IF NOT EXISTS idx_mirror_config_dest ON mirror_config(dest_account_id);
CREATE INDEX IF NOT EXISTS idx_mirror_config_enabled ON mirror_config(enabled) WHERE enabled = TRUE;
