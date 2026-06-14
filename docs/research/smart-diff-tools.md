# Smart-Diff Tooling Research

**Status:** research spike — no code change accompanies this doc.
**Scope:** estimate effort to ship "what changed between two versions" diffs for `.docx`, `.pptx`, `.xlsx`, `.pdf` in the FSFile detail UI.
**Out of scope:** UI surface design, data model changes, implementation issues, multimedia formats, encrypted/password-protected docs.

This spike is a precursor to implementation issues. Recommendations here name format batches to ship-first vs. defer; concrete implementation issues will be filed in follow-up after review.

---

## 1. Context: what's already on the classpath

Before reaching for new dependencies, we anchor on libraries we already pull:

| Library | Version | How it gets here | Relevance |
|---|---|---|---|
| Apache Tika | 2.9.1 | Direct (`tika-core`, `tika-parsers-standard-package`) | Universal text extraction; not structural |
| Apache POI | 5.4.1 | Direct (`poi-ooxml`) | Structural read of OOXML (docx/pptx/xlsx) |
| Apache PDFBox | 2.0.29 | Transitive via `tika-parsers-standard-package` | PDF text extraction |
| Apache commons-compress | 1.27.1 | Direct | Already used for archive enumeration; reusable for opening OOXML envelopes if we ever bypass POI |

**What's NOT on the classpath today:**

- No general-purpose **line diff** library. `java-diff-utils` (a.k.a. `io.github.java-diff-utils:java-diff-utils`) would be the canonical pick — Apache 2.0, ~200 KB jar, no transitive deps, last release 2024 (4.15). This is the assumed addition for every format below.
- No structural OOXML diff library (docx4j, etc.). We do not need one if we treat the problem as "extract structured text via POI, diff text via java-diff-utils."

**Implication:** for the OOXML trio (docx/pptx/xlsx), the marginal dependency cost of shipping a diff is essentially one small jar (`java-diff-utils`). For PDF, it's zero new deps but a much harder accuracy problem.

---

## 2. Word (`.docx`)

### Available libraries

| Library | License | Last release | Status on classpath | Transitive footprint |
|---|---|---|---|---|
| Apache POI (`poi-ooxml`) | Apache 2.0 | 5.4.1 (2025) | **Already direct** | Heavy (already paid for) |
| java-diff-utils | Apache 2.0 | 4.15 (2024) | New | ~200 KB, no transitives |
| docx4j | Apache 2.0 | 11.x (2024) | New | Heavy (JAXB, several MB) — **not recommended**, POI covers our needs |
| GitHub linguist-style `diff-match-patch` (Google) | Apache 2.0 | 2018 (Java port stagnant) | New | Tiny; better suited to short strings, not paragraphs |

**No Python/Node alternative needed** — Java tooling is mature for `.docx`.

### Granularity available

- **Paragraph-level** via `XWPFDocument.paragraphs` → `XWPFParagraph.text`. Cleanest unit for a useful diff.
- **Run-level** (within a paragraph; formatting boundaries) — exposes bold/italic/strike changes, but explodes scope. Not for MVP.
- **Structural** (styles, headers, tables, comments, tracked changes) — POI exposes all of it; integrating would balloon effort. Defer.
- **Body-text only** via Tika — easiest, but loses paragraph boundaries and gives a worse diff. Not recommended over POI when POI is already on classpath.

### Effort estimate

**Small** (1 worker-issue unit).

- Load both `.docx` files via POI `XWPFDocument`.
- Extract paragraphs as `List<String>` from each.
- Feed to `DiffUtils.diff(...)` from java-diff-utils.
- Render unified or side-by-side diff in a Thymeleaf fragment on the FSFile detail page.

The hard work — opening the OOXML envelope, walking the structure — is already done by POI.

### Known limitations / gotchas

- **Tracked changes / revisions** in source `.docx` files: POI exposes them, but if we ignore the revision layer and just read `getText()` we get the "current" view, which is what users almost always want for a diff. Worth documenting.
- **Tables and lists**: POI returns table cells separately; we need to decide whether to flatten them into the paragraph stream or treat as separate sections. MVP: flatten with a marker.
- **Embedded objects** (OLE, charts, equations): out of scope; render as `[embedded object]` placeholders in the diff stream.
- **Header/footer/footnotes**: append after body or hide behind a "show metadata diff" toggle. MVP: ignore — they're rarely the answer to "what changed."
- **Password-protected docs**: POI can read with the password supplied, but per scope discipline, defer.

