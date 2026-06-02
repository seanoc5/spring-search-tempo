-- Reset all row state between integration tests (issue #46).
--
-- Pairs with `application-it.yml` setting `ddl-auto: update`, which (unlike
-- the previous `create-drop`) keeps every entity-mapped table alive for the
-- lifetime of the shared Testcontainers PostgreSQL container — eliminating
-- the cross-context schema-drop poisoning that caused 50+ flake failures in
-- a full `./gradlew :test` sweep. Because Hibernate no longer drops between
-- contexts, **every** table the suite ever populates must be cleared here;
-- enumerating that list by hand is fragile (new entities arrive often), so
-- this script discovers tables dynamically from `pg_tables` and TRUNCATEs
-- them all with CASCADE — letting PostgreSQL resolve FK order itself and
-- restarting identity sequences for deterministic row IDs in fixtures.
--
-- Note on Spring `ScriptUtils` parsing: Spring 6.2's splitter is not
-- dollar-quote aware, so `@Sql` in `BaseIT` configures the EOF-sentinel
-- separator. This script is forwarded to the PostgreSQL JDBC driver as one
-- string; PostgreSQL parses the `$$ ... $$` boundary correctly.

DO $$
DECLARE
    tnames text;
BEGIN
    SELECT string_agg(quote_ident(schemaname) || '.' || quote_ident(tablename), ', ')
    INTO tnames
    FROM pg_tables
    WHERE schemaname = 'public';

    IF tnames IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || tnames || ' RESTART IDENTITY CASCADE';
    END IF;
END $$;
