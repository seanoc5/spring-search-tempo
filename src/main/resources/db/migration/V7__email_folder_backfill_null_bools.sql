-- V7: Backfill NULLs and tighten the Boolean columns on email_folder.
--
-- Background: PR #17 added a batch of Kotlin non-null primitive Boolean
-- fields to EmailFolder (hasChildren, noinferiors, noselect, marked,
-- unmarked, subscribed, archived, skipDetected). The columns were
-- auto-created by ddl-auto without NOT NULL / DEFAULT, so pre-PR rows
-- carry NULL. Hibernate then trips on hydration:
--     "Null value was assigned to a property [..hasChildren] of
--      primitive type"
-- which breaks any operation that loads one of those rows (e.g.
-- per-account quick-sync of seanoc5@gmail.com).
--
-- V4 attempted this backfill but only ended up adding sync_enabled.
-- This migration finishes the job for the eight remaining Boolean
-- columns: backfill NULLs to FALSE, then enforce NOT NULL DEFAULT FALSE
-- so the bug cannot recur after a fresh-schema deploy.

UPDATE email_folder
   SET has_children   = COALESCE(has_children,   FALSE),
       noinferiors    = COALESCE(noinferiors,    FALSE),
       noselect       = COALESCE(noselect,       FALSE),
       marked         = COALESCE(marked,         FALSE),
       unmarked       = COALESCE(unmarked,       FALSE),
       subscribed     = COALESCE(subscribed,     FALSE),
       archived       = COALESCE(archived,       FALSE),
       skip_detected  = COALESCE(skip_detected,  FALSE)
 WHERE has_children  IS NULL
    OR noinferiors   IS NULL
    OR noselect      IS NULL
    OR marked        IS NULL
    OR unmarked      IS NULL
    OR subscribed    IS NULL
    OR archived      IS NULL
    OR skip_detected IS NULL;

ALTER TABLE email_folder
    ALTER COLUMN has_children  SET DEFAULT FALSE,
    ALTER COLUMN has_children  SET NOT NULL,
    ALTER COLUMN noinferiors   SET DEFAULT FALSE,
    ALTER COLUMN noinferiors   SET NOT NULL,
    ALTER COLUMN noselect      SET DEFAULT FALSE,
    ALTER COLUMN noselect      SET NOT NULL,
    ALTER COLUMN marked        SET DEFAULT FALSE,
    ALTER COLUMN marked        SET NOT NULL,
    ALTER COLUMN unmarked      SET DEFAULT FALSE,
    ALTER COLUMN unmarked      SET NOT NULL,
    ALTER COLUMN subscribed    SET DEFAULT FALSE,
    ALTER COLUMN subscribed    SET NOT NULL,
    ALTER COLUMN archived      SET DEFAULT FALSE,
    ALTER COLUMN archived      SET NOT NULL,
    ALTER COLUMN skip_detected SET DEFAULT FALSE,
    ALTER COLUMN skip_detected SET NOT NULL;
