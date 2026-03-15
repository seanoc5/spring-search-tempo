# Spring Search Tempo Roadmap

This document outlines the development roadmap for Spring Search Tempo, a full-text search engine template with local file system crawling, metadata extraction, and configurable text processing.

---

## Vision & Goals

**Mission**: Provide a production-ready, modular template for building intelligent search applications over diverse data sources.

**Target Users**:
- "Power Users" who want more control over the search process _(and keep history of good/poor results, sites, authors,...)_
- Developers building search applications
- Teams implementing personal knowledge management
- Organizations needing enterprise content discovery
- Researchers organizing documents and datasets

**Core Values**:
- **Modularity**: Clean boundaries, event-driven architecture
- **Extensibility**: Easy to add new data sources and processors
- **Performance**: Efficient batch processing, optimized search
- **Developer Experience**: Clear documentation, tested patterns

---

## Current Status (v0.2.1)

**Version**: 0.2.1 - "Remote Crawling + Batch Observability"
**Last Updated**: 2026-03-14
**Active Phase**: Phase 2 - NLP Integration (95% complete)

### Phase 1 - Core Foundation ✅ COMPLETE (100%)

**All Phase 1 objectives achieved:**

- ✅ Domain model (FSFile, FSFolder, FSObject, ContentChunks)
- ✅ Spring Modulith architecture with module boundaries
- ✅ Apache Tika integration (400+ file format support)
- ✅ Metadata extraction (author, title, dates, page count, content type)
- ✅ File system crawling batch job with multi-crawl support
- ✅ Sentence-level content chunking
- ✅ PostgreSQL-safe text sanitization
- ✅ Basic web UI (Thymeleaf + HTMX)
- ✅ REST API endpoints (HATEOAS)
- ✅ Comprehensive testing (unit, integration)
- ✅ Documentation structure (guides, architecture, reference)
- ✅ **Crawl Configuration System** - YAML with pattern matching
- ✅ **Pattern Matching Service** - Hierarchical SKIP/LOCATE/INDEX/ANALYZE
- ✅ **SKIP Status Implementation** - Metadata persistence with UI filtering
- ✅ **SKIP Folder Optimization** - Children not enumerated (performance boost)
- ✅ **Incremental Crawling** - Timestamp-based change detection (90%+ faster)
- ✅ **PostgreSQL Full-Text Search** - FTS vectors, GIN indexes, ts_rank
- ✅ **Search API** - REST endpoints with pagination and ranking
- ✅ **Search Web UI** - Results with highlighting and snippets

### Production-Ready Features

**Full-Text Search Infrastructure:**
- PostgreSQL tsvector with weighted fields (title:A, metadata:B, body:C, label:D)
- GIN indexes for sub-100ms search performance
- Automatic FTS vector maintenance via database triggers
- Search function with ts_rank ranking and ts_headline snippets
- Three search modes: All content, Files only, Chunks only

**Search API Endpoints:**
- `GET /api/search?q={query}` - Search all content
- `GET /api/search/files?q={query}` - Search files with metadata
- `GET /api/search/chunks?q={query}` - Search content chunks
- Supports AND (&), OR (|), NOT (!) operators
- Pagination and relevance ranking

**Performance Optimizations:**
- Incremental crawl skips unchanged files (timestamp comparison)
- Pattern-based processing levels reduce unnecessary work
- Batch processing for large file sets
- Connection pooling and lazy loading

### Ready for Phase 2

Phase 1 foundation is complete. All success criteria met:
- [x] 400+ file formats supported
- [x] 10,000+ files can be indexed
- [x] Search results < 200ms
- [x] Test coverage > 70%
- [x] Zero critical vulnerabilities
- [x] Full-text search with ranking
- [x] Configurable crawl patterns
- [x] Incremental crawl working

**Next**: Complete Phase 2 integration work (auto-triggering, search integration, UI)

---

### Phase 2 - NLP Integration 🔄 IN PROGRESS (95%)

