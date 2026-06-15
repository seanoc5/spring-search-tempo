-- V13: Issue #119 — Add content_hash column to fsfile for byte-identical dedup.
-- (Renumbered from V11 → V13 per issue #132; V11 was a collision with V11__archive_entries.sql)
--
-- The hash is SHA-256 over the RAW file bytes (not extracted text), computed
-- in the INDEX-class text extraction path. LOCATE files don't get a hash —
-- hashing would require opening the file content, which defeats the
-- "plocate-equivalent" purpose of LOCATE. SKIP files aren't enumerated.
--
-- CHAR(64) = 64 hex chars from SHA-256.
-- Nullable: pre-existing rows have no hash until next re-crawl, and LOCATE/
-- SKIP files remain NULL forever by design.
-- Index supports findByContentHash for "show me the duplicates of this file."

ALTER TABLE fsfile
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64);

CREATE INDEX IF NOT EXISTS idx_fsfile_content_hash
    ON fsfile(content_hash)
    WHERE content_hash IS NOT NULL;
