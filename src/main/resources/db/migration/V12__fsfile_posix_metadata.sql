-- V11: Issue #120 — Persist POSIX owner / group / mode on fsfile,
-- plus a composite index supporting the metadata-duplicate finder.
--
-- The existing fsfile already carries a free-form `owner`, `group`,
-- and `permissions` (string "rwxrwxrwx") inherited from FSObject;
-- those stay for backwards compatibility. The new posix_* columns
-- give us:
--   - a numeric mode column (`posix_mode` stores the standard
--     POSIX 0-0777 mode value as a decimal integer), so we can do
--     bitwise SQL questions like "world-writable" = posix_mode & 2 != 0,
--     without parsing a string;
--   - POSIX-specific owner/group names that future cleanup can
--     migrate the legacy columns onto.
--
-- All nullable: Windows / non-POSIX filesystems will leave them null
-- (the crawl processors swallow UnsupportedOperationException).

ALTER TABLE fsfile
    ADD COLUMN IF NOT EXISTS posix_owner VARCHAR(255),
    ADD COLUMN IF NOT EXISTS posix_group VARCHAR(255),
    ADD COLUMN IF NOT EXISTS posix_mode  INTEGER;

-- Composite index supporting the metadata-duplicate finder query
-- (label, size, fs_last_modified). The duplicate-finder groups by
-- this exact triple, so a single covering index is the cheapest way
-- to make the GROUP BY / HAVING COUNT(*) > 1 query plan a hash or
-- merge group instead of a seq scan + sort.
CREATE INDEX IF NOT EXISTS idx_fsfile_metadata_dup
    ON fsfile (label, size, fs_last_modified);