---

## 3. PowerPoint (`.pptx`)

### Available libraries

| Library | License | Last release | Status on classpath | Transitive footprint |
|---|---|---|---|---|
| Apache POI (`poi-ooxml`, `XSLF`) | Apache 2.0 | 5.4.1 (2025) | **Already direct** | Heavy (already paid for) |
| java-diff-utils | Apache 2.0 | 4.15 (2024) | New | ~200 KB |
| Aspose.Slides (commercial) | Proprietary | 2025 | New | Heavy, license cost — **not recommended** |

### Granularity available

- **Slide-level** with **text-frame-level** within each slide. This is the natural granularity — users think in slides.
- **Shape-level** (per text box / placeholder): doable via POI `XSLFTextShape` traversal. Useful when one slide changed substantially.
- **Visual / layout diff** (positions, colors, master-slide changes): out of scope — would require rasterization. Defer to a phase-3 spike if ever.
- **Speaker notes**: POI exposes `XSLFNotes.text`. Worth including as a small extra "notes" column in the diff.

### Effort estimate

**Small-to-medium** (1–1.5 worker-issue units).

The extra half-unit over `.docx`:

- Need to choose a presentation in the UI: "slide N changed" jump-list, then drill into per-shape text diff for that slide.
- Per-slide grouping is a small but real UI affordance beyond a flat unified diff.

### Known limitations / gotchas

- **Slide reordering** is the realistic majority case. A naive line diff will look like "everything changed" when slides 5 and 6 swap. **Mitigation:** key slides by a stable signal (slide title, or hash of slide text) before diffing the sequence — this is small extra logic but materially better UX. Worth costing in.
- **Slide layouts / masters**: same content can shift visually because of master-slide edits. Out of scope for text diff.
- **Embedded media** (images, video, audio): `[media: name]` placeholder.
- **Speaker notes** vs. **slide text**: surface separately; conflating them is misleading.

---

## 4. Excel (`.xlsx`)

### Available libraries

| Library | License | Last release | Status on classpath | Transitive footprint |
|---|---|---|---|---|
| Apache POI (`poi-ooxml`, `XSSF`) | Apache 2.0 | 5.4.1 (2025) | **Already direct** | Heavy (already paid for) |
| Apache POI SAX/streaming (`SXSSF`/`XSSFReader`) | Apache 2.0 | 5.4.1 | Already direct | Same artifact — used for large sheets |
| java-diff-utils | Apache 2.0 | 4.15 (2024) | New | ~200 KB — but **only marginally useful** for Excel |

### Granularity available

- **Cell-level** (sheet × row × column → value): **this is the killer feature** and is structurally different from the docx/pptx text-line story. Diff shape is a *cell-coordinate map*, not a line sequence.
- **Sheet-level** (added/removed/renamed sheets): cheap to surface; do it.
- **Formula vs. computed value**: a cell can change because its formula was edited, or because its inputs changed. POI exposes both. MVP: compare computed values; expose formula diff as a secondary view.
- **Formatting** (number formats, fills, conditional formatting): out of scope.
- **Named ranges, pivot tables, charts**: out of scope.

### Effort estimate

**Medium** (2 worker-issue units).

The data-shape difference vs. docx/pptx is the cost:

- Walk sheets, rows, cells via POI; build `Map<SheetName, Map<CellRef, CellValue>>`.
- Diff the maps (added / removed / changed cells), not a line sequence.
- UI must render this as a *grid* of changed cells with the old and new value, plus a header showing per-sheet change counts. A flat unified diff is the wrong affordance.
- Large sheets: must use POI's streaming reader (`XSSFReader` / event-based) to avoid OOM. Adds engineering care, not new dependencies.

`java-diff-utils` plays only a minor role here — possibly for diffing in-cell strings that are long (multi-line text inside one cell), or for diffing sheet-name sequences. The primary algorithm is map-key set arithmetic.

