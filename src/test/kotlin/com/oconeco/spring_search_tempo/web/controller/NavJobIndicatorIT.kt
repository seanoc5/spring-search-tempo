package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

/**
 * GET /nav/jobIndicator + /nav/jobIndicator/details — site-wide nav indicator
 * for in-flight crawl/sync jobs (issue #73).
 *
 * The endpoints power the small badge in the global header and its
 * click-to-open dropdown panel. Both are backed by [JobRunRepository]
 * COUNT/lookup queries against the `job_run` table — no JobExplorer scan.
 *
 * Coverage:
 *   1. badge with no running jobs → muted "idle" pill, no primary-count badge.
 *   2. badge with two RUNNING JobRuns → primary badge text "2", spinning icon.
 *   3. badge with a recent FAILED JobRun → red dot overlay.
 *   4. details panel → lists the running execution with detail-page link
 *      and the rendered started-at time.
 *   5. details panel with no rows → "No running jobs right now." copy.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("NavJobIndicatorController /nav/jobIndicator — global header indicator (issue #73)")
class NavJobIndicatorIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jobRunRepository: JobRunRepository

    @BeforeEach
    fun resetJobRuns() {
        jobRunRepository.deleteAll()
    }

    @Test
    @DisplayName("badge — no running jobs → idle pill, no count badge, no red dot")
    fun badgeIdleWhenNothingRunning() {
        mockMvc.perform(
            get("/nav/jobIndicator").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"navJobIndicator\"")))
            .andExpect(content().string(containsString(">idle<")))
            .andExpect(content().string(not(containsString("rounded-pill bg-primary"))))
            .andExpect(content().string(not(containsString("bg-danger border border-light rounded-circle"))))
    }

    @Test
    @DisplayName("badge — two RUNNING JobRuns → count badge \"2\" and spinning icon")
    fun badgeShowsCountWhenJobsRunning() {
        saveJobRun(jobName = "fsCrawlJob", status = RunStatus.RUNNING)
        saveJobRun(jobName = "emailQuickSync_42", status = RunStatus.RUNNING)

        mockMvc.perform(
            get("/nav/jobIndicator").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("rounded-pill bg-primary")))
            .andExpect(content().string(containsString(">2<")))
            .andExpect(content().string(containsString("bi-arrow-repeat spin")))
    }

    @Test
    @DisplayName("badge — recent FAILED JobRun (last 5 min) → red dot overlay even with no running jobs")
    fun badgeRedDotForRecentFailure() {
        saveJobRun(
            jobName = "nlpProcessingJob",
            status = RunStatus.FAILED,
            startTime = OffsetDateTime.now().minusMinutes(3),
            finishTime = OffsetDateTime.now().minusMinutes(2),
        )

        mockMvc.perform(
            get("/nav/jobIndicator").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("bg-danger border border-light rounded-circle")))
    }

    @Test
    @DisplayName("badge — failure older than 5 min → no red dot")
    fun badgeNoDotForStaleFailure() {
        saveJobRun(
            jobName = "nlpProcessingJob",
            status = RunStatus.FAILED,
            startTime = OffsetDateTime.now().minusHours(2),
            finishTime = OffsetDateTime.now().minusHours(1),
        )

        mockMvc.perform(
            get("/nav/jobIndicator").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("bg-danger border border-light rounded-circle"))))
    }

    @Test
    @DisplayName("details — empty list → 'No running jobs right now.'")
    fun detailsEmptyState() {
        mockMvc.perform(
            get("/nav/jobIndicator/details").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"navJobIndicatorDetails\"")))
            .andExpect(content().string(containsString("No running jobs right now")))
    }

    @Test
    @DisplayName("details — RUNNING email sync → row with account detail link and started-at time")
    fun detailsListsRunningExecutionWithLink() {
        saveJobRun(
            jobName = "emailQuickSync_42",
            status = RunStatus.RUNNING,
            startTime = OffsetDateTime.now().minusSeconds(30),
            currentStepName = "INBOX",
            springBatchJobExecutionId = "1234",
        )

        mockMvc.perform(
            get("/nav/jobIndicator/details").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("emailQuickSync_42")))
            .andExpect(content().string(containsString("INBOX")))
            .andExpect(content().string(containsString("href=\"/emailAccounts/42\"")))
            .andExpect(content().string(containsString(">RUNNING<")))
    }

    @Test
    @DisplayName("details — generic job name with execution id → batch detail link fallback")
    fun detailsGenericJobFallsBackToBatchLink() {
        saveJobRun(
            jobName = "fsCrawlJob",
            status = RunStatus.RUNNING,
            startTime = OffsetDateTime.now().minusSeconds(15),
            springBatchJobExecutionId = "9001",
        )

        mockMvc.perform(
            get("/nav/jobIndicator/details").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/batch/9001\"")))
    }

    private fun saveJobRun(
        jobName: String,
        status: RunStatus,
        startTime: OffsetDateTime? = OffsetDateTime.now().minusSeconds(10),
        finishTime: OffsetDateTime? = null,
        currentStepName: String? = null,
        springBatchJobExecutionId: String? = null,
    ): JobRun {
        val run = JobRun().apply {
            this.jobName = jobName
            this.runStatus = status
            this.startTime = startTime
            this.finishTime = finishTime
            this.currentStepName = currentStepName
            this.springBatchJobExecutionId = springBatchJobExecutionId
            this.uri = "job-run:$jobName:${System.nanoTime()}"
            this.version = 0L
            this.processedCount = 0L
        }
        return jobRunRepository.save(run)
    }
}