**Infrastructure Complete:**
- ✅ Stanford CoreNLP 4.5.5 dependency added
- ✅ `NLPService` interface + `StanfordNLPService` implementation
- ✅ Named Entity Recognition (NER) - Person, Organization, Location, Date, etc.
- ✅ Part-of-Speech (POS) tagging with lemmatization
- ✅ Sentiment analysis (sentence + document level)
- ✅ `NLPProcessingJobConfiguration` batch job
- ✅ `NLPChunkProcessor` for processing ContentChunks
- ✅ ContentChunk entity fields for NLP storage (namedEntities, nouns, verbs, sentiment, etc.)
- ✅ Email crawling infrastructure (IMAP service, entities, batch jobs)

**Triggering & API Complete:**
- ✅ `NLPAutoTriggerListener` - Auto-triggers NLP job after successful file crawl
- ✅ `NLPJobLauncher` - Service for launching NLP job programmatically
- ✅ REST API: `POST /api/nlp/process` - Manual NLP trigger
- ✅ REST API: `GET /api/nlp/status` - NLP status check
- ✅ UI endpoint: `POST /nlp/process` - Web UI trigger
- ✅ Configurable auto-trigger via `app.nlp.auto-trigger` (default: true)

**Integration Remaining:**
- [x] Add NLP fields to FTS vector (searchable nouns, verbs) ✅ 2025-12-11
- [x] REST API sentiment filter for chunk search ✅ 2025-12-11
- [ ] Search UI filters by sentiment, entity type (UI work)
- [x] Complete email crawl integration with CrawlOrchestrator ✅ 2026-03-15
- [x] Email-to-NLP pipeline (via NLPAutoTriggerListener) ✅ 2026-03-15

---

## Phase 1: Core Foundation ✅ COMPLETE (100%)

**Goal**: Establish solid foundation with file system crawling, text extraction, and basic search capabilities.

**Timeline**: Started 2025-10-15, Target Completion: 2025-11-15

### Completed Tasks

| Component                    | Status | Completion Date |
|------------------------------|--------|-----------------|
| Domain Model & JPA Entities  | ✅ Done | 2025-10-20      |
| Repository Layer             | ✅ Done | 2025-10-22      |
| Service Layer with DTOs      | ✅ Done | 2025-10-25      |
| Spring Modulith Setup        | ✅ Done | 2025-11-06      |
| Apache Tika Integration      | ✅ Done | 2025-11-07      |
| Metadata Extraction          | ✅ Done | 2025-11-07      |
| File System Crawl Job        | ✅ Done | 2025-11-05      |
| Content Chunking (Sentences) | ✅ Done | 2025-11-06      |
| Basic Web UI                 | ✅ Done | 2025-10-28      |
| REST API with HATEOAS        | ✅ Done | 2025-10-30      |
| Security (Basic Auth)        | ✅ Done | 2025-10-26      |

### All Tasks Completed ✅

| Task                                  | Status | Completion Date |
|---------------------------------------|--------|-----------------|
| Crawl Configuration Loader            | ✅ Done | 2025-11-07      |
| Pattern Matching Service              | ✅ Done | 2025-11-07      |
| Incremental Crawl Logic               | ✅ Done | 2025-11-07      |
| PostgreSQL FTS Setup                  | ✅ Done | 2025-11-07      |
| Search API Endpoints                  | ✅ Done | 2025-11-07      |
| Search Web UI                         | ✅ Done | 2025-11-07      |
| SKIP Status Implementation            | ✅ Done | 2025-11-14      |
| SKIP Folder Optimization (Reader)     | ✅ Done | 2025-11-14      |
| SKIP UI Filtering (FSFile & FSFolder) | ✅ Done | 2025-11-14      |

### Success Criteria - ALL MET ✅

- [x] All entities persisted successfully
- [x] Text extracted from PDF, DOCX, HTML, TXT (400+ formats via Tika)
- [x] Metadata captured (author, title, dates, content type, page count)
- [x] Files crawled and stored in database (multi-crawl support)
- [x] Content chunked at sentence level
- [x] Configurable crawl patterns working (YAML-based with hierarchical matching)
- [x] Incremental crawl detects changes (timestamp-based, 90%+ faster)
- [x] Full-text search returns ranked results (PostgreSQL FTS with ts_rank)
- [x] Module boundaries documented (ModularityTest disabled with rationale)
- [x] Test coverage > 70%

