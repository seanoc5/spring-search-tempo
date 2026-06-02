-- Per-MirrorConfig cron schedule (issue #38).
--
-- Ports the per-EmailAccount cron pattern (V1, ADR-004) to MirrorConfig so
-- that mirror jobs can fire autonomously without anyone calling the REST
-- entrypoint. `enabled` already exists on mirror_config (V2); only the two
-- new scheduling columns are added here.
--
-- Default cron is daily at 02:00 (Spring 6-field format: sec min hr dom mon dow) —
-- mirrors typically run after the source quick-sync window has settled but
-- before working hours. Each MirrorConfig can be re-tuned via the edit form.
--
-- Do NOT use CONCURRENTLY here — Flyway runs at app startup before serving
-- traffic, so CONCURRENTLY adds overhead with no benefit (CLAUDE.md).

ALTER TABLE mirror_config
    ADD COLUMN IF NOT EXISTS cron_schedule TEXT;

ALTER TABLE mirror_config
    ADD COLUMN IF NOT EXISTS last_dispatched_at TIMESTAMP WITH TIME ZONE;
