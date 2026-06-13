# crawl-tree — canonical filesystem fixture for crawl/IT suites

This directory is the single source of truth for the synthetic filesystem
tree that integration tests crawl against. It exists so that future ITs
(`AnalysisStatus` end-to-end, Tika size-cap, archive handling, hidden-gem
detection) share one fixture rather than each rolling its own inline tree.

Established by **issue #115**.

## How to consume from a test

```kotlin
import com.oconeco.spring_search_tempo.testfixtures.CrawlTreeFixture
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MyCrawlIT {
    @TempDir lateinit var tmp: Path

    @Test
    fun `crawl walks the fixture tree`() {
        val root = CrawlTreeFixture.build(tmp)
        // root now contains the full tree, including generated oversized.txt
        // and sample.zip. Point the crawler at `root` and assert.
    }
}
```

The builder copies all committed static files from this directory into
`tmp`, then generates the two dynamic files (`oversized.txt`, `sample.zip`)
on the fly. Generated files are intentionally NOT committed — they would
bloat the repo and the manifest is the source of truth.

## Layout

| Path                                                            | Bytes | Intent (which IT class this is for) |
|-----------------------------------------------------------------|-------|--------------------------------------|
| `README.md`                                                     | n/a   | This file (copied along with the rest, so consumers can see it in built tree) |
| `empty.txt`                                                     | 0     | 0-byte edge case for `TextExtractionService` |
| `docs/lorem-alpha.txt`                                          | 192   | INDEX-class — short, predictable, ASCII |
| `docs/lorem-beta.txt`                                           | 252   | INDEX-class — short, predictable, ASCII |
| `docs/lorem-gamma.txt`                                          | 129   | INDEX-class — short, predictable, ASCII |
| `analyzed/press-release.txt`                                    | 602   | ANALYZE-class — sentence-rich, named entities (Acme, Stanford, Goldman Sachs), positive sentiment |
| `analyzed/product-review.txt`                                   | 541   | ANALYZE-class — sentence-rich, named entities (Apple, Sony, Amazon), negative sentiment |
| `node_modules/pkg-root.js`                                      | 100   | SKIP-folder optimization — level 1 |
| `node_modules/foo/foo-file.js`                                  | 55    | SKIP-folder optimization — level 2 |
| `node_modules/foo/bar/bar-file.js`                              | 55    | SKIP-folder optimization — level 3 |
| `node_modules/foo/bar/baz/baz-file.js`                          | 55    | SKIP-folder optimization — level 4 |
| `node_modules/foo/bar/baz/deep/deep-file.js`                    | 65    | SKIP-folder optimization — level 5 (deepest) |
| `node_modules/foo/bar/hidden-gem/internal-tool/notes.md`        | 311   | Hidden-gem detection (#104) — INDEX-eligible content buried under a SKIP root |
| `metadata-only/photo.jpg`                                       | 17    | LOCATE-class — extension drives classification |
| `metadata-only/song.mp3`                                        | 17    | LOCATE-class — extension drives classification |
| `metadata-only/archive.iso`                                     | 17    | LOCATE-class — extension drives classification |
| `metadata-only/binary.bin`                                      | 17    | LOCATE-class — extension drives classification |
| `metadata-only/dataset.parquet`                                 | 21    | LOCATE-class — extension drives classification |
| **(generated)** `oversized.txt`                                 | 10485761 (10 MiB + 1) | Just over `TextExtractionService.MAX_STRING_LENGTH` (10 MiB) — triggers the size-cap path |
| **(generated)** `sample.zip`                                    | varies | 3 entries with mixed extensions: `inside.txt`, `Inside.kt`, `inside.bin` — for archive-handling IT |

### Static counts (what `find src/test/resources/fixtures/crawl-tree …` sees)

```
find src/test/resources/fixtures/crawl-tree -type f | wc -l   →  18
find src/test/resources/fixtures/crawl-tree -type d | wc -l   →  11
```

### Built-tree counts (after `CrawlTreeFixture.build(tmpDir)`)

```
20 files (18 static + 2 generated)
11 directories (no new dirs created during generation)
```

## SKIP behaviour expectations

The `node_modules/` subtree is intentionally five levels deep with files
at each level. Crawlers honoring the `.*/node_modules/.*` SKIP pattern
should:

- Persist metadata for the `node_modules/` folder itself.
- **Not** enumerate its children (file count under `node_modules/` should
  be zero in the indexed result for a SKIP-respecting crawl).
- Hidden-gem detection (#104) is a separate pass that may surface
  `node_modules/foo/bar/hidden-gem/internal-tool/notes.md` despite the
  parent SKIP. ITs verifying hidden-gem surfacing should assert against
  that exact path.

## Manifest is the source of truth

Counts and sizes above are also encoded as constants on
`com.oconeco.spring_search_tempo.testfixtures.CrawlTreeFixture` and
asserted by `CrawlTreeFixtureTest`. If you change the tree, update both
this README and the constants — the smoke test will refuse to pass
otherwise.

## Consumers

Tests known to depend on this fixture (extend this list as IT suites
adopt it):

- `CrawlTreeFixtureTest` — verifies the fixture itself.
- *(planned)* `AnalysisStatusEndToEndIT` — round-trip status classification.
- *(planned)* `TextExtractionSizeCapIT` — verifies `oversized.txt` rejection.
- *(planned)* `ArchiveHandlingIT` — verifies `sample.zip` traversal.
- *(planned)* `HiddenGemDetectionIT` — verifies `internal-tool/notes.md` surfacing.
