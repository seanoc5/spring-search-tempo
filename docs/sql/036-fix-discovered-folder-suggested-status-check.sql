-- 036: Align discovered_folder.suggested_status check with SuggestedStatus enum.
-- Code allows: SKIP, LOCATE, INDEX, ANALYZE, SEMANTIC, UNKNOWN (and NULL).

-- Normalize any unexpected legacy values before tightening constraint.
UPDATE discovered_folder
SET suggested_status = 'UNKNOWN'
WHERE suggested_status IS NOT NULL
  AND suggested_status NOT IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC', 'UNKNOWN');

-- Remove any existing suggested_status check constraints on discovered_folder.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'discovered_folder'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%suggested_status%'
    LOOP
        EXECUTE format('ALTER TABLE discovered_folder DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE discovered_folder
    ADD CONSTRAINT discovered_folder_suggested_status_check
    CHECK (
        suggested_status IS NULL
        OR suggested_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC', 'UNKNOWN')
    );
