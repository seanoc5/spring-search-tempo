# 005 — IMAP mailbox mirroring via APPEND with Message-ID dedup

**Status:** Proposed
**Date:** 2026-06-01
**Issues:** [#22](https://github.com/seanoc5/spring-search-tempo/issues/22) (foundation),
[#23](https://github.com/seanoc5/spring-search-tempo/issues/23) (`ImapMirrorService`),
[#24](https://github.com/seanoc5/spring-search-tempo/issues/24) (`MirrorJob`),
[#25](https://github.com/seanoc5/spring-search-tempo/issues/25) (dry-run),
[#26](https://github.com/seanoc5/spring-search-tempo/issues/26) (progress dashboard)

## Context

The IMAP integration shipped in PRs #12–#18 reads from configured `EmailAccount`s
for indexing. It does not write to a destination mailbox. A concrete use case
has emerged from this session: the project owner is migrating personal email
infrastructure from GoDaddy + Amazon WorkMail onto Cloudflare email routing +
Gmail. Spring Search Tempo already has the IMAP stack (connection, auth,
folder enumeration, scheduling) — what's missing is a *write* path that copies
messages losslessly from one IMAP account to another.

The mirror feature is also generally useful beyond this specific migration:
the project can serve as both a personal search engine *and* a controlled
migration tool when an account move is needed.

Requirements:

- **Lossless** — original RFC822 body (including attachments), `Message-ID`,
  `INTERNALDATE`, and IMAP flags (`\Seen`, `\Flagged`, `\Answered`, etc.) all
  preserved on the destination.
- **Idempotent** — re-running a mirror job after interruption (or just
  re-running it deliberately) must not produce duplicate messages on the
  destination.
- **Resumable** — a mid-folder crash should pick up from the last copied
  message, not from the start of the folder.
- **Streamable** — never load a full mailbox or even a full large message
  into a JVM byte array; large attachments must flow as `InputStream`.

## Decision

**Use IMAP `APPEND` of the raw RFC822 octets from the source, with idempotency
keyed on the `Message-ID` header.**

For each `(sourceFolder → destFolder)` pair in a `MirrorConfig`:

1. FETCH source message UID + `Message-ID` header.
2. Look up `MirroredMessage(mirrorConfigId, messageId)` in our DB.
3. If found → skip; this message has already been mirrored under this config.
4. Otherwise, FETCH `BODY[]` + `INTERNALDATE` + `FLAGS` from source.
5. `APPEND` to the destination folder, passing the original `INTERNALDATE`
   and `FLAGS` as APPEND arguments (RFC 3501 §6.3.11).
6. Record `MirroredMessage(mirrorConfigId, sourceUid, sourceFolder, destUid,
   destFolder, messageId, mirroredAt = now())`.

Folder mapping is configurable per `MirrorConfig`. Default mapping is
name-based identity (`INBOX → INBOX`, `Sent → Sent`, custom folders by
display name). The user may override or disable any mapping in the UI.

### Alternatives considered

- **External tool shellout (`imapsync`, `gmvault`)** — purpose-built and
  battle-tested. Rejected: adds an external binary dependency, breaks the
  dark-factory containment, and complicates progress/checkpoint observability.
  The IMAP stack already exists in-process; reuse beats shellout.
- **Local mbox archive + re-upload** — fully decouples source unavailability
  from destination availability. Adds an intermediate storage requirement and
  doubles the I/O. Worth a follow-up ADR if/when *offline* migration becomes
  a use case (e.g., source mailbox being decommissioned before destination is
  ready).
- **`MOVE` extension (RFC 6851)** — destructive on the source. Wrong tool for
  a mirror use case where the source must remain untouched until the
  destination is verified.
- **`COPY` (RFC 3501 §6.4.7)** — only works within the same server. The
  motivating use case crosses servers entirely (WorkMail → Gmail), so `COPY`
  is unavailable.
- **No dedup, trust UIDs** — IMAP UIDs are per-folder per-server and have no
  meaning across endpoints. `Message-ID` is the canonical, server-agnostic
  identity key.

### Dedup boundary: `Message-ID`, not full body hash

Comparing full bodies would catch the rare case of a message lacking a
`Message-ID` header. For the initial migration scope this is acceptable:
messages without `Message-ID` get a synthesized key
(`synthetic:<mirrorConfigId>:<sourceFolder>:<sourceUid>`) so they APPEND once
and skip thereafter. A future ADR can revisit body-hash dedup if/when
cross-folder de-duplication becomes a requirement.

### Reuses existing infrastructure

- **`ImapConnectionService`** (PR #12 lineage) — same connect/auth/health path
  for both source and destination.
- **`EmailAccount` + `TokenEncryptionService`** (PR #13) — credential storage
  works identically for source and destination accounts.
- **`EmailFolder`** (PR #17) — folder enumeration on both sides feeds the
  mapping editor.
- **`CrawlCheckpoint`** pattern (PR #19) — the resumability shape is borrowed
  directly, parameterized by `mirrorConfigId` and `(folder, lastUid)` instead
  of `(crawlConfigId, lastUri)`.
- **Spring Batch reader/processor/writer** — the existing email-job wiring
  conventions apply unchanged.

## Consequences

### Positive

- One-time migration use case (GoDaddy/WorkMail → Cloudflare/Gmail) ships as a
  real feature, not a one-off script.
- Future two-way sync or live mirror can build on the same `MirrorConfig` and
  `MirroredMessage` tables — the `Message-ID` index is the foundation either
  way.
- No new external dependencies; full stack containment.
- Idempotent re-runs are safe by design, matching the project's existing
  "same config re-run never skipped" philosophy for crawls.

### Negative

- **Destination APPEND quotas.** Gmail (and similar) cap APPENDs per second.
  Mitigation: `MirrorConfig.appendRateLimitPerSecond` (default 10) throttles
  inside the processor. Users tune via UI.
- **`INTERNALDATE` not honored on every server.** Some servers stamp their
  own timestamp on APPEND. Documented but unavoidable; major servers (Gmail,
  Cloudflare, Fastmail, Outlook) honor the supplied `INTERNALDATE`.
- **`Message-ID` collisions across mirror runs to different destination
  accounts.** Scoping `MirroredMessage` rows by `mirrorConfigId` (composite
  unique key on `(mirrorConfigId, messageId)`) avoids cross-config collisions
  by design.
- **Single-instance assumption.** If the app ever runs multiple replicas, a
  ShedLock-style guard around `MirrorJob` dispatch is needed to prevent two
  replicas racing on the same mirror. Same caveat as ADR-004; out of scope
  here.

## Open questions

These are not blockers for the initial feature but should be tracked:

- **Delete propagation.** If a source message is deleted after mirroring,
  should we delete from the destination? For the initial one-shot migration
  use case: no. For a future "live mirror": probably yes, configurable.
- **Source-side cleanup.** Some users may want to delete from source after
  successful mirror (effectively a `MOVE`-equivalent). Defer — a separate
  "verify and prune" pass is safer than coupling delete-on-success to APPEND.
- **Cross-account dedup.** If user A mirrors `acct1 → acct2` and then later
  mirrors `acct1 → acct3`, the `MirroredMessage` rows are independent (scoped
  by `mirrorConfigId`). That's deliberate: the user may want both
  destinations populated. Document the scoping in the schema comment.

## Acceptance criteria (across the issue suite)

- [ ] `MirrorConfig` entity + Flyway migration + UI for source/dest pair +
  folder map. (#22)
- [ ] `ImapMirrorService` with `APPEND` + flag/`INTERNALDATE` preservation +
  `Message-ID` dedup; `MirroredMessage` entity. (#23)
- [ ] `MirrorJob` Spring Batch with per-folder checkpoint reusing the
  `CrawlCheckpoint` shape. (#24)
- [ ] Dry-run REST + UI: per-folder count + size estimate + folder mapping
  preview. (#25)
- [ ] Progress dashboard + error log with HTMX auto-refresh; retry-failed
  endpoint; CSV error export. (#26)
