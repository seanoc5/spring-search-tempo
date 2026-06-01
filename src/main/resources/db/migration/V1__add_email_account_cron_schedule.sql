-- Per-account cron schedule for IMAP sync (issue #2, ADR-004).
--
-- Adds a cron_schedule column to email_account so each account can be
-- polled on its own cadence, and a last_dispatched_at column so the
-- minute-tick scheduler can decide which accounts are due without
-- re-dispatching ones that already fired this cron boundary.
--
-- Default cron is daily at 00:00 (Spring 6-field format: sec min hr dom mon dow).
-- Do NOT use CONCURRENTLY here — Flyway runs at app startup before serving
-- traffic, so CONCURRENTLY adds overhead with no benefit (CLAUDE.md).

ALTER TABLE email_account
    ADD COLUMN IF NOT EXISTS cron_schedule TEXT NOT NULL DEFAULT '0 0 0 * * *';

ALTER TABLE email_account
    ADD COLUMN IF NOT EXISTS last_dispatched_at TIMESTAMP WITH TIME ZONE;