### Known limitations / gotchas

- **Volatile formulas** (`NOW()`, `RAND()`): every recompute looks "changed." Either skip volatile-formula cells in the diff or surface them in a separate "volatile cells" bucket.
- **Floating-point representation**: `=0.1+0.2` ≠ 0.3 byte-equal. Round/normalize before comparing values.
- **Large workbooks** (50k+ rows): streaming read is mandatory; building the full cell map in memory will not scale. Engineering discipline issue, not a dep issue.
- **External references** (link to other workbooks): POI sees the cached value only.
- **Merged cells**: easy to misreport — pick the anchor cell's value, ignore the merge region's other coordinates.
- **Date cells**: POI returns them as numeric `double` (Excel serial date); normalize to `LocalDate`/`LocalDateTime` before comparing or it'll never match across files with different cached formats.

---

## 5. PDF (`.pdf`)

### Available libraries

| Library | License | Last release | Status on classpath | Transitive footprint |
|---|---|---|---|---|
| Apache PDFBox | Apache 2.0 | 2.0.29 (transitive via Tika); 3.0.x available | **Already transitive** | Already paid for |
| Apache Tika (`PDFParser`) | Apache 2.0 | 2.9.1 | Already direct | Already paid for |
| iText 7 (AGPL or commercial) | AGPL / commercial | 2025 | New | AGPL would contaminate licensing — **not viable for us** |
| OpenPDF (fork of iText 4) | MPL / LGPL | 2024 | New | Smaller, LGPL OK; weaker text-extraction quality than PDFBox |
| `pdf.js` (Mozilla) | Apache 2.0 | 2024 | New (browser/Node) | Better visual-reading-order text extraction — but adds a JS runtime; not Java |
| **Python: `pdfplumber`** | MIT | 2024 | N/A | Best-in-class for layout-aware extraction; would require an out-of-process service. Mention only because the gap from Java tooling is real |

### Granularity available

- **Page-level**: PDFBox gives us per-page text. This is the realistic unit.
- **Text-line within page**: PDFBox `PDFTextStripper` returns text approximately in reading order — emphasis on *approximately*. Multi-column layouts, tables, and rotated text all break this.
- **Visual reading order**: not reliably available from PDFBox. `pdf.js` does it better; `pdfplumber` does it best — both are non-Java.
- **Structural diff** (form fields, annotations, bookmarks, signatures): possible via PDFBox but high effort and rarely what users want.

### Effort estimate

**Medium for the happy path, Large for "actually useful across our users' PDFs."**

- **Happy path** (text-heavy, single-column PDFs like a manuscript or report): 2 units. Extract page text via PDFBox, normalize whitespace, diff via java-diff-utils per page.
- **Realistic path** (mixed PDFs including multi-column papers, slide exports, scanned docs, invoices): 4+ units. We hit the text-extraction-order wall, and our diff will frequently look like "the whole page changed" even when only one number changed, because token order shifted.
- **Scanned PDFs**: no text layer → diff is impossible without OCR. Out of scope; mark as "[no text layer]" in the UI.

### Known limitations / gotchas

- **Reading order ≠ visual order** (the headline issue): PDFBox extracts text in the order glyphs appear in the content stream, which for many real PDFs (academic papers, slide exports, brochures) does not match how a human reads. A token-level line diff against this will misreport heavily.
- **Whitespace and ligatures**: PDFBox's text stream often contains non-breaking spaces, ligature characters (`ﬁ`, `ﬂ`), and irregular spacing. Aggressive normalization is mandatory before diffing.
- **No semantic paragraphs**: PDFs do not have a notion of "paragraph"; we have to infer breaks from line gaps. Inference quality varies.
- **Tables**: text-stripper returns table contents as flowing text with whitespace, frequently in column-major or row-major order depending on the producer. Useless for diff.
- **Form fields, annotations, signatures**: out of scope for MVP.
- **Scanned PDFs**: no text layer; OCR is its own multi-issue research spike.
- **Honest framing of value**: in this codebase's user model (Spotlight-style indexing across the full system), **most PDFs the user encounters are "publish, not create"** — one canonical version received from elsewhere. There is no heritage of edits to diff. Spending Large effort on PDF diff for an uncommon use case is the wrong order of investment.

