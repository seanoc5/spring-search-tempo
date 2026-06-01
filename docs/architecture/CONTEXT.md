# CONTEXT — spring-search-tempo

> Unbiased domain overview. Describes **what the system is for**, not how
> any prior implementation builds it. Do not infer architecture from this
> document — that lives in ADRs and the codebase.
>
> **Provenance.** Salvaged from the stalled `spring-search-tempo-v2`
> "dark-factory" rewrite (docs-only, no code) on 2026-06-01 to serve as
> the shared vocabulary and guardrail catalog for ongoing work in this
> repository. Open questions have been reconciled against the current
> state of the parent repo; resolved items are marked inline with the
> resolution date and ADR pointer.
>
> **Adoption rule for legacy keep-list patterns.** Anything in
> `research/legacy-keep-list.md` is a *candidate*, not a decision.
> Adopting one of those patterns into this codebase requires an ADR
> under `docs/architecture/decisions/` first — the keep-list note is
> the prompt, not the approval.

## Problem

A **personal, self-hosted, multi-source search engine**: indexes a single
person's readable content, eg:
- files
- emails
- browser history, cached page content, bookmarks/tags
- git hub projects (code, issues, PRs, comments)
- cloud docs (google drive, onedrive,...)

This app makes any/all content *full-text searchable* with optional **NLP enrichment**
(entities, sentiment, POS).

Additionally, this can (should?) be used
as a well controlled and gated tool for explicit and intentional gating and guardrails
for **LLM agents** (via mcp and/or old-school api) to access content in a trace-able and controllable manner.

