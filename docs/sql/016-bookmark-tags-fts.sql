-- Issue #9: include tag names in the bookmark fts_vector.
--
-- The browser_bookmark.fts_vector column is a GENERATED ALWAYS expression.
-- PostgreSQL does NOT allow GENERATED expressions to reference joined tables,
-- so the import writer keeps a denormalized `tags_text` column in sync with
-- the bookmark_tag many-to-many relation. We then drop the existing generated
-- column and recreate it to include the new field, and add a GIN index so
-- the @@ tsquery searches don't scan the full table.
--
-- Safe to run on a freshly JPA-generated schema as well as upgrades. JPA's
-- ddl-auto: update never modifies a GENERATED ALWAYS expression once the
-- column exists, so this script must be re-run after entity changes that
-- affect the tsvector definition.

ALTER TABLE browser_bookmark
    ADD COLUMN IF NOT EXISTS tags_text text;

ALTER TABLE browser_bookmark
    DROP COLUMN IF EXISTS fts_vector;

ALTER TABLE browser_bookmark
    ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple',  coalesce(tags_text, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(domain, '')), 'B') ||
        setweight(to_tsvector('simple',  coalesce(folder_path, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
        setweight(to_tsvector('simple',  coalesce(url, '')), 'D')
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_browser_bookmark_fts
    ON browser_bookmark USING GIN (fts_vector);

CREATE INDEX IF NOT EXISTS idx_browser_bookmark_folder_path
    ON browser_bookmark (folder_path);

CREATE INDEX IF NOT EXISTS idx_bookmark_tag_name
    ON bookmark_tag (name);

-- Backfill tags_text for any bookmarks imported before this column existed.
UPDATE browser_bookmark b
   SET tags_text = sub.tag_names
  FROM (
        SELECT bbt.bookmark_id, string_agg(lower(bt.name), ' ') AS tag_names
          FROM browser_bookmark_tags bbt
          JOIN bookmark_tag bt ON bt.id = bbt.tag_id
         GROUP BY bbt.bookmark_id
       ) sub
 WHERE b.id = sub.bookmark_id
   AND (b.tags_text IS NULL OR b.tags_text = '');