**Phase 1 Completed**: 2025-11-07 (2 days ahead of schedule)

---

## Phase 2: Advanced NLP Integration (IN PROGRESS - 90%)

**Goal**: Add linguistic analysis capabilities using Stanford CoreNLP for richer search and understanding.

**Timeline**: 2025-11-15 to 2026-01-15 (2 months)

### Completed Tasks ✅

#### 2.1 Stanford CoreNLP Setup ✅ DONE
- [x] Add CoreNLP dependencies (models for English)
- [x] Create NLPService for linguistic analysis
- [x] Configure pipeline (tokenization, POS tagging, NER, parsing, sentiment)
- [x] Implement batch processing integration
- **Completed**: 2025-12-01

#### 2.2 Named Entity Recognition (NER) ✅ DONE
- [x] Extract entities: Person, Organization, Location, Date, Money, etc.
- [x] Store entities in database (JSON field on ContentChunk)
- [x] Link entities to content chunks via NLPChunkProcessor
- [ ] Create entity search endpoints (REMAINING)
- **Completed**: 2025-12-05

#### 2.3 Part-of-Speech Tagging ✅ DONE
- [x] Tag tokens with POS labels
- [x] Store POS annotations on chunks (tokenAnnotations JSON field)
- [x] Extract and store nouns/verbs for quick access
- [ ] Enable grammatical pattern search (REMAINING)
- **Completed**: 2025-12-05

#### 2.4 Dependency Parsing ✅ Infrastructure Ready
- [x] Parse tree infrastructure (parseTree, parseUd, parseNpvp, conllu fields)
- [ ] Generate and store dependency parse trees
- [ ] Enable syntax-based search
- **Status**: Fields ready, processing not yet implemented

#### 2.5 Sentence Sentiment Analysis ✅ DONE
- [x] Analyze sentiment per sentence/chunk
- [x] Store sentiment scores (sentiment, sentimentScore fields)
- [ ] Filter search by sentiment (REMAINING - UI work)
- **Completed**: 2025-12-05

#### 2.6 Email Crawling Infrastructure ✅ DONE
- [x] EmailAccount, EmailFolder, EmailMessage entities
- [x] ImapConnectionService (Gmail, WorkMail, generic IMAP)
- [x] EmailQuickSync batch job
- [x] EmailTextExtractionService (HTML body processing)
- [x] Integration with CrawlOrchestrator (DailyEmailScheduler + EmailAutoTriggerListener)
- **Completed**: 2026-03-15

#### 2.7 Browser Data Integration (DEFERRED)
- [ ] Firefox bookmark indexing
- [ ] Firefox history indexing
- [ ] Chrome/Edge support
- **Status**: Deferred to Phase 2.5 or Phase 3

### Remaining Tasks for Phase 2 Completion

| Task | Priority | Effort | Description |
|------|----------|--------|-------------|
| ~~NLP Auto-Trigger~~ | ~~High~~ | ~~2-3h~~ | ~~Event listener to start NLP job after crawl~~ ✅ Done |
| ~~NLP Search Integration~~ | ~~High~~ | ~~4-5h~~ | ~~Add NLP fields to FTS, sentiment filters~~ ✅ Done |
| ~~Entity Search API~~ | ~~Medium~~ | ~~2-3h~~ | ~~REST endpoints for entity queries~~ ✅ Done (`/api/entities/*`) |
| ~~Email Orchestrator Integration~~ | ~~Medium~~ | ~~3-4h~~ | ~~Wire email jobs to CrawlOrchestrator~~ ✅ Done |
| Dependency Parse Processing | Medium | 3-4h | Extract and store parse trees |
| NLP Results UI | Low | 3-4h | Display sentiment, entities in search results |

### Technical Considerations

**Dependencies**:
```kotlin
// build.gradle.kts
dependencies {
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5")
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5:models")  // ~1GB
}
```

**Performance**:
- CoreNLP is CPU-intensive (~1-5 seconds per document)
- Process asynchronously via batch jobs
- Consider processing only ANALYZE-level files

**Storage**:
- NER entities: Separate table with M2M to chunks
- POS tags: JSON field on ContentChunks
- Parse trees: JSON field or compressed format

### Success Criteria

