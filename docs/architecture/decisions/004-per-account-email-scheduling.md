# 004 — Per-account email sync scheduling

**Status:** Accepted
**Date:** 2026-06-01
**Issue:** [#2 — IMAP: Multi-account orchestration with per-account scheduling](https://github.com/seanoc5/spring-search-tempo/issues/2)

## Context

Spring Search Tempo supports multiple `EmailAccount` rows, but the existing
`EmailCrawlOrchestrator` dispatches a single ad-hoc flow across all enabled
accounts. There is no per-account cadence and no per-account failure
isolation: an error on one account can interfere with siblings, and every
account is forced onto the same global cron (`app.scheduling.email.cron`).

The "multiple IMAP accounts" feature requires:

- Each account synced on its own configured cadence.
- Failure in account A must not halt sync for account B.
- A manual trigger REST endpoint per account.
- A persisted `cronSchedule` per account (Flyway-managed column).

The v2 prototype's "two-pass sync" design
(`/opt/work/spring-search-tempo-v2/docs/research/legacy-keep-list.md`)
informs the per-job parameterization shape: `accountId` flows through
`JobParameters`, never through singleton state.

## Decision

### Scheduling mechanism: Spring `@Scheduled` minute tick + stored per-account cron

A single `@Scheduled(fixedDelayString = "PT1M")` bean
(`MultiAccountEmailScheduler`) ticks every minute and delegates to
`EmailCrawlOrchestrator.runDueAccounts(Instant.now())`.

`runDueAccounts` iterates enabled accounts and, for each:

1. Parses `account.cronSchedule` via Spring's `CronExpression`
   (already in use elsewhere in the project — `DailyEmailScheduler`,
   `DailyCrawlScheduler`).
2. Computes the next scheduled instant after `account.lastDispatchedAt`
   (or after `account.createdDate` if never dispatched).
3. If that instant is `<= now`, dispatches one `EmailQuickSyncJob`
   parameterized with `accountId` and records `lastDispatchedAt = now`
   so the next tick won't re-fire until the next cron boundary.

Each per-account dispatch is wrapped in its own try/catch so an exception
during dispatch for account A does not abort the loop for sibling
accounts. The job itself runs in its own Spring Batch transaction; failure
inside the job is already isolated at the batch level.

### Alternatives considered

- **Quartz** — purpose-built per-job scheduling with persisted triggers,
  but brings a substantial dependency, its own schema tables, and a
  scheduler thread pool. Its core strength is HA clustering, which is
  explicitly out of scope ("Distributed locking across multiple app
  instances").
- **ShedLock around a global `@Scheduled`** — solves the
  multiple-instances-racing-on-the-same-cron problem, but that problem
  doesn't exist yet (also out of scope), and ShedLock doesn't help with
  per-account cadence by itself — we would still need an inner loop
  over accounts.
- **`ScheduledTaskRegistrar` + dynamic per-account `Trigger` registration
  on entity create/update/delete** — closer to Quartz semantics, but
  requires bookkeeping every time a `cronSchedule` changes (or the app
  restarts), and concentrates scheduling logic in a registry that's
  harder to reason about than "tick + evaluate." A minute tick is
  precise enough for the cadences this project cares about (hourly,
  15-minute, daily); finer granularity isn't useful for IMAP polling.

### Flyway

This issue introduces Flyway to the project (previously planned for
Phase 4). The first migration adds the `cron_schedule` column to
`email_account`. To avoid breaking existing dev databases that were
built with `ddl-auto: update`, Flyway is configured with
`baseline-on-migrate=true` and `baseline-version=0`.

`ddl-auto: update` is kept active during the rapid-development phase;
Hibernate's `update` mode is additive only and will be a no-op for
columns Flyway has already created. New, intentional schema changes
should land as Flyway migrations going forward.

## Consequences

### Positive

- Per-account cadence via a single `cronSchedule` string on
  `EmailAccount`.
- Failure isolation at the per-account dispatch level (and naturally
  at the batch-job level below).
- Manual `POST /api/email/accounts/{id}/sync` reuses the same
  per-account dispatch path; no parallel code path.
- No new heavyweight dependency (no Quartz schema, no ShedLock).
- Persisting `lastDispatchedAt` makes startup catch-up trivial — if
  the most recent cron boundary is older than the stored value, we
  fire on the next tick.

### Negative

- Minute-resolution scheduling. Fine for IMAP cadences; not suitable
  for sub-minute work (we don't need it here).
- Single-instance assumption. If we ever run multiple replicas, we
  will need ShedLock (or equivalent) around `runDueAccounts` to
  prevent duplicate dispatch. Captured as a follow-up if/when
  horizontal scaling lands.
- Two scheduling beans temporarily coexist: the legacy
  `DailyEmailScheduler` (global cron) and the new
  `MultiAccountEmailScheduler` (per-account cron). `DailyEmailScheduler`
  is left in place for one release so existing
  `app.scheduling.email.cron` configurations don't silently stop
  firing; default for the new scheduler is enabled so new installs
  pick up per-account cadence immediately. A follow-up issue should
  remove `DailyEmailScheduler` once users have migrated.

## Acceptance criteria (from issue)

- [x] `EmailAccount` gains a `cronSchedule` field (default daily) +
  UI to edit it. Flyway migration, not `ddl-auto` alone.
- [x] `EmailCrawlOrchestrator` enumerates active accounts and
  dispatches one `EmailQuickSyncJob` per account, parameterized by
  `accountId`.
- [x] Per-account dispatch driven by stored cron.
- [x] Failure in account A does not halt sync for account B.
- [x] Manual trigger REST: `POST /api/email/accounts/{id}/sync`.
- [x] ADR documenting the scheduling choice (this file).
