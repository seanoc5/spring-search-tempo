# Legacy keep-list — patterns from spring-search-tempo worth considering

> Research notes harvested from a deep read of
> https://github.com/seanoc5/spring-search-tempo (the legacy view at the
> time of the v2 rewrite attempt). Each item is a **candidate** for
> future work, not a decision. Adoption requires an ADR under
> `docs/architecture/decisions/` that justifies the pattern on its own
> merits in *this* project. Do not lift these patterns blindly — write
> the ADR first.
>
> **Salvaged into this repo on 2026-06-01.** Several candidates are
> already in active use here (the legacy "v2" rewrite was reading
> *this* repo as legacy, and some good patterns were never actually
> rotten — they just needed an explicit ADR). Items already in use are
> marked `**Status (2026-06-01):**` with a pointer; even so, a
> retroactive ADR is the right way to ratify them.

## Two-pass sync (headers-only → full body) for IMAP

- **What.** First pass fetches only message headers (cheap, fast); a
  second pass fetches bodies on demand or as a follow-up job. State is
  tracked on the message so the system knows which pass it is in.
- **Why interesting.** Lets initial mail crawls finish in minutes instead
  of hours; bodies stream in as resources allow; resilient to
  interruption.
- **ADR question.** Is the two-pass model the contract for *every* source
  ("enumerate cheaply, enrich expensively"), or specific to IMAP?
- **Status (2026-06-01):** the four-level processing model
  (SKIP / LOCATE / INDEX / ANALYZE — see `../CONTEXT.md`) already
  expresses the "enumerate cheaply, enrich expensively" idea
  generically. The IMAP-specific two-pass mechanic is still candidate
  work pending the email-crawl orchestration in Phase 2.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Weighted full-text search index columns

- **What.** Indexed text stored in a generated column with per-field
  weighting (e.g. subject highest, sender mid, body lowest), driving
  relevance ranking directly from the database engine.
- **Why interesting.** Pushes ranking into the storage layer where it is
  fast and consistent; avoids a bespoke ranker in application code.
- **ADR question.** What is the ranking model (see `../CONTEXT.md` open
  question), and does the storage engine we pick support equivalent
  weighting?

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Boosted-request vs. fragment-response routing

- **What.** UI uses a single web framework but distinguishes between
  full-page requests, boosted partial-navigation requests, and inline
  fragment requests at the controller layer — each returns the right
  shape of HTML.
- **Why interesting.** Single template tree, no SPA build, SPA-grade UX
  for navigation and partial updates.
- **ADR question.** Is the boosted/fragment convention a project rule
  that every controller honours, or per-feature?
- **Status (2026-06-01):** the convention is documented in CLAUDE.md
  ("HTMX + Thymeleaf Response Shape") and applied across controllers,
  but not yet codified in an ADR.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Bulk insert path for crawl ingestion

- **What.** A dedicated bulk-write path for crawler output, separate from
  the per-item write path used elsewhere.
- **Why interesting.** Crawls produce thousands of records at once;
  per-row writes are an N+1 trap. A bulk path is essential at scale.
- **ADR question.** Where is the boundary — at what batch size does the
  system switch from per-item to bulk? How does it report partial
  failures?

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Service interface / implementation separation

- **What.** Services are defined as interfaces with one production
  implementation, so callers depend on the interface and tests can swap
  in alternatives.
- **Why interesting.** Cheap testability and a clear API surface per
  service.
- **ADR question.** Is this a blanket rule for every service, or only
  for services with external dependencies / replaceable strategies?
  (Universal interfaces can become ceremony.)
- **Status (2026-06-01):** already the working pattern (e.g.
  `FSFileService` interface in `base/service/`, implementation in
  `base/service/impl/`). Worth ratifying with an ADR that pins down
  *when* the interface split is required vs. optional.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Module-boundary verification tests

- **What.** A test that runs at build time and fails the build if a
  module imports something it is not allowed to import — module
  boundaries enforced by test, not convention.
- **Why interesting.** Architecture drift is detected automatically.
  This is a candidate L4/L5 guardrail: a fitness function rather than
  a code-review checklist.
- **ADR question.** What are the modules in this reimplementation, and
  what is each one *not* allowed to depend on?
- **Status (2026-06-01):** already in place via Spring Modulith
  (`./gradlew test --tests ModularityTest`); ADR-002 covers the
  modulith choice. This keep-list item is effectively closed —
  retained here as a marker that the legacy pattern was correct.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## ADRs as a habit (not just a folder)

- **What.** A `docs/architecture/decisions/` directory with numbered ADRs
  recording trade-offs, alternatives, and consequences for each load-
  bearing choice.
- **Why interesting.** Future-you (and future agents) need this to make
  reversible decisions. An L4/L5 workflow leans heavily on ADRs as the
  "argue with the spec" artifact.
- **ADR question.** *(This is meta.)* What is the ADR template, and
  what counts as load-bearing enough to require one?
- **Status (2026-06-01):** ADR habit is started — three ADRs on file
  (001 Kotlin, 002 Spring Modulith, 003 Apache Tika). A future
  "ADR-000" / template + load-bearing rubric would tighten the habit.

*Adopt only after writing the ADR that justifies it on its own merits in this project.* (Yes, including the ADR-about-ADRs.)

## JOIN-FETCH-named repository methods

- **What.** Repository methods that need related data are named to make
  their fetch shape explicit (e.g. `findByIdWithTags`), so callers know
  what is hydrated and templates do not trigger lazy-load surprises.
- **Why interesting.** Eliminates a whole class of runtime errors that
  show up only when a page renders.
- **ADR question.** Naming convention vs. compile-time guarantee (return
  a projection / DTO rather than the entity)?
- **Status (2026-06-01):** convention documented in CLAUDE.md
  ("JPA + Thymeleaf: Preventing LazyInitializationException") and
  applied in code (e.g. `findByIdWithParentAndCreatedBy`-style
  methods). The naming-vs-projection trade-off in the ADR question is
  still open.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*

## Modern-Kotlin-first build

- **What.** A build configured for current Kotlin tooling (Kotlin DSL
  build script, recent JVM target, annotation processor wired in).
- **Why interesting.** Cuts ceremony, gets idiomatic Kotlin straight from
  the build.
- **ADR question.** Stack choice belongs in ADR-001; this item is just
  evidence that the legacy stack was a reasonable starting point.
- **Status (2026-06-01):** in place — `build.gradle.kts`, Kotlin
  1.9.25, kapt wired. Covered by ADR-001.

*Adopt only after writing the ADR that justifies it on its own merits in this project.*
