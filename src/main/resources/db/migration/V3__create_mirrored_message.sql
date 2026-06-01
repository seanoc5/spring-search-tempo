-- Mirrored-message provenance + idempotency record (issue #25, ADR-005).
--
-- The real-run job (#23) will INSERT one row per copied message; #25's
-- dry-run service reads aggregate counts from this table so the user
-- sees how many messages have already been mirrored under a given
-- (mirror_config_id, source_folder) pair before kicking off a real run.
--
-- Per CLAUDE.md: no CONCURRENTLY (Flyway runs at startup before traffic).

CREATE TABLE IF NOT EXISTS mirrored_message (
    id                  BIGINT  PRIMARY KEY,
    mirror_config_id    BIGINT  NOT NULL REFERENCES mirror_config(id),
    message_id          TEXT    NOT NULL,
    source_folder       TEXT    NOT NULL,
    source_uid          BIGINT,
    dest_folder         TEXT,
    dest_uid            BIGINT,
    mirrored_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_mirrored_message_config_messageid
        UNIQUE (mirror_config_id, message_id)
);

CREATE INDEX IF NOT EXISTS ix_mirrored_message_config_folder
    ON mirrored_message (mirror_config_id, source_folder);
