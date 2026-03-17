-- Create canonical source_host records and attach them to the host-scoped tables.
-- Safe to rerun in development.

BEGIN;

CREATE TABLE IF NOT EXISTS source_host (
    id bigint NOT NULL,
    uri text NOT NULL,
    status text,
    analysis_status text,
    label text,
    description text,
    type text,
    crawl_depth integer,
    size bigint,
    version bigint NOT NULL,
    archived boolean,
    date_created timestamp(6) with time zone NOT NULL,
    last_updated timestamp(6) with time zone NOT NULL,
    job_run_id bigint,
    source_host character varying(50),
    analysis_status_reason text,
    analysis_status_set_by text,
    located_at timestamp(6) with time zone,
    skip_detected boolean,
    normalized_host character varying(100) NOT NULL,
    display_name character varying(100),
    os_type character varying(20),
    enabled boolean NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'source_host_pkey') THEN
        ALTER TABLE source_host ADD CONSTRAINT source_host_pkey PRIMARY KEY (id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_source_host_uri') THEN
        ALTER TABLE source_host ADD CONSTRAINT uk_source_host_uri UNIQUE (uri);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_source_host_normalized_host') THEN
        ALTER TABLE source_host ADD CONSTRAINT uk_source_host_normalized_host UNIQUE (normalized_host);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'source_host_status_check') THEN
        ALTER TABLE source_host
            ADD CONSTRAINT source_host_status_check
            CHECK (status IS NULL OR status = ANY (ARRAY['NEW','IN_PROGRESS','DIRTY','CURRENT','FAILED']));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'source_host_analysis_status_check') THEN
        ALTER TABLE source_host
            ADD CONSTRAINT source_host_analysis_status_check
            CHECK (analysis_status IS NULL OR analysis_status = ANY (ARRAY['SKIP','LOCATE','INDEX','ANALYZE','SEMANTIC']));
    END IF;
END $$;

ALTER TABLE crawl_config ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE discovery_session ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE user_source_host ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE remote_crawl_task ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE crawl_discovery_run ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE crawl_discovery_folder_obs ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE fsfolder ADD COLUMN IF NOT EXISTS source_host_id bigint;
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS source_host_id bigint;

CREATE INDEX IF NOT EXISTS idx_source_host_normalized_host ON source_host (normalized_host);
CREATE INDEX IF NOT EXISTS idx_crawl_config_source_host_id ON crawl_config (source_host_id);
CREATE INDEX IF NOT EXISTS idx_discovery_session_source_host_id ON discovery_session (source_host_id);
CREATE INDEX IF NOT EXISTS idx_user_source_host_source_host_id ON user_source_host (source_host_id);
CREATE INDEX IF NOT EXISTS idx_remote_crawl_task_source_host_id ON remote_crawl_task (source_host_id);
CREATE INDEX IF NOT EXISTS idx_crawl_discovery_run_source_host_id ON crawl_discovery_run (source_host_id);
CREATE INDEX IF NOT EXISTS idx_crawl_discovery_folder_obs_source_host_id ON crawl_discovery_folder_obs (source_host_id);
CREATE INDEX IF NOT EXISTS idx_fsfolder_source_host_id ON fsfolder (source_host_id);
CREATE INDEX IF NOT EXISTS idx_fsfile_source_host_id ON fsfile (source_host_id);

INSERT INTO source_host (
    id,
    uri,
    status,
    analysis_status,
    label,
    description,
    type,
    version,
    date_created,
    last_updated,
    source_host,
    normalized_host,
    display_name,
    os_type,
    enabled,
    last_seen_at
)
SELECT
    nextval('primary_sequence'),
    'tempo:source-host:' || normalized_host,
    'CURRENT',
    'LOCATE',
    min(display_name),
    'Source host ' || min(display_name),
    'SOURCE_HOST',
    0,
    now(),
    now(),
    normalized_host,
    normalized_host,
    min(display_name),
    max(os_type),
    true,
    now()
