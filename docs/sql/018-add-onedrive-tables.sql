-- OneDrive account and item tables for OneDrive sync integration
-- Follows same pattern as email_account / email_message tables

-- OneDrive account: stores OAuth2 tokens, drive info, and sync state
CREATE TABLE IF NOT EXISTS one_drive_account (
    id                      BIGINT NOT NULL DEFAULT nextval('primary_sequence'),
    uri                     TEXT NOT NULL UNIQUE,
    status                  TEXT DEFAULT 'NEW',
    analysis_status         TEXT DEFAULT 'LOCATE',
    label                   TEXT,
    description             TEXT,
    type                    TEXT,
    crawl_depth             INTEGER,
    size                    BIGINT,
    version                 BIGINT NOT NULL DEFAULT 0,
    archived                BOOLEAN,
    date_created            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    job_run_id              BIGINT,

    -- Microsoft identity
    microsoft_account_id    TEXT,
    display_name            TEXT,
    email                   TEXT,

    -- OAuth2 / PKCE
    client_id               TEXT NOT NULL,
    encrypted_refresh_token TEXT,
    token_obtained_at       TIMESTAMP WITH TIME ZONE,

    -- Drive info (from /me/drive)
    drive_id                TEXT,
    drive_type              TEXT,
    drive_quota_total       BIGINT,
    drive_quota_used        BIGINT,

    -- Delta sync state
    delta_token             TEXT,
    last_delta_sync_at      TIMESTAMP WITH TIME ZONE,
    last_full_sync_at       TIMESTAMP WITH TIME ZONE,

    -- Account status
    enabled                 BOOLEAN NOT NULL DEFAULT true,
    last_error              TEXT,
    last_error_at           TIMESTAMP WITH TIME ZONE,

    -- Aggregated stats
    total_items             BIGINT DEFAULT 0,
    total_size              BIGINT DEFAULT 0,

    CONSTRAINT pk_one_drive_account PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_one_drive_account_email ON one_drive_account (email);
CREATE INDEX IF NOT EXISTS idx_one_drive_account_microsoft_id ON one_drive_account (microsoft_account_id);
CREATE INDEX IF NOT EXISTS idx_one_drive_account_enabled ON one_drive_account (enabled);

-- OneDrive item: individual files and folders synced from OneDrive
CREATE TABLE IF NOT EXISTS one_drive_item (
    id                      BIGINT NOT NULL DEFAULT nextval('primary_sequence'),
    uri                     TEXT NOT NULL UNIQUE,
    status                  TEXT DEFAULT 'NEW',
    analysis_status         TEXT DEFAULT 'LOCATE',
    label                   TEXT,
    description             TEXT,
    type                    TEXT,
    crawl_depth             INTEGER,
    size                    BIGINT,
    version                 BIGINT NOT NULL DEFAULT 0,
    archived                BOOLEAN,
    date_created            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    job_run_id              BIGINT,

    -- Graph item identity (stable across renames/moves)
    graph_item_id           TEXT NOT NULL,
    parent_graph_item_id    TEXT,
    drive_id                TEXT NOT NULL,

    -- Item metadata
    item_name               TEXT,
    item_path               TEXT,
    mime_type               TEXT,
    is_folder               BOOLEAN NOT NULL DEFAULT false,
    is_deleted              BOOLEAN NOT NULL DEFAULT false,
    file_hash               TEXT,
    hash_algorithm          TEXT,

    -- Graph timestamps
    graph_created_at        TIMESTAMP WITH TIME ZONE,
    graph_modified_at       TIMESTAMP WITH TIME ZONE,

    -- Content (populated by Pass 2)
    body_text               TEXT,
    body_size               BIGINT,
    content_type            TEXT,
    author                  TEXT,
    title                   TEXT,
    page_count              INTEGER,

    -- Processing state
    fetch_status            TEXT NOT NULL DEFAULT 'METADATA_ONLY',
    chunked_at              TIMESTAMP WITH TIME ZONE,

    -- Full-text search vector
    fts_vector              tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(item_name, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(author, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
        setweight(to_tsvector('english', coalesce(item_path, '')), 'D')
    ) STORED,

    -- FK to account
    one_drive_account_id    BIGINT NOT NULL,

    CONSTRAINT pk_one_drive_item PRIMARY KEY (id),
    CONSTRAINT fk_one_drive_item_account FOREIGN KEY (one_drive_account_id)
        REFERENCES one_drive_account(id),
    CONSTRAINT uq_one_drive_item_graph UNIQUE (drive_id, graph_item_id)
);

CREATE INDEX IF NOT EXISTS idx_one_drive_item_account ON one_drive_item (one_drive_account_id);
CREATE INDEX IF NOT EXISTS idx_one_drive_item_graph_id ON one_drive_item (graph_item_id);
CREATE INDEX IF NOT EXISTS idx_one_drive_item_fetch_status ON one_drive_item (fetch_status);
CREATE INDEX IF NOT EXISTS idx_one_drive_item_fts ON one_drive_item USING GIN (fts_vector);
CREATE INDEX IF NOT EXISTS idx_one_drive_item_is_deleted ON one_drive_item (is_deleted) WHERE NOT is_deleted;
