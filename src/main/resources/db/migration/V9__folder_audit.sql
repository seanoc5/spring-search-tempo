-- V9: Issue #103 — Folder audit (filesystem): schema for run + per-folder snapshot.
--
-- The "folder audit" pass walks every directory under the configured start
-- paths INCLUDING under SKIP roots (configurable peek-depth). It is a
-- read-only sanity check, distinct from CrawlMode.DISCOVERY (which honors
-- SKIP at the visitor) and CrawlMode.ENFORCE (which extracts content).
--
-- Schema follows the architectural decision in the issue: snapshot data
-- lives in sibling tables, NOT on fs_folder. fs_folder remains the durable
-- operational record; folder_snapshot is disposable scratch produced by a
-- given run, queryable for "hidden gem" candidates and reconciliation.
--
-- source_type is future-proofed for IMAP / OneDrive auditors; only the
-- FILESYSTEM auditor is implemented in this migration's scope.
--
-- Per CLAUDE.md: no CONCURRENTLY (Flyway runs at startup before traffic).

CREATE TABLE IF NOT EXISTS folder_audit_run (
    id                              BIGINT PRIMARY KEY,
    started                         TIMESTAMP WITH TIME ZONE NOT NULL,
    finished                        TIMESTAMP WITH TIME ZONE,
    source_type                     VARCHAR(32) NOT NULL,
    source_ref                      TEXT,
    total_folders                   BIGINT NOT NULL DEFAULT 0,
    skip_subtree_count              BIGINT NOT NULL DEFAULT 0,
    hidden_gem_count                BIGINT NOT NULL DEFAULT 0,
    status                          VARCHAR(32) NOT NULL,
    error_message                   TEXT,
    CONSTRAINT folder_audit_run_source_type_chk
        CHECK (source_type IN ('FILESYSTEM', 'IMAP', 'ONEDRIVE')),
    CONSTRAINT folder_audit_run_status_chk
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_folder_audit_run_started
    ON folder_audit_run(started DESC);

CREATE TABLE IF NOT EXISTS folder_snapshot (
    id                              BIGINT PRIMARY KEY,
    audit_run_id                    BIGINT NOT NULL REFERENCES folder_audit_run(id) ON DELETE CASCADE,
    path                            TEXT NOT NULL,
    parent_path                     TEXT,
    depth                           INTEGER NOT NULL,
    under_skip_pattern              TEXT,
    matched_index_pattern           TEXT,
    file_count                      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_folder_snapshot_run_path
    ON folder_snapshot(audit_run_id, path);

-- Browse "hidden gem" candidates within a run (folders under SKIP whose
-- name matches an INDEX/ANALYZE pattern). The partial-index variant gives
-- a more compact structure for the hidden-gem query specifically; the
-- composite above is for general browsing.
CREATE INDEX IF NOT EXISTS idx_folder_snapshot_run_skip
    ON folder_snapshot(audit_run_id, under_skip_pattern);
