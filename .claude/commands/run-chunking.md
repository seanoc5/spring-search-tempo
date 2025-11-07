# Run Chunking Step

You are tasked with running ONLY the chunking step of the batch job.

## Context
- The file crawling has already completed
- There are 429 files in the database with bodyText
- The chunking step should process these files and create ContentChunks

## Your Task

1. First, check the current state:
   ```sql
   SELECT COUNT(*) as files_with_text FROM fsfile WHERE body_text IS NOT NULL AND LENGTH(body_text) > 0;
   SELECT COUNT(*) as existing_chunks FROM content_chunks;
   ```

2. Start the Spring Boot application with a modified configuration that:
   - Disables the full crawl job
   - Runs ONLY the chunking step on existing files

3. After completion, verify the results:
   ```sql
   SELECT COUNT(*) as total_chunks FROM content_chunks;
   SELECT chunk_type, COUNT(*) as count FROM content_chunks GROUP BY chunk_type;
   SELECT concept, COUNT(*) as chunks_per_file FROM content_chunks GROUP BY concept ORDER BY chunks_per_file DESC LIMIT 10;
   ```

4. Report:
   - Number of files processed
   - Number of chunks created
   - Any errors encountered
   - Distribution of chunk types (Sentence, Paragraph, LongSentence)

## Important
- Do NOT run the full crawl job (files and folders steps)
- ONLY process files that already exist in the database
- The chunking code is already implemented in:
  - ChunkReader.kt
  - ChunkProcessor.kt
  - ChunkWriter.kt
  - FsCrawlJobBuilder.kt (integrated into job)