- [ ] NER extracts persons, orgs, locations with >85% accuracy
- [ ] POS tagging completes for all indexed documents
- [ ] Dependency parsing stores valid tree structures
- [ ] Sentiment scores match manual evaluation
- [ ] Browser history indexed and searchable
- [ ] NLP processing < 5 sec/document average
- [ ] Test coverage maintained > 70%

---

## Phase 2.5: Remote Crawling ✅ COMPLETE (100%)

**Goal**: Enable distributed crawling from remote hosts (Windows, Linux) with centralized policy management.

**Timeline**: 2026-01-15 to 2026-03-01

### Completed Tasks ✅

#### Remote Crawler CLI
- [x] Standalone fat JAR with Apache Tika for text extraction
- [x] Commands: `test`, `status`, `dry-run`, `crawl`, `onboard`
- [x] Server-driven classification (patterns evaluated server-side)
- [x] Discovery session support for new host onboarding
- [x] TLS support with custom truststore for LAN/self-signed certs
- **Completed**: 2026-02-15

#### Server-Side API
- [x] Bootstrap endpoint (`GET /api/remote-crawl/bootstrap?host=<hostname>`)
- [x] Classification endpoint (`POST /api/remote-crawl/classify`)
- [x] Session lifecycle (`start`, `heartbeat`, `ingest`, `complete`)
- [x] Task queue (`enqueue-folders`, `next`, `ack`, `status`)
- [x] Discovery upload and folder snapshot endpoints
- **Completed**: 2026-02-20

#### Windows Deployment
- [x] PowerShell scripts for scheduled task setup
- [x] Auto-update script (`update-remote-crawler.ps1`)
- [x] Config file and environment variable support
- [x] Documentation: Windows setup guide, TLS guide
- **Completed**: 2026-03-01

#### Release Automation
- [x] `release.sh` script for local builds and GitHub releases
- [x] GitHub Actions workflow (`publish-remote-crawler.yml`)
- [x] Hotfix versioning support (e.g., `0.5.3.1`)
- **Completed**: 2026-03-14

### Deferred Items

| Item | Priority | Description |
|------|----------|-------------|
| Staging tables | Low | Dedicated ingest staging instead of direct upsert |
| Path normalization | Low | UI-friendly path display for remote hosts |

---

## Phase 3: Semantic Search with Embeddings (Future - Q2 2025)

**Goal**: Enable semantic similarity search using vector embeddings and hybrid keyword+semantic ranking.

**Timeline**: 2026-01-15 to 2026-03-15 (2 months)

### Planned Features

#### 3.1 Vector Embedding Infrastructure
- [ ] Choose embedding model (Sentence-BERT, OpenAI, etc.)
- [ ] Add pgvector extension to PostgreSQL
- [ ] Create vector storage schema
- [ ] Implement embedding generation service
- [ ] **Effort**: 5 days
- [ ] **Target**: 2026-01-22

#### 3.2 Document-Level Embeddings
- [ ] Generate embeddings for entire documents
- [ ] Store in vector column (pgvector)
- [ ] Implement cosine similarity search
- [ ] Batch job for embedding generation
- [ ] **Effort**: 4 days
- [ ] **Target**: 2026-01-28

#### 3.3 Chunk-Level Embeddings
- [ ] Generate embeddings for sentences/paragraphs
- [ ] Store on ContentChunks table
- [ ] Enable fine-grained semantic search
- [ ] **Effort**: 3 days
- [ ] **Target**: 2026-02-03

#### 3.4 Hybrid Search Ranking
- [ ] Combine keyword (FTS) and semantic (vector) scores
- [ ] Implement ranking algorithms (RRF, weighted sum)
- [ ] Expose unified search API
- [ ] A/B testing framework for ranking
- [ ] **Effort**: 6 days
- [ ] **Target**: 2026-02-12

#### 3.5 Multi-Level Embeddings
- [ ] Hierarchical embeddings: document → paragraph → sentence
- [ ] Cross-level similarity search
- [ ] Enable "find similar paragraphs in different documents"
- [ ] **Effort**: 5 days
- [ ] **Target**: 2026-02-20