FROM (
    SELECT lower(trim(source_host)) AS normalized_host, min(trim(source_host)) AS display_name, NULL::varchar(20) AS os_type
    FROM crawl_config
    WHERE source_host IS NOT NULL AND trim(source_host) <> ''
    GROUP BY lower(trim(source_host))

    UNION ALL

    SELECT lower(trim(host)) AS normalized_host, min(trim(host)) AS display_name, max(os_type) AS os_type
    FROM discovery_session
    WHERE host IS NOT NULL AND trim(host) <> ''
    GROUP BY lower(trim(host))

    UNION ALL

    SELECT lower(trim(source_host)) AS normalized_host, min(trim(source_host)) AS display_name, NULL::varchar(20) AS os_type
    FROM user_source_host
    WHERE source_host IS NOT NULL AND trim(source_host) <> ''
    GROUP BY lower(trim(source_host))

    UNION ALL

    SELECT lower(trim(host)) AS normalized_host, min(trim(host)) AS display_name, NULL::varchar(20) AS os_type
    FROM remote_crawl_task
    WHERE host IS NOT NULL AND trim(host) <> ''
    GROUP BY lower(trim(host))

    UNION ALL

    SELECT lower(trim(host)) AS normalized_host, min(trim(host)) AS display_name, NULL::varchar(20) AS os_type
    FROM crawl_discovery_run
    WHERE host IS NOT NULL AND trim(host) <> ''
    GROUP BY lower(trim(host))

    UNION ALL

    SELECT lower(trim(host)) AS normalized_host, min(trim(host)) AS display_name, NULL::varchar(20) AS os_type
    FROM crawl_discovery_folder_obs
    WHERE host IS NOT NULL AND trim(host) <> ''
    GROUP BY lower(trim(host))

    UNION ALL

    SELECT lower(trim(source_host)) AS normalized_host, min(trim(source_host)) AS display_name, NULL::varchar(20) AS os_type
    FROM fsfolder
    WHERE source_host IS NOT NULL AND trim(source_host) <> ''
    GROUP BY lower(trim(source_host))

    UNION ALL

    SELECT lower(trim(source_host)) AS normalized_host, min(trim(source_host)) AS display_name, NULL::varchar(20) AS os_type
    FROM fsfile
    WHERE source_host IS NOT NULL AND trim(source_host) <> ''
    GROUP BY lower(trim(source_host))
) hosts
WHERE normalized_host IS NOT NULL
GROUP BY normalized_host
ON CONFLICT (normalized_host) DO UPDATE
SET
    display_name = COALESCE(source_host.display_name, EXCLUDED.display_name),
    os_type = COALESCE(source_host.os_type, EXCLUDED.os_type),
    last_updated = now(),
    last_seen_at = now();

UPDATE crawl_config cc
SET source_host_id = sh.id
FROM source_host sh
WHERE cc.source_host_id IS NULL
  AND cc.source_host IS NOT NULL
  AND lower(trim(cc.source_host)) = sh.normalized_host;

UPDATE discovery_session ds
SET source_host_id = sh.id
FROM source_host sh
WHERE ds.source_host_id IS NULL
  AND ds.host IS NOT NULL
  AND lower(trim(ds.host)) = sh.normalized_host;

UPDATE user_source_host ush
SET source_host_id = sh.id
FROM source_host sh
WHERE ush.source_host_id IS NULL
  AND ush.source_host IS NOT NULL
  AND lower(trim(ush.source_host)) = sh.normalized_host;

UPDATE remote_crawl_task rct
SET source_host_id = sh.id
FROM source_host sh
WHERE rct.source_host_id IS NULL
  AND rct.host IS NOT NULL
  AND lower(trim(rct.host)) = sh.normalized_host;

UPDATE crawl_discovery_run cdr
SET source_host_id = sh.id
FROM source_host sh
WHERE cdr.source_host_id IS NULL
  AND cdr.host IS NOT NULL
  AND lower(trim(cdr.host)) = sh.normalized_host;

UPDATE crawl_discovery_folder_obs cdfo
SET source_host_id = sh.id
FROM source_host sh
WHERE cdfo.source_host_id IS NULL
  AND cdfo.host IS NOT NULL
  AND lower(trim(cdfo.host)) = sh.normalized_host;

UPDATE fsfolder f
SET source_host_id = sh.id
FROM source_host sh
WHERE f.source_host_id IS NULL
  AND f.source_host IS NOT NULL
  AND lower(trim(f.source_host)) = sh.normalized_host;

UPDATE fsfile f
SET source_host_id = sh.id
FROM source_host sh
WHERE f.source_host_id IS NULL
  AND f.source_host IS NOT NULL
  AND lower(trim(f.source_host)) = sh.normalized_host;

UPDATE fsfile f
SET source_host_id = d.source_host_id
FROM fsfolder d
WHERE f.source_host_id IS NULL
  AND f.fs_folder_id = d.id
  AND d.source_host_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_crawl_config_source_host') THEN
        ALTER TABLE crawl_config
            ADD CONSTRAINT fk_crawl_config_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_discovery_session_source_host') THEN
        ALTER TABLE discovery_session
            ADD CONSTRAINT fk_discovery_session_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_source_host_source_host') THEN
        ALTER TABLE user_source_host
            ADD CONSTRAINT fk_user_source_host_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_remote_crawl_task_source_host') THEN
        ALTER TABLE remote_crawl_task
            ADD CONSTRAINT fk_remote_crawl_task_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_crawl_discovery_run_source_host') THEN
        ALTER TABLE crawl_discovery_run
            ADD CONSTRAINT fk_crawl_discovery_run_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_crawl_discovery_folder_obs_source_host') THEN
        ALTER TABLE crawl_discovery_folder_obs
            ADD CONSTRAINT fk_crawl_discovery_folder_obs_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_fsfolder_source_host') THEN
        ALTER TABLE fsfolder
            ADD CONSTRAINT fk_fsfolder_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_fsfile_source_host') THEN
        ALTER TABLE fsfile
            ADD CONSTRAINT fk_fsfile_source_host
            FOREIGN KEY (source_host_id) REFERENCES source_host(id);
    END IF;
END $$;

COMMIT;
