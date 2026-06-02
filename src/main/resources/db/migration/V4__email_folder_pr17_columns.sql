-- V4: Add the one EmailFolder column that PR #17 entity changes left
-- un-migrated.
--
-- Background: PR #17 added several fields to EmailFolder.kt while the
-- project was still on `ddl-auto: update`, so JPA auto-added `path`,
-- `subscribed`, `unmarked` to the schema. PR #18 then introduced Flyway
-- and switched ddl-auto to `validate` — and at some point after that,
-- `syncEnabled` was added to the entity without a matching migration.
-- Result: schema has every PR #17 column EXCEPT `sync_enabled`, and the
-- per-account scheduler crashes on every tick.
--
-- This migration is intentionally narrow: only adds `sync_enabled`.
-- IF NOT EXISTS keeps it safe to re-run on fresh DBs where ddl-auto
-- (now-disabled) might still have added the column historically.
--
-- Out of scope (see follow-up note):
--   - The legacy `full_path` column sits alongside `path` from when the
--     entity field was renamed. Existing folder rows likely have data
--     in `full_path` and NULL in `path`. Backfilling + dropping the
--     legacy column is a separate decision (data-loss-aware).

ALTER TABLE email_folder
    ADD COLUMN IF NOT EXISTS sync_enabled BOOLEAN NOT NULL DEFAULT TRUE;