#### 3.6 Query Expansion & Reranking
- [ ] Use embeddings for query expansion
- [ ] Implement neural reranking
- [ ] Personalized search based on user history
- [ ] **Effort**: 7 days
- [ ] **Target**: 2026-03-03

### Technical Considerations

**Embedding Models**:
- **Sentence-BERT** (local): Free, good quality, ~300MB model
- **OpenAI Embeddings**: Best quality, costs ~$0.0001/1K tokens
- **Cohere/Voyage**: Specialized for search, moderate cost

**Storage**:
- pgvector for PostgreSQL (efficient vector indexing)
- Vector dimension: 384 (Sentence-BERT), 768 (BERT), 1536 (OpenAI)
- Index types: IVFFlat (fast), HNSW (more accurate)

**Performance**:
- Embedding generation: ~50-500ms per document
- Vector search: <100ms for millions of vectors (with proper indexing)
- Hybrid search: <200ms total

### Success Criteria

- [ ] Vector search returns semantically similar documents
- [ ] Hybrid search outperforms keyword-only by >15% (MRR metric)
- [ ] Embedding generation batch job completes overnight
- [ ] Search latency < 200ms for 95th percentile
- [ ] User satisfaction survey shows preference for semantic search
- [ ] Test coverage maintained > 70%

---

## Phase 4: Advanced Features & Production Hardening (Future - Q3 2025)

**Goal**: Production-ready deployment with advanced features, monitoring, and scalability improvements.

**Timeline**: 2026-03-15 to 2026-05-15 (2 months)

### Planned Features

#### 4.1 Database Strategy (REVISED)
- [x] JPA `ddl-auto: update` for rapid iteration (current approach)
- [x] `essential-postgres-features.sql` for PostgreSQL-specific features
- [ ] Production deployment scripts with backup/restore procedures
- [ ] **Status**: Current approach works well for RAD; revisit if needed for multi-environment deployments

#### 4.2 Advanced Search UI
- [ ] Faceted search (filter by author, date, type)
- [ ] Search suggestions/autocomplete
- [ ] Highlighted snippets
- [ ] Result previews
- [ ] **Effort**: 7 days

#### 4.3 User Management & Permissions
- [ ] User registration and profiles
- [ ] Role-based access control
- [ ] Personal vs shared content
- [ ] **Effort**: 5 days

#### 4.4 Monitoring & Observability
- [ ] Prometheus metrics export
- [ ] Grafana dashboards
- [ ] Application Performance Monitoring (APM)
- [ ] Logging aggregation (ELK stack)
- [ ] **Effort**: 5 days

#### 4.5 Scalability Improvements
- [ ] Database connection pooling (HikariCP tuning)
- [ ] Query optimization and indexing
- [ ] Caching layer (Redis/Caffeine)
- [ ] Async processing with message queues
- [ ] **Effort**: 6 days

#### 4.6 Email Indexing
- [ ] IMAP/POP3 email import
- [ ] Email parsing (sender, recipients, attachments)
- [ ] Thread reconstruction
- [ ] **Effort**: 5 days

#### 4.7 Cloud Storage Integration
- [ ] AWS S3 connector
- [ ] Google Drive connector
- [ ] Dropbox connector
- [ ] **Effort**: 6 days

### Success Criteria

- [ ] Zero-downtime deployments with Flyway migrations
- [ ] Search UI scores >4/5 in usability testing
- [ ] RBAC prevents unauthorized access
- [ ] Metrics tracked for all critical paths
- [ ] 95th percentile latency < 500ms under load
- [ ] Email and cloud storage content indexed successfully

---

## Phase 5: Microservices Migration (Optional - 2026+)

**Goal**: Extract modules into independent microservices if scale or organizational needs require.

**Prerequisites**:
- Multiple teams working on different modules
- Independent scaling requirements
- Clear module boundaries verified over 6+ months

### Migration Path

#### 5.1 Extract Core Service
- [ ] base module → core-service
- [ ] REST API for domain operations
- [ ] Database per service (or shared initially)
- [ ] **Effort**: 10 days

#### 5.2 Extract Batch Service
- [ ] batch module → batch-service
- [ ] Calls core-service API
- [ ] Independent scaling
- [ ] **Effort**: 8 days

