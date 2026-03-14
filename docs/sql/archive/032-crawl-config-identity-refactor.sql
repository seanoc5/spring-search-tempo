-- 032: Refactor crawl_config identity fields.
-- - Use `label` as the primary UI label.
-- - Scope config-name uniqueness by `source_host` instead of globally.
-- - Normalize `name`/`source_host` and emit canonical URI format.
-- - Remove legacy `display_label`.

BEGIN;

-- Drop any existing UNIQUE(name) constraint (Hibernate-generated names vary).
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT c.conname
        FROM pg_constraint c
                 JOIN pg_class t ON t.oid = c.conrelid
                 JOIN unnest(c.conkey) WITH ORDINALITY AS u(attnum, ord) ON TRUE
                 JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = u.attnum
        WHERE t.relname = 'crawl_config'
          AND c.contype = 'u'
        GROUP BY c.conname
        HAVING array_agg(a.attname::text ORDER BY u.ord) = ARRAY['name']::text[]
        LOOP
            EXECUTE format('ALTER TABLE crawl_config DROP CONSTRAINT %I', rec.conname);
        END LOOP;
END
$$;

-- Normalize and backfill host identity.
UPDATE crawl_config
SET source_host = BTRIM(source_host)
WHERE source_host IS NOT NULL;

UPDATE crawl_config
SET source_host = 'legacy-local'
WHERE source_host IS NULL OR BTRIM(source_host) = '';

-- Normalize names and de-duplicate per host after normalization.
WITH normalized AS (
    SELECT id,
           source_host,
           UPPER(COALESCE(NULLIF(BTRIM(name), ''), 'UNNAMED_' || id)) AS normalized_name
    FROM crawl_config
),
ranked AS (
    SELECT id,
           normalized_name,
           ROW_NUMBER() OVER (
               PARTITION BY source_host, normalized_name
               ORDER BY id
               ) AS rn
    FROM normalized
)
UPDATE crawl_config c
SET name = CASE
               WHEN ranked.rn = 1 THEN ranked.normalized_name
               ELSE ranked.normalized_name || '_' || ranked.rn
    END
FROM ranked
WHERE c.id = ranked.id;

-- Backfill `label` from legacy `display_label` (if present), then from `name`.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'crawl_config'
          AND column_name = 'display_label'
    ) THEN
        EXECUTE
            'UPDATE crawl_config
             SET label = COALESCE(NULLIF(BTRIM(display_label), ''''), NULLIF(BTRIM(label), ''''), name)
             WHERE label IS NULL OR BTRIM(label) = '''';';
    END IF;
END
$$;

UPDATE crawl_config
SET label = COALESCE(NULLIF(BTRIM(label), ''), name)
WHERE label IS NULL OR BTRIM(label) = '';

-- Canonical URI format: tempo:crawl-config:<host-slug>/<name-slug>
UPDATE crawl_config
SET uri = FORMAT(
        'tempo:crawl-config:%s/%s',
        COALESCE(
            NULLIF(BTRIM(REGEXP_REPLACE(LOWER(source_host), '[^a-z0-9]+', '-', 'g'), '-'), ''),
            'default'
        ),
        COALESCE(
            NULLIF(BTRIM(REGEXP_REPLACE(LOWER(name), '[^a-z0-9]+', '-', 'g'), '-'), ''),
            'unnamed'
        )
          );

ALTER TABLE crawl_config ALTER COLUMN source_host SET NOT NULL;
ALTER TABLE crawl_config ALTER COLUMN name SET NOT NULL;
ALTER TABLE crawl_config ALTER COLUMN label SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_crawl_config_source_host_name_ci
    ON crawl_config (LOWER(source_host), LOWER(name));

CREATE INDEX IF NOT EXISTS idx_crawl_config_source_host ON crawl_config (source_host);

ALTER TABLE crawl_config DROP COLUMN IF EXISTS display_label;

COMMIT;
