-- 000-consolidated-migrations.sql
-- Single entrypoint to apply all incremental SQL migrations in order.
--
-- Usage:
--   PGPASSWORD=password psql -h <host> -p <port> -U postgres -d tempo -f docs/sql/000-consolidated-migrations.sql

\set ON_ERROR_STOP on

\echo 'Applying SQL migrations 001..024'
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
\echo 'Done'
