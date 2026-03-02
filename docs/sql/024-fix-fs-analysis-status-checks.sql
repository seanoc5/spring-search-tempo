-- 024: Align fsfolder/fsfile analysis_status constraints with application enum.
-- Legacy schema used IGNORE; current code uses SKIP.

-- Normalize any legacy values before tightening constraints.
UPDATE fsfolder SET analysis_status = 'SKIP' WHERE analysis_status = 'IGNORE';
UPDATE fsfile SET analysis_status = 'SKIP' WHERE analysis_status = 'IGNORE';


ALTER TABLE fsfolder
    DROP CONSTRAINT IF EXISTS fsfolder_analysis_status_check;

ALTER TABLE fsfolder
    ADD CONSTRAINT fsfolder_analysis_status_check
    CHECK (analysis_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC'));

ALTER TABLE fsfile
    DROP CONSTRAINT IF EXISTS fsfile_analysis_status_check;

ALTER TABLE fsfile
    ADD CONSTRAINT fsfile_analysis_status_check
    CHECK (analysis_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC'));
