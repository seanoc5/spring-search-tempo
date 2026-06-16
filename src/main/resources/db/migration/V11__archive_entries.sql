-- V11: Issue #118 — Per-entry FSFile rows for archives (zip/tar/7z/jar).
--
-- Each archive entry becomes its own fsfile row with a synthetic jar-style URI
-- like file:///path/to/archive.zip!/inner/file.txt. The parent_archive_uri
-- column points back at the enclosing archive's fsfile.uri so re-enumeration
-- on archive change can find and delete the stale entries in one query.
--
-- NOTE: This references the pre-V15 table name (`fsfile`). V15 renames it to
-- `fs_file` for snake_case consistency. The column and index added here carry
-- across the rename automatically.

ALTER TABLE fsfile
    ADD COLUMN IF NOT EXISTS parent_archive_uri TEXT;

CREATE INDEX IF NOT EXISTS idx_fsfile_parent_archive_uri
    ON fsfile(parent_archive_uri)
    WHERE parent_archive_uri IS NOT NULL;