Targets people who want Spotlight/Recoll-class
search but private, programmable, and able to span sources Spotlight does
not reach _(or heathens like me that love gnu/linux but don't like Mac OSx)_.

For those looking to advance in the LLM automation tiers (i.e. [vibe coding level 3, 4, and 5](https://www.danshapiro.com/blog/2026/01/the-five-levels-from-spicy-autocomplete-to-the-software-factory/))
this can be a great tool with the rigor of spring boot and all it's "glory"
along with Full-text search AND semantic search.

## Target users

- General users who want an advanced search platform that combines many different
  content sources into one unified repository and search interface
- Power users on Linux / macOS / Windows wanting a private alternative to
  Spotlight, Windows Search, or cloud search (Google, Microsoft 365).
- Privacy-conscious individuals — no content leaves the host.
- Knowledge workers (developers, researchers, writers) with mixed-source
  archives (docs, code, mail, bookmarks) needing unified recall.
- Small teams / regulated orgs that cannot upload content to cloud indexes.

## Capabilities

- **Multi-source ingestion** from local filesystem, IMAP mailboxes,
  browser bookmark stores, and cloud document stores.
- **Configurable per-source processing depth** (see Processing Levels
  below) so the user trades index richness against time and disk.
- **Incremental crawling** — first pass is comprehensive; subsequent
  passes only touch changed content.
- **Overlap-aware crawling** — multiple crawl configurations may cover
  the same content; the system de-duplicates work across overlapping
  configs based on a configurable freshness window.
- **Full-text search** over indexed content, with relevance ranking,
  result highlighting, and source/type filtering.
- **NLP enrichment** — named entities (people, orgs, locations, dates,
  money), part-of-speech tags, lemmatisation, sentiment, dependency
  parses. Enrichment runs as a follow-on step that can be auto-triggered
  after a crawl or invoked manually.
- **Web UI + HTTP API** — a local web interface for browsing and
  searching, plus a programmatic API for integration with other tools.
- **Job control & observability** — long-running crawls and enrichment
  jobs can be monitored, paused, and resumed.
- **Packaged distribution** — installable as a native application on at
  least one major OS.

## Domain language (glossary)

| Term                            | Meaning                                                                                                                                                                             |
|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Tempo**                       | Project name. Portmanteau of *tempus* (time) and *template* — the system is time-aware (incremental, freshness-aware) and meant to be a substrate for personal search applications. |
| **Source**                      | A category of crawlable content (filesystem path, IMAP account, browser profile, cloud drive).                                                                                      |
| **Crawl**                       | The act of enumerating items from a source and applying processing.                                                                                                                 |
| **Crawl configuration**         | A user-defined declaration: which source, which subset, what processing level per item pattern, how often, what freshness window.                                                   |
| **Processing level**            | Per-item depth of work. Four levels: **SKIP**, **LOCATE**, **INDEX**, **ANALYZE**.                                                                                                  |
| &nbsp;&nbsp;SKIP                | Do not enumerate or process this item or anything beneath it.                                                                                                                       |
| &nbsp;&nbsp;LOCATE              | Record existence + metadata only; do not extract content.                                                                                                                           |
| &nbsp;&nbsp;INDEX               | LOCATE + extract textual content and prepare it for search.                                                                                                                         |
| &nbsp;&nbsp;ANALYZE             | INDEX + run NLP enrichment over the extracted content.                                                                                                                              |
| **Chunk**                       | A sub-document unit (typically a sentence or paragraph) that carries extracted text and any NLP annotations. The atomic unit of search results and NLP work.                        |
| **Incremental crawl**           | A re-crawl that only touches items whose change-detection signal (timestamp, size, hash) differs from the recorded state.                                                           |
| **Overlapping crawl detection** | When two crawl configs cover the same items, the system avoids re-processing items already handled within the freshness window by another config.                                   |
| **Freshness window**            | Per-config time interval inside which an already-processed item is considered fresh enough to skip.                                                                                 |
| **NLP auto-trigger**            | After a successful INDEX crawl, automatically launch ANALYZE for newly indexed chunks. Configurable.                                                                                |

## Sources (initial set)

The first-pass source matrix the system aims to cover. Order is rough
priority; each is independent and can ship separately.

1. **Local filesystem** — broad file-format coverage (documents,
   spreadsheets, presentations, code, configuration, logs, archives).
2. **IMAP mailboxes** — headers + bodies; two-pass sync (cheap headers
   first, full bodies on demand or follow-up).
3. **Browser bookmarks and history** — local browser profile stores.
4. **Cloud document stores** — at least one (e.g. OneDrive, Google Drive).

## What is unclear / still to decide

These are flagged so they can be addressed in `grill-with-docs` or ADR
sessions — they are *intentionally unresolved* in this document **unless
otherwise marked**. Items that have since been decided in the parent
repo carry a `**Resolved**` line with date and pointer.

- **Scale envelope.** Target item-count, total-text-size, host memory,
  and acceptable first-crawl time. Drives many architecture choices.
- **Ranking model.** Pure relevance, recency-weighted, type-weighted,
  hybrid with semantic search, or user-tuneable?
- **NLP presentation.** How sentiment / entities / topics surface in
  search results and item detail views.
  - **Partially resolved (2026-06-01).** Phase 2 wires Stanford CoreNLP
    enrichment into `ContentChunk` (entities, POS, sentiment, dependency
    parse) and the NLP job can auto-trigger after a file crawl. The
    storage shape is decided; the UI surfacing (badges, entity links)
    is still open work (see `CLAUDE.md` "Phase 2 Remaining").
- **Source plugin contract.** Whether sources are first-class plugins
  with a stable contract (so a new source can be added without touching
  core) or each source is bespoke.
- **Deletion / retention.** How content removal propagates from source
  to index; whether there is a retention policy for old chunks.
- **Multi-host.** Whether two installs on different machines can share
  / federate an index, or each install is strictly single-host.
- **Semantic / vector search.** Whether vector embeddings are part of
  the first shipped version or a later phase.
  - **Direction set (2026-06-01).** pgvector + HNSW indexes are provisioned
    in `docs/sql/essential-postgres-features.sql` and CLAUDE.md flags
    "semantic search with pgvector embeddings" as the next phase
    (post Phase 2). Embedding pipeline and ranking interplay with FTS
    are still open and warrant a future ADR.
- **Auth model.** Single-user local install vs. small-team install with
  per-user views.
  - **Resolved as single-user (2026-06-01).** Current `SecurityConfig`
    is HTTP basic auth with a single `user`/`password` credential
    (see CLAUDE.md "Security"). Multi-user is explicitly out of scope
    for the current phase.

## Non-goals (for now)

- Public / multi-tenant SaaS deployment.
- Real-time push indexing (e.g. filesystem watch-style invalidation) —
  the system is pull/crawl-driven.
- Replacing the search inside any specific source's native UI (Gmail
  search, IDE code search, etc.).

## Architectural decisions on file

The load-bearing choices below have ADRs in
`docs/architecture/decisions/`. Each resolves a question that would
otherwise live in the open-questions list above; consult the ADR
before relitigating.

- **ADR-001** — Kotlin as the JVM language.
  ([decisions/001-use-kotlin.md](decisions/001-use-kotlin.md))
- **ADR-002** — Spring Modulith for modular monolith boundaries.
  ([decisions/002-spring-modulith.md](decisions/002-spring-modulith.md))
- **ADR-003** — Apache Tika for text extraction across 400+ formats.
  ([decisions/003-apache-tika.md](decisions/003-apache-tika.md))

## Known divergence from the v2 research notes

The research notes in `research/legacy-smells.md` propose **Flyway
migrations from `V1` onward** as a CI-gateable guardrail. This repo
deliberately uses `spring.jpa.hibernate.ddl-auto: update` plus
`docs/sql/essential-postgres-features.sql` for PostgreSQL-specific
features (FTS, pgvector, materialised views) during rapid development
(see CLAUDE.md "Database"). The schema-migration question is therefore
**still open** here, not closed by adopting Flyway — a future ADR
should either ratify the current approach for the project's phase or
introduce Flyway with a migration plan.

## References

- Upstream prior art: https://github.com/seanoc5/spring-search-tempo
- Related research notes: `research/legacy-keep-list.md`,
  `research/legacy-smells.md`
- Source v2 stub (now archived):
  `/opt/work/spring-search-tempo-v2/CONTEXT.md`
