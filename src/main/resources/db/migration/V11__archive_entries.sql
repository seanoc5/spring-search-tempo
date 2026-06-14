-- V11: Issue #118 — Per-entry FSFile rows for archives (zip/tar/7z/jar).
--
-- Each archive entry becomes its own fs_file row with a synthetic jar-style URI
-- like file:///path/to/archive.zip!/inner/file.txt. The parent_archive_uri
-- column points back at the enclosing archive's fs_file.uri so re-enumeration
-- on archive change can find and delete the stale entries in one query.

ALTER TABLE fs_file
    ADD COLUMN IF NOT EXISTS parent_archive_uri TEXT;

CREATE INDEX IF NOT EXISTS idx_fs_file_parent_archive_uri
    ON fs_file(parent_archive_uri)
    WHERE parent_archive_uri IS NOT NULL;
