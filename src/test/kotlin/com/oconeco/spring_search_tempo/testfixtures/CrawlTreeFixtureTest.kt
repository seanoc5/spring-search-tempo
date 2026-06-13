package com.oconeco.spring_search_tempo.testfixtures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * Smoke test for the [CrawlTreeFixture]. Asserts the static layout on
 * disk and the post-build layout in a `@TempDir`. A future contributor
 * who reshapes the fixture (adds a file, renames a folder, etc.) will
 * fail here first and know exactly what to update on the README and the
 * manifest constants.
 *
 * Established by issue #115.
 */
class CrawlTreeFixtureTest {

    @Test
    fun `static committed tree matches manifest constants`() {
        val staticRoot = locateStaticRoot()

        val staticFiles = Files.walk(staticRoot)
            .filter { Files.isRegularFile(it) }
            .toList()
        val staticDirs = Files.walk(staticRoot)
            .filter { Files.isDirectory(it) }
            .toList()

        assertThat(staticFiles)
            .`as`("static file count must match CrawlTreeFixture.STATIC_FILE_COUNT")
            .hasSize(CrawlTreeFixture.STATIC_FILE_COUNT)
        assertThat(staticDirs)
            .`as`("static directory count must match CrawlTreeFixture.STATIC_DIR_COUNT")
            .hasSize(CrawlTreeFixture.STATIC_DIR_COUNT)
    }

    @Test
    fun `empty txt is exactly zero bytes`() {
        val staticRoot = locateStaticRoot()
        val empty = staticRoot.resolve("empty.txt")
        assertThat(Files.size(empty)).isZero()
    }

    @Test
    fun `node_modules has five nesting levels with a file at each`() {
        val staticRoot = locateStaticRoot()
        val levels = listOf(
            "node_modules/pkg-root.js",
            "node_modules/foo/foo-file.js",
            "node_modules/foo/bar/bar-file.js",
            "node_modules/foo/bar/baz/baz-file.js",
            "node_modules/foo/bar/baz/deep/deep-file.js",
        )
        levels.forEach { rel ->
            assertThat(staticRoot.resolve(rel))
                .`as`("expected file at SKIP-tree level: $rel")
                .exists()
                .isRegularFile()
        }
    }

    @Test
    fun `hidden-gem path lives under node_modules with INDEX-eligible content`() {
        val staticRoot = locateStaticRoot()
        val gem = staticRoot.resolve("node_modules/foo/bar/hidden-gem/internal-tool/notes.md")
        assertThat(gem).exists().isRegularFile()
        assertThat(Files.readString(gem)).contains("hidden gem")
    }

    @Test
    fun `built tree matches BUILT_FILE_COUNT and contains generated dynamic files`(@TempDir tmp: Path) {
        val root = CrawlTreeFixture.build(tmp)

        val builtFiles = Files.walk(root).filter { Files.isRegularFile(it) }.toList()
        assertThat(builtFiles)
            .`as`("built file count must match CrawlTreeFixture.BUILT_FILE_COUNT")
            .hasSize(CrawlTreeFixture.BUILT_FILE_COUNT)

        CrawlTreeFixture.GENERATED_FILES.forEach { rel ->
            assertThat(root.resolve(rel))
                .`as`("expected generated file in built tree: $rel")
                .exists()
                .isRegularFile()
        }
    }

    @Test
    fun `oversized txt exceeds the text-extraction max`(@TempDir tmp: Path) {
        val root = CrawlTreeFixture.build(tmp)
        val oversized = root.resolve("oversized.txt")
        val tenMiB = 10L * 1024L * 1024L
        assertThat(Files.size(oversized))
            .`as`("oversized.txt must be just over the 10 MiB text-extraction cap")
            .isGreaterThan(tenMiB)
            .isEqualTo(CrawlTreeFixture.OVERSIZED_TXT_SIZE)
    }

    @Test
    fun `sample zip has the three expected entries with mixed extensions`(@TempDir tmp: Path) {
        val root = CrawlTreeFixture.build(tmp)
        val zipPath = root.resolve("sample.zip")
        assertThat(zipPath).exists().isRegularFile()
        ZipFile(zipPath.toFile()).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertThat(names)
                .containsExactlyInAnyOrderElementsOf(CrawlTreeFixture.ZIP_ENTRIES)
        }
    }

    @Test
    fun `build is idempotent`(@TempDir tmp: Path) {
        CrawlTreeFixture.build(tmp)
        val firstFiles = Files.walk(tmp).filter { Files.isRegularFile(it) }.count()
        CrawlTreeFixture.build(tmp)
        val secondFiles = Files.walk(tmp).filter { Files.isRegularFile(it) }.count()
        assertThat(secondFiles).isEqualTo(firstFiles)
    }

    /**
     * Locate the committed static root on disk. We use the classpath
     * URL (same trick the builder uses) rather than a hard-coded
     * `src/test/resources/…` path so the test passes whether run from
     * the project root, from IntelliJ, or from a CI container with a
     * different working directory.
     */
    private fun locateStaticRoot(): Path {
        val url = Thread.currentThread().contextClassLoader
            .getResource(CrawlTreeFixture.CLASSPATH_ROOT)
            ?: error("fixture classpath root missing")
        return Paths.get(url.toURI())
    }
}
