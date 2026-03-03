-- 029-reset-primary-sequence-to-standard.sql
-- Revert primary sequence behavior to standard increment-by-1 semantics.
-- Keep this migration after 020-multi-host-support.sql.

ALTER SEQUENCE IF EXISTS primary_sequence INCREMENT BY 1;
