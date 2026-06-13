package com.oconeco.spring_search_tempo.base.service

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ArchiveEnumerationServiceTest {

    private val service = ArchiveEnumerationService()

    @Test
    fun `detects supported archive extensions`() {
        assertEquals(ArchiveEnumerationService.ArchiveKind.ZIP, service.detectKind("a.zip"))
        assertEquals(ArchiveEnumerationService.ArchiveKind.JAR, service.detectKind("a.jar"))
        assertEquals(ArchiveEnumerationService.ArchiveKind.TAR, service.detectKind("a.tar"))
        assertEquals(ArchiveEnumerationService.ArchiveKind.TAR_GZ, service.detectKind("a.tar.gz"))
        assertEquals(ArchiveEnumerationService.ArchiveKind.TAR_GZ, service.detectKind("a.tgz"))
        assertEquals(ArchiveEnumerationService.ArchiveKind.SEVEN_Z, service.detectKind("a.7z"))
        assertNull(service.detectKind("a.txt"))
        assertNull(service.detectKind(null))
    }

    @Test
    fun `enumerates three entries from a zip`(@TempDir tmp: Path) {
        val zip = tmp.resolve("sample.zip")
        writeSampleZip(zip)

        val entries = service.enumerateEntries(zip).toList()
        // Three regular files plus a directory entry — verify file-only count.
        val files = entries.filterNot { it.isDirectory }
        assertEquals(3, files.size)
        val names = files.map { it.entryPath }.sorted()
        assertEquals(listOf("readme.txt", "src/code.kt", "subdir/notes.md"), names)
    }

    @Test
    fun `opens entry stream and returns content`(@TempDir tmp: Path) {
        val zip = tmp.resolve("sample.zip")
        writeSampleZip(zip)

        val stream = service.openEntryStream(zip, "readme.txt")
        assertNotNull(stream)
        val text = stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("hello readme", text)
    }

    @Test
    fun `returns null entry stream for missing entry`(@TempDir tmp: Path) {
        val zip = tmp.resolve("sample.zip")
        writeSampleZip(zip)
        assertNull(service.openEntryStream(zip, "does/not/exist.txt"))
    }

    @Test
    fun `buildEntryUri produces jar-style URI`() {
        val uri = service.buildEntryUri("file:///opt/archive.zip", "inner/file.txt")
        assertEquals("file:///opt/archive.zip!/inner/file.txt", uri)
    }

    @Test
    fun `depthOf counts bang separators`() {
        assertEquals(0, service.depthOf("file:///opt/a"))
        assertEquals(1, service.depthOf("file:///opt/a.zip!/inner.txt"))
        assertEquals(2, service.depthOf("file:///opt/a.zip!/nested.zip!/leaf.txt"))
    }

    @Test
    fun `unrecognised extensions return empty sequence`(@TempDir tmp: Path) {
        val plain = tmp.resolve("file.txt")
        Files.writeString(plain, "not an archive")
        assertTrue(service.enumerateEntries(plain).toList().isEmpty())
    }

    @Test
    fun `corrupt archive returns empty sequence and does not throw`(@TempDir tmp: Path) {
        val zip = tmp.resolve("broken.zip")
        Files.writeString(zip, "PKnot really a zip")
        // Should swallow the IOException and surface an empty sequence so the crawl continues.
        assertTrue(service.enumerateEntries(zip).toList().isEmpty())
    }

    /**
     * Three regular files plus one directory entry, deterministic content so the test
     * assertions don't drift. Lives in the test (not src/test/resources) so the fixture
     * is exact about its contents and survives any future repo-wide fixture moves.
     */
    private fun writeSampleZip(target: Path) {
        ZipArchiveOutputStream(Files.newOutputStream(target)).use { zos ->
            putEntry(zos, "readme.txt", "hello readme")
            putEntry(zos, "src/code.kt", "fun main() = println(\"hi\")")
            putEntry(zos, "subdir/notes.md", "# notes\n\nsome content")
            // Directory entry — should be filtered out by callers that only care about files.
            val dirEntry = ZipArchiveEntry("subdir/")
            zos.putArchiveEntry(dirEntry)
            zos.closeArchiveEntry()
        }
    }

    private fun putEntry(zos: ZipArchiveOutputStream, name: String, content: String) {
        val entry = ZipArchiveEntry(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        entry.size = bytes.size.toLong()
        zos.putArchiveEntry(entry)
        zos.write(bytes)
        zos.closeArchiveEntry()
    }
}
