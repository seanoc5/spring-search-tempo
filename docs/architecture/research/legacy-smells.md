# Legacy smells — patterns from spring-search-tempo to deliberately avoid

> Research notes harvested from a deep read of
> https://github.com/seanoc5/spring-search-tempo (the legacy view at the
> time of the v2 rewrite attempt). Each item pairs a concrete smell
> observed in the legacy code with a **guardrail** for new work.
>
> Guiding principle: **L4/L5 means the guardrail catches this in CI,
> not in code review.** A rule that exists only in a style guide is not
> a rule; it is a hope.
>
> **Salvaged into this repo on 2026-06-01.** Some smells have since been
> addressed in this codebase — those are marked `**Status (2026-06-01):**`
> with the resolution and a pointer. The rest remain open; treat them
> as a guardrail backlog.

## Controller bloat

- **Smell.** Single controller classes reached ~1670 LOC and ~66 methods,
  mixing routing, validation, orchestration, and view-model assembly.
- **Why it hurts.** Untestable in isolation; merge conflicts on every
  change; reasoning surface too large for a human or an agent.
- **Guardrail.** Hard limits enforced by static analysis: controller
  class size ≤ 250 LOC, ≤ 10 public endpoints. Orchestration lives in
  services; view-model assembly lives in a dedicated view-model layer.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Presentation logic in templates

- **Smell.** Hundreds of conditional branches in templates assembling
  status badges, labels, and CSS classes from raw entity state.
- **Why it hurts.** Templates have no compile-time check; logic is
  invisible to tests; designers and developers fight over the same file.
- **Guardrail.** Templates may only bind values. Any branching beyond
  presence/absence belongs in a view-model property computed in code,
  covered by a unit test. A custom lint (or a count-based CI gate on
  template conditionals) flags drift.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Event-handler attributes with string arguments

- **Smell.** Templates emit inline event handlers with string-interpolated
  arguments — violates the framework's own safety rules and the project
  CLAUDE.md.
- **Why it hurts.** Template-rendering exceptions at runtime; XSS surface;
  refactor-hostile.
- **Guardrail.** Lint rule that fails the build on any string-typed
  expression inside an event-handler attribute. Approved patterns:
  HTMX attributes, or `data-*` + delegated JS.
- **Status (2026-06-01):** documented as a hard rule in this repo's
  `CLAUDE.md` ("Thymeleaf: No Strings in th:on* Event Handlers"). The
  *CI gate* is still missing — the rule is enforced by review and by
  CLAUDE.md, not by a failing build. Open guardrail.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Java streams used inside Kotlin code

- **Smell.** Java `.stream().collect(...)` chains where idiomatic Kotlin
  collection operations exist.
- **Why it hurts.** Two collection paradigms in one file; harder to
  read; obscures intent.
- **Guardrail.** Detekt rule banning `java.util.stream` imports outside
  a small allow-list (e.g. genuine parallel-stream cases). Default deny.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Inconsistent logger variable naming

- **Smell.** Mix of `logger` and `log` across files; mix of factory call
  styles.
- **Why it hurts.** Grep tax compounds over years; agents inherit
  whichever they last saw.
- **Guardrail.** Detekt custom rule (or `ktlint` config) requiring the
  variable name `log`. CI fails on any other name.
