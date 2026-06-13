-- V10: Issue #104 — Hidden-gem resolution table.
--
-- Records durable decisions made against folder-audit "hidden gem"
-- candidates: paths living under a SKIP root whose name matches an
-- INDEX/ANALYZE pattern. Resolutions survive snapshot rotation —
-- they are keyed on (source_ref, path), NOT on a specific audit_run_id,
-- so dismissing a candidate today suppresses it for every future
-- audit run against the same source.
--
-- Resolution kinds:
--   DISMISSED            — operator looked at it, decided "no, leave it SKIP"
--   PROMOTED_TO_INDEX    — operator chose to reclassify to INDEX
--   PROMOTED_TO_ANALYZE  — operator chose to reclassify to ANALYZE
--
-- Promotion also creates/updates a CrawlConfig rule (see #104 brief);
-- the resolution row itself just records the durable suppression so the
-- hidden-gem list view's NOT EXISTS filter keeps the path out of sight.

CREATE TABLE IF NOT EXISTS hidden_gem_resolution (
    id                              BIGINT PRIMARY KEY,
    source_ref                      TEXT NOT NULL,
    path                            TEXT NOT NULL,
    resolution                      VARCHAR(32) NOT NULL,
    resolved_at                     TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by                     TEXT,
    CONSTRAINT hidden_gem_resolution_resolution_chk
        CHECK (resolution IN ('DISMISSED', 'PROMOTED_TO_INDEX', 'PROMOTED_TO_ANALYZE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hidden_gem_resolution_source_path
    ON hidden_gem_resolution(source_ref, path);
