-- Enable pgvector extension (must run before Hibernate creates tables)
CREATE EXTENSION IF NOT EXISTS vector;

-- Spring Security Remember-Me Token Table
-- Required by JdbcTokenRepositoryImpl in BasicSecurityConfig
CREATE TABLE IF NOT EXISTS persistent_logins (
    username VARCHAR(64) NOT NULL,
    series VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    last_used TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS persistent_logins_username_idx ON persistent_logins (username);
