-- V8: Issue #84 — UIDVALIDITY hard-stop + folder re-enumeration drift detection.
--
-- Two related correctness gaps in the IMAP sync path:
--
--   1. UIDVALIDITY changes silently corrupt sync state. When a server rotates
--      a folder's UIDVALIDITY (mailbox restore, server migration, admin
--      reindex), stored lastSyncUid values become meaningless. Before this
--      migration, the reader auto-reset to a full resync — losing the
--      diagnostic signal the operator wants to see. The new behaviour halts
--      the folder and surfaces the event in the UI for manual reconcile.
--
--   2. Folder enumeration runs only on first-run. New server-side folders
--      (new Gmail labels, new Outlook subfolders) are invisible until the
--      operator triggers manual re-enumeration. We now re-enumerate when
--      account.last_folder_enumerated_at is older than the configured
--      max-age (default 24h).
--
-- Columns added:
--   email_folder.uid_validity_mismatch_at — set when reader detects a
--       UIDVALIDITY rotation. Null = healthy. Non-null = halted; UI shows
--       a red banner with Reconcile / Skip actions.
--   email_account.last_folder_enumerated_at — when this account last had
--       its full folder list refreshed via LIST *. Drives the re-enum
--       cadence in EmailCrawlOrchestrator.resolveFolders().
--
-- Both columns are nullable so existing rows interpret as "never" without
-- a backfill — which is the correct semantic on day 1 (no mismatches
-- detected yet, and the next sync will populate last_folder_enumerated_at
-- the first time it walks the resolver).

ALTER TABLE email_folder
    ADD COLUMN IF NOT EXISTS uid_validity_mismatch_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE email_account
    ADD COLUMN IF NOT EXISTS last_folder_enumerated_at TIMESTAMP WITH TIME ZONE;
