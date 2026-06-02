-- V5: Per-folder error isolation (issue #39).
--
-- 1. New sibling table `mirror_folder_checkpoint` holds one watermark
--    row per (mirrorConfigId, sourceFolder), so a multi-folder mirror
--    can resume each folder independently from its last good UID. The
--    legacy `mirror_checkpoint` row (one per config) is retained — it
--    still marks "a run was interrupted" and is cleared on success.
--
-- 2. `mirror_error.error_scope` distinguishes folder-level failures
--    (whole folder couldn't be enumerated) from message-level ones.
--    Existing rows backfill to 'MESSAGE'.
--
-- 3. `mirror_folder_progress.status` lets the dashboard render
--    OK / IN_FLIGHT / FAILED on each folder row instead of inferring
--    from the openedAt/completedAt pair. Existing rows backfill from
--    the timestamps.

CREATE TABLE IF NOT EXISTS mirror_folder_checkpoint (
    id                        BIGINT       NOT NULL PRIMARY KEY,
    mirror_config_id          BIGINT       NOT NULL,
    source_folder             TEXT         NOT NULL,
    last_source_uid           BIGINT       NOT NULL,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_mirror_folder_checkpoint_config_folder
        UNIQUE (mirror_config_id, source_folder)
);

CREATE INDEX IF NOT EXISTS ix_mirror_folder_checkpoint_config
    ON mirror_folder_checkpoint (mirror_config_id);

ALTER TABLE mirror_error
    ADD COLUMN IF NOT EXISTS error_scope TEXT NOT NULL DEFAULT 'MESSAGE';

ALTER TABLE mirror_folder_progress
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'IN_FLIGHT';

-- Backfill folder-progress status from existing timestamps so the
-- dashboard renders correctly for rows persisted before V5.
UPDATE mirror_folder_progress
   SET status = 'COMPLETED'
 WHERE completed_at IS NOT NULL
   AND status = 'IN_FLIGHT';
