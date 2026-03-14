-- Migration: Add browser bookmark tables for Firefox import
-- Version: 006
-- Description: Creates tables for browser profiles, bookmarks, and tags

-- Browser profile table (represents a Firefox/Chrome profile)
CREATE TABLE IF NOT EXISTS browser_profile (
    id BIGINT PRIMARY KEY,
    uri TEXT NOT NULL UNIQUE,
    status TEXT DEFAULT 'NEW',
    analysis_status TEXT DEFAULT 'LOCATE',
    label TEXT,
    description TEXT,
    type TEXT,
    crawl_depth INTEGER,
    size BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    archived BOOLEAN,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL,
    job_run_id BIGINT,
    browser_type TEXT NOT NULL,
    profile_name TEXT,
    profile_path TEXT,
    places_db_path TEXT,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    last_sync_bookmark_count INTEGER,
    enabled BOOLEAN DEFAULT true,
    last_error TEXT,
    last_error_at TIMESTAMP WITH TIME ZONE
);

-- Bookmark tag table
CREATE TABLE IF NOT EXISTS bookmark_tag (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_name TEXT,
    usage_count INTEGER DEFAULT 0,
    source TEXT,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Browser bookmark table
CREATE TABLE IF NOT EXISTS browser_bookmark (
    id BIGINT PRIMARY KEY,
    uri TEXT NOT NULL UNIQUE,
    status TEXT DEFAULT 'NEW',
    analysis_status TEXT DEFAULT 'SEMANTIC',
    label TEXT,
    description TEXT,
    type TEXT,
    crawl_depth INTEGER,
    size BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    archived BOOLEAN,
    date_created TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL,
    job_run_id BIGINT,
    firefox_place_id BIGINT,
    firefox_bookmark_id BIGINT,
    url TEXT NOT NULL,
    title TEXT,
    domain TEXT,
    scheme TEXT,
    visit_count INTEGER,
    last_visit_date TIMESTAMP WITH TIME ZONE,
    frecency INTEGER,
    date_added TIMESTAMP WITH TIME ZONE,
    folder_path TEXT,
    body_text TEXT,
    fetched_at TIMESTAMP WITH TIME ZONE,
    chunked_at TIMESTAMP WITH TIME ZONE,
    browser_profile_id BIGINT REFERENCES browser_profile(id),
    fts_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(domain, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(folder_path, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
        setweight(to_tsvector('english', coalesce(url, '')), 'D')
    ) STORED
);

-- Browser bookmark to tag join table (many-to-many)
CREATE TABLE IF NOT EXISTS browser_bookmark_tags (
    bookmark_id BIGINT NOT NULL REFERENCES browser_bookmark(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES bookmark_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (bookmark_id, tag_id)
);

-- Add browser_bookmark_id to content_chunks for future content chunking
ALTER TABLE content_chunks ADD COLUMN IF NOT EXISTS browser_bookmark_id BIGINT
    REFERENCES browser_bookmark(id);

-- Indexes for browser_profile
CREATE INDEX IF NOT EXISTS idx_browser_profile_browser_type ON browser_profile(browser_type);
CREATE INDEX IF NOT EXISTS idx_browser_profile_enabled ON browser_profile(enabled);
CREATE INDEX IF NOT EXISTS idx_browser_profile_profile_path ON browser_profile(profile_path);

-- Indexes for bookmark_tag
CREATE INDEX IF NOT EXISTS idx_bookmark_tag_name ON bookmark_tag(name);
CREATE INDEX IF NOT EXISTS idx_bookmark_tag_usage ON bookmark_tag(usage_count DESC);
CREATE INDEX IF NOT EXISTS idx_bookmark_tag_source ON bookmark_tag(source);

-- Indexes for browser_bookmark
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_url ON browser_bookmark(url);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_domain ON browser_bookmark(domain);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_scheme ON browser_bookmark(scheme);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_frecency ON browser_bookmark(frecency DESC);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_date_added ON browser_bookmark(date_added DESC);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_folder_path ON browser_bookmark(folder_path);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_profile ON browser_bookmark(browser_profile_id);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_fts ON browser_bookmark USING GIN(fts_vector);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_firefox_place ON browser_bookmark(firefox_place_id);

-- Indexes for join table
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_tags_bookmark ON browser_bookmark_tags(bookmark_id);
CREATE INDEX IF NOT EXISTS idx_browser_bookmark_tags_tag ON browser_bookmark_tags(tag_id);

-- Index for content_chunks browser_bookmark foreign key
CREATE INDEX IF NOT EXISTS idx_content_chunks_browser_bookmark ON content_chunks(browser_bookmark_id);

-- Comment on tables
COMMENT ON TABLE browser_profile IS 'Browser profile sources (Firefox, Chrome) for bookmark import';
COMMENT ON TABLE bookmark_tag IS 'Tags/labels applied to browser bookmarks';
COMMENT ON TABLE browser_bookmark IS 'Imported browser bookmarks with metadata and optional fetched content';
COMMENT ON TABLE browser_bookmark_tags IS 'Many-to-many relationship between bookmarks and tags';