---

## 6. Cross-format summary

| Format | Library maturity | Effort to ship useful text-diff | Coverage of typical user files | New deps required |
|---|---|---|---|---|
| `.docx` | High (POI is best-in-class) | **Small** (1 unit) | Very high — many heritage versions per file | `java-diff-utils` (~200 KB) |
| `.pptx` | High (POI XSLF mature) | **Small-Medium** (1–1.5 units) | High — slide decks have heritage | `java-diff-utils` (~200 KB) |
| `.xlsx` | High (POI XSSF mature) | **Medium** (2 units) | High — spreadsheets are diff-prone by nature | `java-diff-utils` (~200 KB, modest use) |
| `.pdf` | PDFBox is mature, but the *problem* is hard | **Medium happy path, Large realistic** (2–4+ units) | Moderate-to-low — most PDFs are received, not authored | None (PDFBox already transitive) |

**Effort-unit definition:** one worker-issue unit ≈ one focused issue scoped to a single PR, including tests and the FSFile detail UI panel for the format. The estimates assume the smart-diff infrastructure (loading two versions of an `FSFile` by `contentHash`, routing to a per-format diff strategy, rendering a fragment) is shared across formats and built once on the back of the first format shipped.

---

## 7. Recommendation

### Ship first

- **`.docx` (ship-first)** — smallest effort, highest library leverage (POI already in deps), and the format with the most "user-authored heritage" semantics. Makes the strongest first impression for the feature, and the shared smart-diff plumbing (version pairing, fragment rendering, route) gets built on its back. **One small follow-up issue.**

- **`.pptx` (ship-first, after `.docx`)** — reuses the docx-built infrastructure; only added cost is slide-keyed pre-alignment and a slightly different UI layout (per-slide jump list). **One small-to-medium follow-up issue.**

### Ship second

- **`.xlsx` (ship-second)** — high user value (cell diffs are the canonical "what changed" question on spreadsheets), but the data-shape and UI affordance are *different enough* from the line-diff plumbing that it should not block the first release. Ship after docx + pptx demonstrate the framework. **One medium follow-up issue, possibly two if formula-vs-value diff is split.**

### Defer

- **`.pdf` (defer)** — text-extraction-order limitation makes "useful" hard to deliver, and our user model is biased toward received-not-authored PDFs where diff has lower utility. Revisit only after the OOXML trio is shipped and we have user signal demanding it. If revisited, scope it as **its own research spike on layout-aware PDF text extraction** (likely involving an out-of-process Python service or evaluating `pdf.js` in a JVM-side runtime like GraalJS) before any implementation issue is filed.

### Out of scope (confirmed, do not file)

- Multimedia (video/audio/image) diff — phase-3+ roadmap item, separate research entirely.
- Encrypted/password-protected docs — its own research spike if it ever surfaces.
- Visual / formatting diff (font, color, layout) — substantially different problem; defer indefinitely.

### Follow-up issues this spike enables

Two implementation issues can be filed immediately on the back of this recommendation:

i.   **"Implement `.docx` smart-diff via POI + java-diff-utils, with shared smart-diff infrastructure (version pairing by `contentHash`, per-format strategy dispatch, FSFile detail UI panel)."** — Small.
ii.  **"Implement `.pptx` smart-diff on top of shared smart-diff infrastructure; slide-keyed pre-alignment; per-slide jump list."** — Small-to-Medium.

A third issue (`.xlsx` cell-level diff) can be filed after (i) and (ii) ship, when the shared infrastructure has settled.

---

## 8. Quick reference — chosen libraries

| Purpose | Library | Already on classpath? |
|---|---|---|
| `.docx` / `.pptx` / `.xlsx` structured read | Apache POI 5.4.1 | Yes |
| Text-level line diff | java-diff-utils 4.15 | **No — to be added** |
| PDF text extraction (if/when revisited) | Apache PDFBox 2.0.29 | Yes (transitive via Tika) |
| Universal text fallback / format detection | Apache Tika 2.9.1 | Yes |

Total new dependency footprint to ship the recommended first batch (docx + pptx): **one ~200 KB jar with no transitives**.
