package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Integration tests for the metadata-duplicate finder (issue #120).
 *
 * The finder groups [FSFile] rows by their `(label, size,
 * fsLastModified)` triple and surfaces groups with > 1 member. The
 * UID-drift signal (the same name+size+mtime under different POSIX
 * owners) is the headline use case.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("FSFile metadata-duplicate finder (issue #120)")
class FSFileMetadataDuplicateRepositoryIT : BaseIT() {

    @Autowired
    lateinit var fsFileRepository: FSFileRepository

    @Test
    @DisplayName("duplicate group with distinct POSIX owners surfaces with UID-drift signal")
    fun surfacesMultiOwnerDuplicateGroup() {
        val mtime = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        saveFile(uri = "/home/user-a/projects.json", label = "projects.json",
            size = 1234, mtime = mtime, posixOwner = "alice", posixGroup = "staff", posixMode = 0b110_100_100)
        saveFile(uri = "/home/user-b/projects.json", label = "projects.json",
            size = 1234, mtime = mtime, posixOwner = "bob", posixGroup = "staff", posixMode = 0b110_100_100)
        // Decoy: same name & owner, different size — must not be in the group.
        saveFile(uri = "/home/user-c/projects.json", label = "projects.json",
            size = 9999, mtime = mtime, posixOwner = "alice", posixGroup = "staff", posixMode = null)

        val page = fsFileRepository.findMetadataDuplicateGroups(PageRequest.of(0, 25))

        assertThat(page.totalElements).isEqualTo(1L)
        val group = page.content.single()
        assertThat(group.label).isEqualTo("projects.json")
        assertThat(group.size).isEqualTo(1234L)
        assertThat(group.count).isEqualTo(2L)

        val files = fsFileRepository.findByMetadataTriple(
            group.label, group.size, group.fsLastModified,
        )
        assertThat(files).hasSize(2)
        val distinctOwners = files.mapNotNull { it.posixOwner }.toSet()
        assertThat(distinctOwners).containsExactlyInAnyOrder("alice", "bob")
    }

    @Test
    @DisplayName("singleton (unique name+size+mtime) is excluded from the duplicates list")
    fun excludesSingletons() {
        val mtime = OffsetDateTime.of(2026, 6, 2, 9, 30, 0, 0, ZoneOffset.UTC)
        saveFile(uri = "/home/user-a/unique.json", label = "unique.json",
            size = 555, mtime = mtime, posixOwner = "alice", posixGroup = null, posixMode = null)

        val page = fsFileRepository.findMetadataDuplicateGroups(PageRequest.of(0, 25))

        assertThat(page.totalElements).isEqualTo(0L)
        assertThat(page.content).isEmpty()
    }

    @Test
    @DisplayName("files persisted with null posix fields (non-POSIX FS) still surface as duplicates by name+size+mtime")
    fun nonPosixFsDoesNotBreakDuplicateFinder() {
        // Simulates a Windows / non-POSIX filesystem: the crawl
        // processor caught UnsupportedOperationException and stored
        // null for all three posix_* columns. The duplicate-finder
        // groups on label+size+fsLastModified, which are still
        // populated, so the row should still group.
        val mtime = OffsetDateTime.of(2026, 6, 3, 8, 0, 0, 0, ZoneOffset.UTC)
        saveFile(uri = "/c:/users/a/notes.txt", label = "notes.txt",
            size = 42, mtime = mtime, posixOwner = null, posixGroup = null, posixMode = null)
        saveFile(uri = "/c:/users/b/notes.txt", label = "notes.txt",
            size = 42, mtime = mtime, posixOwner = null, posixGroup = null, posixMode = null)

        val page = fsFileRepository.findMetadataDuplicateGroups(PageRequest.of(0, 25))

        assertThat(page.totalElements).isEqualTo(1L)
        val group = page.content.single()
        assertThat(group.count).isEqualTo(2L)
        val files = fsFileRepository.findByMetadataTriple(
            group.label, group.size, group.fsLastModified,
        )
        assertThat(files).hasSize(2)
        assertThat(files.mapNotNull { it.posixOwner }).isEmpty()
        assertThat(files.mapNotNull { it.posixMode }).isEmpty()
    }

    private fun saveFile(
        uri: String,
        label: String,
        size: Long,
        mtime: OffsetDateTime,
        posixOwner: String?,
        posixGroup: String?,
        posixMode: Int?,
    ): Long {
        val file = FSFile().apply {
            this.uri = uri
            this.label = label
            this.size = size
            this.fsLastModified = mtime
            this.status = Status.CURRENT
            this.analysisStatus = AnalysisStatus.LOCATE
            this.version = 0L
            this.posixOwner = posixOwner
            this.posixGroup = posixGroup
            this.posixMode = posixMode
        }
        return fsFileRepository.save(file).id!!
    }
}
