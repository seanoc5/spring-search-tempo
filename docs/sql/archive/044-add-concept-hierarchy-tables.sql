-- Migration 044: add concept hierarchy and concept node tables
-- Creates a standalone taxonomy model so multiple concept trees can coexist.

CREATE TABLE IF NOT EXISTS concept_hierarchy (
    id                  BIGINT NOT NULL DEFAULT nextval('primary_sequence'),
    code                VARCHAR(100) NOT NULL,
    uri                 TEXT NOT NULL,
    label               TEXT NOT NULL,
    description         TEXT,
    source_system       VARCHAR(50) NOT NULL,
    core                BOOLEAN NOT NULL DEFAULT FALSE,
    supports_address    BOOLEAN NOT NULL DEFAULT FALSE,
    expected_node_count BIGINT,
    external_version    VARCHAR(100),
    last_imported_at    TIMESTAMP WITH TIME ZONE,
    date_created        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_concept_hierarchy PRIMARY KEY (id),
    CONSTRAINT uk_concept_hierarchy_code UNIQUE (code),
    CONSTRAINT uk_concept_hierarchy_uri UNIQUE (uri),
    CONSTRAINT concept_hierarchy_source_system_check CHECK (source_system IN ('OCONECO', 'OPENALEX'))
);

CREATE INDEX IF NOT EXISTS idx_concept_hierarchy_source_system
    ON concept_hierarchy(source_system);

CREATE INDEX IF NOT EXISTS idx_concept_hierarchy_core
    ON concept_hierarchy(core);

CREATE TABLE IF NOT EXISTS concept_node (
    id           BIGINT NOT NULL DEFAULT nextval('primary_sequence'),
    hierarchy_id BIGINT NOT NULL,
    parent_id    BIGINT,
    external_key VARCHAR(255) NOT NULL,
    uri          TEXT NOT NULL,
    label        TEXT NOT NULL,
    description  TEXT,
    address      VARCHAR(1024),
    path         TEXT,
    wikidata_id  VARCHAR(64),
    openalex_id  VARCHAR(128),
    depth_level  INTEGER,
    leaf         BOOLEAN NOT NULL DEFAULT FALSE,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_concept_node PRIMARY KEY (id),
    CONSTRAINT fk_concept_node_hierarchy FOREIGN KEY (hierarchy_id) REFERENCES concept_hierarchy(id),
    CONSTRAINT fk_concept_node_parent FOREIGN KEY (parent_id) REFERENCES concept_node(id),
    CONSTRAINT uk_concept_node_hierarchy_external_key UNIQUE (hierarchy_id, external_key),
    CONSTRAINT uk_concept_node_hierarchy_uri UNIQUE (hierarchy_id, uri)
);

CREATE INDEX IF NOT EXISTS idx_concept_node_hierarchy
    ON concept_node(hierarchy_id);

CREATE INDEX IF NOT EXISTS idx_concept_node_parent
    ON concept_node(parent_id);

CREATE INDEX IF NOT EXISTS idx_concept_node_address
    ON concept_node(address);

CREATE INDEX IF NOT EXISTS idx_concept_node_wikidata
    ON concept_node(wikidata_id);

INSERT INTO concept_hierarchy (
    code,
    uri,
    label,
    description,
    source_system,
    core,
    supports_address,
    expected_node_count
)
VALUES
    (
        'OCONECO',
        'concept-hierarchy:oconeco',
        'OconEco',
        'Primary OconEco hierarchy with address-bearing concept nodes.',
        'OCONECO',
        TRUE,
        TRUE,
        4500
    ),
    (
        'OPENALEX_CONCEPTS',
        'concept-hierarchy:openalex-concepts',
        'OpenAlex Concepts',
        'OpenAlex concept taxonomy imported without postal-style addresses.',
        'OPENALEX',
        TRUE,
        FALSE,
        60000
    ),
    (
        'OPENALEX_TOPICS',
        'concept-hierarchy:openalex-topics',
        'OpenAlex Topics',
        'OpenAlex topic hierarchy kept distinct from OpenAlex Concepts.',
        'OPENALEX',
        TRUE,
        FALSE,
        NULL
    )
ON CONFLICT (code) DO NOTHING;