#### 5.3 Event Bus Integration
- [ ] Replace @EventListener with Kafka/RabbitMQ
- [ ] Message schemas and versioning
- [ ] Retry and dead-letter queues
- [ ] **Effort**: 7 days

#### 5.4 Service Discovery & Gateway
- [ ] Spring Cloud Gateway or similar
- [ ] Service registry (Eureka, Consul)
- [ ] Load balancing
- [ ] **Effort**: 5 days

#### 5.5 Distributed Tracing
- [ ] OpenTelemetry integration
- [ ] Jaeger or Zipkin
- [ ] Cross-service request tracking
- [ ] **Effort**: 4 days

### Decision Points

**When to migrate**:
- ✅ Clear ownership boundaries between teams
- ✅ Independent deployment requirements
- ✅ Different scaling needs per module
- ✅ Stable module APIs for 6+ months

**When NOT to migrate**:
- ❌ Single team can handle all modules
- ❌ Deployment cadence is aligned
- ❌ Operational complexity outweighs benefits
- ❌ Module boundaries still evolving

---

## Experimental & Research Ideas

### Ideas Under Consideration

#### Graph Database Integration
- **Concept**: Model document relationships as knowledge graph
- **Tech**: Neo4j or Neptune
- **Use Case**: "Documents citing this paper", "Related concepts"
- **Status**: Research phase
- **Effort**: 10-15 days

#### Multi-Language Support
- **Concept**: Index and search documents in multiple languages
- **Tech**: Polyglot embeddings, language-specific stemmers
- **Use Case**: International content, translation search
- **Status**: Requirements gathering
- **Effort**: 8-12 days

#### OCR for Scanned Documents
- **Concept**: Extract text from images and scanned PDFs
- **Tech**: Apache Tesseract integration
- **Use Case**: Historical documents, scanned archives
- **Status**: Proof of concept
- **Effort**: 5-7 days

#### Audio/Video Transcription
- **Concept**: Extract transcripts from media files
- **Tech**: Whisper (OpenAI), Google Speech-to-Text
- **Use Case**: Podcast archives, video content
- **Status**: Idea phase
- **Effort**: 10-15 days

#### Deduplication & Near-Duplicate Detection
- **Concept**: Identify duplicate or very similar documents
- **Tech**: MinHash, SimHash, embedding similarity
- **Use Case**: Clean up redundant content
- **Status**: Research phase
- **Effort**: 6-8 days

#### Question Answering
- **Concept**: Answer questions directly from indexed content
- **Tech**: RAG (Retrieval-Augmented Generation), T5/BART
- **Use Case**: "What is the deadline mentioned in the contract?"
- **Status**: Idea phase
- **Effort**: 15-20 days

---

## Release Schedule

### Milestone Releases

#### v0.1.0 - "Foundation" (2025-11-15)
- **Features**: File crawling, text extraction, basic search
- **Status**: Phase 1 complete
- **Target**: 2025-11-15

#### v0.2.0 - "Intelligence" (2026-01-15)
- **Features**: NLP integration, browser data, advanced search
- **Status**: Phase 2 complete
- **Target**: 2026-01-15

#### v0.3.0 - "Semantic" (2026-03-15)
- **Features**: Vector embeddings, semantic search, hybrid ranking
- **Status**: Phase 3 complete
- **Target**: 2026-03-15

#### v1.0.0 - "Production Ready" (2026-05-15)
- **Features**: Hardened, scalable, production deployments
- **Status**: Phase 4 complete
- **Target**: 2026-05-15

### Patch Releases

- **Cadence**: As needed for bug fixes
- **Versioning**: SemVer (MAJOR.MINOR.PATCH)
- **Support**: Latest 2 minor versions

---

## Technology Radar

### Current Stack (Adopt)

- ✅ **Kotlin**: Primary language
- ✅ **Spring Boot 3.5**: Framework
- ✅ **Spring Modulith**: Modular architecture
- ✅ **PostgreSQL 18**: Database
- ✅ **Apache Tika**: Text extraction
- ✅ **Spring Batch**: Batch processing
- ✅ **Thymeleaf + HTMX**: Web UI
- ✅ **Testcontainers**: Integration testing

### Near-Term Additions (Trial)