- **Status (2026-06-01):** convention is documented globally
  (user-level CLAUDE.md: "use the variable name `log` rather than
  `logger`"). CI gate not yet in place. Open guardrail.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Anemic domain + bloated services

- **Smell.** Entities are data bags; all behaviour lives in service
  classes that grow without bound.
- **Why it hurts.** Invariants drift across services; "where does this
  rule live?" has no clear answer; agents synthesise plausible but
  inconsistent rules.
- **Guardrail.** Convention: domain rules belong on the entity or a
  domain service in the same package. Services orchestrate transactions
  and external calls; they do not contain rules. Track service-class
  LOC as a fitness metric.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Repository sprawl

- **Smell.** Each screen acquires its own bespoke finder method on the
  repository, so repositories accumulate dozens of nearly-identical
  queries.
- **Why it hurts.** No reuse; subtle divergence between similar queries;
  caching and indexing strategies fragment.
- **Guardrail.** A unified search-criteria / specification mechanism is
  the default for new finders. Bespoke finders allowed only when the
  query is provably faster or simpler — and require a comment citing
  the benchmark or the simplification.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Trust-all TLS for external integrations

- **Smell.** `mail.imaps.ssl.trust = "*"` (and equivalents) in
  configuration, accepting any TLS certificate.
- **Why it hurts.** Silent MITM exposure for credentials and content.
- **Guardrail.** Never accept wildcard trust. Explicit trust store with
  pinned roots; integration tests use a local CA. CI greps for the
  wildcard string and fails.
- **Status (2026-06-01):** addressed for the IMAP path — recent commit
  `b5d2707` ("TLS probe: use JVM default trust store, not trust-all")
  removed the wildcard trust. CI grep gate not yet added. Verify the
  wildcard is gone from `application.yml` before closing.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## Missing fetch discipline for template-accessed relations

- **Smell.** Templates access lazy-loaded associations whose hydration
  is not guaranteed by the controller, producing intermittent
  `LazyInitializationException` in production.
- **Why it hurts.** Bug only appears under the right cache/session
  timing; trivially missed in unit tests.
- **Guardrail.** Every page-rendering endpoint has an integration test
  that renders the template against a clean session and asserts a 200.
  This catches missing fetches by construction.
- **Status (2026-06-01):** convention codified in CLAUDE.md
  ("JPA + Thymeleaf: Preventing LazyInitializationException" — use
  `findByIdWith*()` plus JOIN FETCH). The *systematic* render-200
  integration test is the missing piece. Open guardrail.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## No CI, no formatter, no static analysis

- **Smell.** Only a narrow publish workflow in CI; no test run, no
  format check, no static analysis on PRs.
- **Why it hurts.** Style and quality drift; every PR re-debates the
  same conventions; agents have no signal.
- **Guardrail.** Day-one CI: `spotless` or `ktfmt` for formatting,
  `detekt` for static analysis, `jacoco` for coverage with a minimum
  threshold, plus the full test run. PRs are blocked until green.

*L4/L5 means the guardrail catches this in CI, not in code review.*

## No schema migration tool

- **Smell.** Schema evolves with the entity classes; dev and prod
  diverge silently.
- **Why it hurts.** Production deploys break on schema drift; rollbacks
  become irreversible.
- **Guardrail.** Flyway from migration `V1` onward, idempotent SQL
  (`CREATE INDEX IF NOT EXISTS`, no `CONCURRENTLY` per CLAUDE.md).
  Integration tests run against the migrated schema, not against a
  Hibernate-generated one.
- **Status (2026-06-01):** *deliberately not adopted yet.* This repo
  currently runs `spring.jpa.hibernate.ddl-auto: update` plus
  `docs/sql/essential-postgres-features.sql` for FTS / pgvector
  features (see CLAUDE.md "Database"). The trade-off is rapid-dev
  velocity vs. production rollback safety. A future ADR should either
  ratify ddl-auto for the current phase or introduce Flyway with a
  migration plan. **Do not silently switch.**

*L4/L5 means the guardrail catches this in CI, not in code review.*

## No CONTEXT.md, no ADR habit beyond the very first

- **Smell.** Single ADR (stack choice) and no living glossary of domain
  language; the rest of the architecture is implicit in the code.
- **Why it hurts.** New contributors (human or agent) must reverse-
  engineer intent from the code, and the code is what we are trying not
  to inherit.
- **Guardrail.** `CONTEXT.md` exists and is the source of truth for
  domain language. ADRs are required for any load-bearing decision;
  a CI check fails any PR that modifies a flagged area (e.g.
  `src/main/.../search/`) without touching `docs/architecture/decisions/`.
- **Status (2026-06-01):** partially closed — `docs/architecture/CONTEXT.md`
  now exists (this very salvage ticket) and three ADRs are on file
  (001 Kotlin, 002 Spring Modulith, 003 Apache Tika). The CI gate
  (PR-touches-flagged-area → must touch ADRs) is still missing.

*L4/L5 means the guardrail catches this in CI, not in code review.*
