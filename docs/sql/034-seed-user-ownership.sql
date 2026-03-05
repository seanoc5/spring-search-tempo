-- Migration: Seed user ownership data
-- This is environment-specific - adjust user labels and sourceHosts as needed

BEGIN;

-- Example: Assign 'minti9' sourceHost to user 'sean'
INSERT INTO user_source_host (spring_user_id, source_host, date_created)
SELECT id, 'minti9', NOW() FROM spring_user WHERE label = 'sean'
ON CONFLICT (spring_user_id, source_host) DO NOTHING;

-- Example: Assign email accounts to sean
UPDATE email_account SET owner_user_id = (
    SELECT id FROM spring_user WHERE label = 'sean'
) WHERE email IN ('seanoc5@gmail.com', 'sean@oconeco.com', 'sean.oconnor@stvfd.org')
  AND owner_user_id IS NULL;

-- Example: Assign 'JocSamsung' sourceHost to user 'john'
INSERT INTO user_source_host (spring_user_id, source_host, date_created)
SELECT id, 'JocSamsung', NOW() FROM spring_user WHERE label = 'john'
ON CONFLICT (spring_user_id, source_host) DO NOTHING;

-- Example: Assign email account to john
UPDATE email_account SET owner_user_id = (
    SELECT id FROM spring_user WHERE label = 'john'
) WHERE email ilike 'john@oconeco.com'
  AND owner_user_id IS NULL;

-- Helper query to see current sourceHosts and suggest assignments:
SELECT DISTINCT source_host FROM crawl_config WHERE source_host IS NOT NULL ORDER BY source_host;

-- Helper query to see existing users:
SELECT id, label, email FROM spring_user ORDER BY id;

COMMIT;
