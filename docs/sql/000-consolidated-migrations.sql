-- 000-consolidated-migrations.sql
-- Single entrypoint to apply all incremental SQL migrations in order.
--
-- Usage:
--   PGPASSWORD=password psql -h <host> -p <port> -U postgres -d tempo -f docs/sql/000-consolidated-migrations.sql

\set ON_ERROR_STOP on

\echo 'Applying SQL migrations 001..039'
\ir 001-add-metadata-columns.sql
\ir 002-migrate-to-generated-fts.sql
\ir 003-add-nlp-to-fts.sql
\ir 004-add-crawl-browsing-indexes.sql
\ir 005-add-chunked-at-column.sql
\ir 006-add-browser-bookmarks.sql
\ir 007-add-freshness-hours-column.sql
\ir 008-add-job-run-warning-column.sql
\ir 009-add-files-access-denied-column.sql
\ir 010-fix-files-access-denied-nulls.sql
\ir 011-add-pgvector-embedding.sql
\ir 012-seed-crawl-configs.sql
\ir 013-add-session-remember-me.sql
\ir 014-add-email-progress-tracking.sql
\ir 015-add-email-category.sql
\ir 016-add-email-read-status.sql
\ir 017-add-email-tags.sql
\ir 018-add-onedrive-tables.sql
\ir 019-add-content-chunk-onedrive-fk.sql
\ir 020-multi-host-support.sql
\ir 021-add-credential-env-var.sql
\ir 022-promote-user-content-to-analyze.sql
\ir 023-add-crawl-config-target-host.sql
\ir 024-fix-fs-analysis-status-checks.sql
\ir 025-backfill-crawl-config-source-host.sql
\ir 026-create-remote-crawl-task-table.sql
\ir 027-crawl-simplification-fields.sql
\ir 028-create-discovery-tables.sql
\ir 029-reset-primary-sequence-to-standard.sql
\ir 030-drop-crawl-config-target-host.sql
\ir 031-add-fs-folder-baseline-manifest.sql
\ir 032-crawl-config-identity-refactor.sql
\ir 033-add-user-ownership.sql
\ir 034-seed-user-ownership.sql
\ir 035-add-discovery-classification-rules.sql
\ir 036-fix-discovered-folder-suggested-status-check.sql
\ir 037-add-semantic-pattern-columns-to-crawl-config.sql
\ir 038-add-crawl-config-pattern-priority-columns.sql
\ir 039-fix-smart-crawl-enabled-nullability.sql
\echo 'Done'
