# Smart-Diff

> **Issue:** [#144](https://github.com/seanoc5/spring-search-tempo/issues/144) — graduates spike [#126](../research/smart-diff-tools.md).
> **Status:** `.docx` shipped. `.pptx` and `.xlsx` filed as separate follow-ups.

Smart-Diff is the FSFile detail-page feature that answers **"what changed
between two versions of this file?"** It works on user-authored documents
(drafts, revisions, "_v2" / "_final" copies) by extracting structured text
from each version and producing a paragraph-level diff.

## How it works

1. **Version pairing.** When you open a file detail page, the controller asks
   `SmartDiffService.findSiblingVersions(fileId)` for other files that share
   the same `label` (typically the basename) but have a **different**
   `contentHash` — i.e., they're byte-distinct. Files without a `contentHash`
   are excluded because we can only assert "different bytes" once both sides
   are hashed.
2. **Strategy dispatch.** When you pick a sibling from the "Compare with…"
   dropdown, the controller calls `SmartDiffService.diff(oldId, newId)`. The
   service resolves the strategy from the *new* file's
   `contentType` (falling back to the old file's content-type when the new
   one is missing) and dispatches.
3. **Per-format strategy.** Each `SmartDiffStrategy` declares the MIME
   types it handles and produces a `SmartDiffResult` of paragraph-keyed
   `INSERTED` / `DELETED` / `CHANGED` / `UNCHANGED` lines.

## Supported formats

| Format | Strategy | Notes |
|---|---|---|
| `.docx` | [`DocxSmartDiffStrategy`](../../src/main/kotlin/com/oconeco/spring_search_tempo/base/service/smartdiff/DocxSmartDiffStrategy.kt) | Paragraph-level. Tables flattened with a `[table]` marker; headers/footers/footnotes ignored; tracked-changes layer ignored (uses the "current" view). |

`.pptx` and `.xlsx` are planned next per the [smart-diff spike
recommendation](../research/smart-diff-tools.md#7-recommendation). `.pdf` is
deferred indefinitely — see the spike for the layout-extraction caveats.

## Adding a new strategy

Drop a `@Component` into
`base/service/smartdiff/` implementing `SmartDiffStrategy`:

```kotlin
@Component
class PptxSmartDiffStrategy : SmartDiffStrategy {
    override fun supportedContentTypes(): Set<String> = setOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    override fun diff(oldFile: FSFile, newFile: FSFile): SmartDiffResult {
        // … extract slides, diff text frames, return SmartDiffResult …
    }
}
```

`SmartDiffServiceImpl` auto-discovers it via constructor injection and
registers every MIME type it claims. The view template
(`templates/fSFile/smart-diff.html`) is format-agnostic — it consumes
`SmartDiffResult.lines` regardless of how they were produced — so a new
strategy gets the existing UI for free.

## Dependencies

- **`io.github.java-diff-utils:java-diff-utils:4.15`** — Myers-diff
  implementation used by every strategy that lays out content as a sequence
  of lines. Apache 2.0, ~200 KB, no transitives.
- **Apache POI** (`poi-ooxml`, already on the classpath) — structural OOXML
  read for `.docx` / `.pptx` / `.xlsx`.