- 🔄 **Stanford CoreNLP**: NLP processing (Phase 2)
- 🔄 **pgvector**: Vector search (Phase 3)
- 🔄 **Sentence-BERT**: Embeddings (Phase 3)
- 🔄 **Flyway**: Database migrations (Phase 4)

### Future Considerations (Assess)

- 🔮 **Redis**: Caching layer
- 🔮 **Kafka/RabbitMQ**: Message bus
- 🔮 **Elasticsearch**: Alternative to PostgreSQL FTS
- 🔮 **Neo4j**: Graph relationships
- 🔮 **OpenTelemetry**: Distributed tracing
- 🔮 **Tesseract**: OCR
- 🔮 **Whisper**: Audio transcription

### Hold (Not Planned)

- ⏸️ **NoSQL databases**: PostgreSQL sufficient for now
- ⏸️ **GraphQL**: REST + HATEOAS meets current needs
- ⏸️ **gRPC**: HTTP/JSON simpler for current use cases
- ⏸️ **Reactive programming**: Blocking I/O acceptable for batch

---

## Contributing to the Roadmap

### How to Propose Features

1. **Open GitHub Issue**: Use "Feature Request" template
2. **Describe Use Case**: What problem does it solve?
3. **Provide Examples**: How would users interact with it?
4. **Estimate Effort**: Rough sizing (S/M/L/XL)
5. **Discuss Trade-offs**: What are the costs/benefits?

### Prioritization Criteria

Features are prioritized based on:

1. **User Value**: Does it solve a real problem?
2. **Strategic Fit**: Aligns with product vision?
3. **Effort**: Cost vs benefit ratio
4. **Dependencies**: Blocks other work?
5. **Risk**: Technical or architectural risk
6. **Community Interest**: Number of upvotes/comments

### Decision Process

- **Minor features**: Project lead decides
- **Major features**: RFC document + community discussion
- **Architectural changes**: ADR (Architecture Decision Record)

---

## Success Metrics

### Key Performance Indicators

#### Phase 1 (Foundation)
- [x] 400+ file formats supported (via Tika)
- [ ] 10,000 files indexed in < 5 minutes
- [ ] Search results returned in < 200ms
- [x] Test coverage > 70%
- [x] Zero critical security vulnerabilities

#### Phase 2 (NLP)
- [ ] NER accuracy > 85%
- [ ] Browser history indexed (1000+ entries)
- [ ] NLP processing < 5 sec/document
- [ ] Test coverage maintained > 70%

#### Phase 3 (Semantic)
- [ ] Semantic search improves MRR by >15%
- [ ] Vector search latency < 100ms
- [ ] Embedding generation < 500ms/doc
- [ ] User preference for semantic search > 70%

#### Phase 4 (Production)
- [ ] 95th percentile latency < 500ms
- [ ] Zero-downtime deployments
- [ ] MTTR (Mean Time to Recovery) < 30 minutes
- [ ] Availability > 99.5%

### User Satisfaction

- **Target**: >4.0/5.0 average rating
- **Measurement**: In-app surveys, GitHub feedback
- **Frequency**: Quarterly

---

## Resources & Links

### Documentation
- [README](../README.md) - Project overview
- [CLAUDE.md](../CLAUDE.md) - AI assistant context
- [Architecture](./architecture/) - Design decisions
- [Guides](./guides/) - Step-by-step tutorials

### External Resources
- [Spring Boot Docs](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [Spring Modulith Docs](https://docs.spring.io/spring-modulith/reference/)
- [Apache Tika Docs](https://tika.apache.org/documentation.html)
- [Stanford CoreNLP](https://stanfordnlp.github.io/CoreNLP/)
- [pgvector](https://github.com/pgvector/pgvector)

### Community
- **GitHub Issues**: Bug reports and feature requests
- **Discussions**: Questions and ideas
- **Project Board**: Track progress

---

## Changelog

| Date       | Change                  | Author |
|------------|-------------------------|--------|
| 2025-12-11 | Updated Phase 2 status - NLP/Email infrastructure complete | Claude |
| 2025-11-07 | Initial roadmap created | Sean   |

---

**Last Updated**: 2025-12-11
**Version**: 1.1
**Status**: Living Document
