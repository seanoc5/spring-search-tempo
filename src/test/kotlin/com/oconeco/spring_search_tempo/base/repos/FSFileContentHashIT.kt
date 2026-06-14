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

/**
 * Issue #119: byte-identical duplicate detection.
 *
 * Persistence-level acceptance: the new content_hash column is queryable via
 * `findByContentHash` / `countByContentHashAndIdNot`, and LOCATE files leave
 * the column null (the dedup UI hides the panel for them).
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("FSFile contentHash persistence (issue #119)")
class FSFileContentHashIT : BaseIT() {

    @Autowired
    lateinit var fSFileRepository: FSFileRepository

    private fun newFile(
        uri: String,
        analysisStatus: AnalysisStatus,
        contentHash: String?
    ): FSFile = FSFile().apply {
        this.uri = uri
        this.status = Status.NEW
        this.analysisStatus = analysisStatus
        this.contentHash = contentHash
        this.label = uri.substringAfterLast('/')
        this.type = "FILE"
    }

    @Test
    fun `findByContentHash returns all files sharing a hash, countByContentHashAndIdNot excludes self`() {
        val hash = "a".repeat(64)
        val a = fSFileRepository.save(newFile("/tmp/dup-a.txt", AnalysisStatus.INDEX, hash))
        val b = fSFileRepository.save(newFile("/tmp/dup-b.txt", AnalysisStatus.INDEX, hash))
        fSFileRepository.save(newFile("/tmp/unique.txt", AnalysisStatus.INDEX, "b".repeat(64)))

        val matches = fSFileRepository.findByContentHash(hash).map { it.id }
        assertThat(matches).containsExactlyInAnyOrder(a.id, b.id)

        // From a's perspective, there is exactly one other byte-identical copy (b).
        assertThat(fSFileRepository.countByContentHashAndIdNot(hash, a.id!!)).isEqualTo(1)
        // From b's perspective, same answer (a is the other copy).
        assertThat(fSFileRepository.countByContentHashAndIdNot(hash, b.id!!)).isEqualTo(1)
        // A nonsense hash matches nothing.
        assertThat(fSFileRepository.countByContentHashAndIdNot("0".repeat(64), a.id!!)).isEqualTo(0)
    }

    @Test
    fun `LOCATE files have a null contentHash and do not appear in dedup queries`() {
        val locate = fSFileRepository.save(newFile("/tmp/locate-only.bin", AnalysisStatus.LOCATE, null))

        val reloaded = fSFileRepository.findById(locate.id!!).orElseThrow()
        assertThat(reloaded.contentHash)
            .describedAs("LOCATE files must never be hashed — opening their bytes defeats the purpose")
            .isNull()

        // findByContentHash on null is not a meaningful query; a sibling INDEX file
        // with a real hash should still be found independently.
        val indexed = fSFileRepository.save(
            newFile("/tmp/indexed.txt", AnalysisStatus.INDEX, "c".repeat(64))
        )
        assertThat(fSFileRepository.findByContentHash("c".repeat(64)).map { it.id })
            .containsExactly(indexed.id)
    }
}
