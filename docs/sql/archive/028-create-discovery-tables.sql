-- Migration 028: Create discovery session and discovered folder tables
-- These tables support the remote crawler onboarding workflow

-- Discovery session tracks a single onboarding/discovery run from a remote machine
CREATE TABLE IF NOT EXISTS discovery_session (
    id BIGINT PRIMARY KEY,
    host VARCHAR(100) NOT NULL,
    os_type VARCHAR(20) NOT NULL,
    root_paths TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_folders INT DEFAULT 0,
    classified_folders INT DEFAULT 0,
    skip_count INT DEFAULT 0,
    locate_count INT DEFAULT 0,
    index_count INT DEFAULT 0,
    analyze_count INT DEFAULT 0,
    discovery_duration_ms BIGINT,
    crawl_config_id BIGINT REFERENCES crawl_config(id),
    date_created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMP WITH TIME ZONE
);

-- Discovered folder stores each folder found during discovery
CREATE TABLE IF NOT EXISTS discovered_folder (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES discovery_session(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    depth INT NOT NULL DEFAULT 0,
    folder_count INT DEFAULT 0,
    file_count INT DEFAULT 0,
    total_size BIGINT DEFAULT 0,
    is_hidden BOOLEAN DEFAULT FALSE,
    suggested_status VARCHAR(20),
    assigned_status VARCHAR(20),
    classified BOOLEAN DEFAULT FALSE,
    parent_path TEXT
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_discovery_session_host ON discovery_session(host);
CREATE INDEX IF NOT EXISTS idx_discovery_session_status ON discovery_session(status);
CREATE INDEX IF NOT EXISTS idx_discovered_folder_session ON discovered_folder(session_id);
CREATE INDEX IF NOT EXISTS idx_discovered_folder_path ON discovered_folder(path);
CREATE INDEX IF NOT EXISTS idx_discovered_folder_depth ON discovered_folder(depth);
CREATE INDEX IF NOT EXISTS idx_discovered_folder_parent ON discovered_folder(parent_path);

COMMENT ON TABLE discovery_session IS 'Tracks remote crawler discovery/onboarding sessions';
COMMENT ON TABLE discovered_folder IS 'Folders discovered during remote onboarding, pending classification';
