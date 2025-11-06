---
description: Execute PostgreSQL query and show results
---

Connect to PostgreSQL and execute query.

**Query:** {{query}}

**Connection:**
```bash
psql -h localhost -p 5433 -U postgres -d spring_search_tempo
```

**If no query provided, show dashboard:**
```sql
-- Entity counts
SELECT
  'FSFile' as entity, COUNT(*) as count FROM fs_file
UNION ALL SELECT
  'FSFolder', COUNT(*) FROM fs_folder
UNION ALL SELECT
  'ContentChunks', COUNT(*) FROM content_chunks
UNION ALL SELECT
  'SpringUser', COUNT(*) FROM spring_user
UNION ALL SELECT
  'Annotation', COUNT(*) FROM annotation;

-- Recent files
SELECT uri, last_modified
FROM fs_file
ORDER BY last_modified DESC
LIMIT 10;

-- Database size
SELECT pg_size_pretty(pg_database_size('spring_search_tempo')) as db_size;
```

**Format results as table** for readability.
