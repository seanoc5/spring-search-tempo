package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Render coverage for /admin/fsfile/metadata-duplicates (issue #120).
 *
 * Confirms that a UID-drift group (same name+size+mtime, different
 * POSIX owners) lights up the warning badge in the rendered HTML.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("Metadata-duplicate admin view renders UID-drift badge (issue #120)")
class FSFileMetadataDuplicateAdminControllerIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var fsFileRepository: FSFileRepository

    @Test
    @DisplayName("GET /admin/fsfile/metadata-duplicates — owner-mismatch group renders warning badge")
    fun rendersOwnerMismatchBadge() {
        val mtime = OffsetDateTime.of(2026, 6, 4, 10, 0, 0, 0, ZoneOffset.UTC)
        saveFile("/home/alice/projects.json", "projects.json", 1234L, mtime, "alice")
        saveFile("/home/bob/projects.json", "projects.json", 1234L, mtime, "bob")

        val body = mockMvc.perform(
            get("/admin/fsfile/metadata-duplicates")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("admin/fsfile/metadata-duplicates"))
            .andReturn().response.contentAsString

        assertThat(body).contains("projects.json")
        assertThat(body).contains("UID drift")
        assertThat(body).contains(">alice<")
        assertThat(body).contains(">bob<")
    }

    @Test
    @DisplayName("GET /admin/fsfile/metadata-duplicates — empty state renders without errors")
    fun rendersEmptyState() {
        val body = mockMvc.perform(
            get("/admin/fsfile/metadata-duplicates")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("No metadata-duplicate groups found.")
    }

    private fun saveFile(
        uri: String,
        label: String,
        size: Long,
        mtime: OffsetDateTime,
        posixOwner: String,
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
        }
        return fsFileRepository.save(file).id!!
    }
}
