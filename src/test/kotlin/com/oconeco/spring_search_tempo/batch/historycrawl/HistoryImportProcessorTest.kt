package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import com.oconeco.spring_search_tempo.base.domain.BrowserSourceType
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxHistoryData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.OffsetDateTime


/**
 * Unit tests for [HistoryImportProcessor] — covers the create-vs-update
 * branch, source-type assignment, and watermark tracking.
 */
class HistoryImportProcessorTest {

    private val repository: BrowserBookmarkRepository = mock(BrowserBookmarkRepository::class.java)

    private fun processor() = HistoryImportProcessor(
        browserBookmarkRepository = repository,
        browserProfileId = 42L
    )

    private fun entry(
        placeId: Long = 1L,
        url: String = "https://news.example/a",
        title: String? = "title",
        visitCount: Int = 3,
        prTime: Long = 1_700_000_000_000_000L
    ) = FirefoxHistoryData(
        placeId = placeId,
        url = url,
        title = title,
        visitCount = visitCount,
        lastVisitDate = OffsetDateTime.now(),
        lastVisitDatePrTime = prTime,
        frecency = 100
    )

    @Test
    fun `process emits Create with HISTORY sourceType for unknown URL`() {
        `when`(repository.findByUrl("https://news.example/a")).thenReturn(null)

        val result = processor().process(entry())

        assertThat(result).isInstanceOf(HistoryProcessorResult.Create::class.java)
        val create = result as HistoryProcessorResult.Create
        assertThat(create.dto.sourceType).isEqualTo(BrowserSourceType.HISTORY)
        assertThat(create.dto.url).isEqualTo("https://news.example/a")
        assertThat(create.dto.uri).isEqualTo("firefox:history:1")
        assertThat(create.dto.firefoxPlaceId).isEqualTo(1L)
        assertThat(create.dto.firefoxBookmarkId).isNull()
        assertThat(create.dto.domain).isEqualTo("news.example")
        assertThat(create.dto.scheme).isEqualTo("https")
        assertThat(create.dto.visitCount).isEqualTo(3)
        assertThat(create.dto.browserProfileId).isEqualTo(42L)
    }

    @Test
    fun `process emits Update for known URL`() {
        val existing = BrowserBookmark().apply {
            id = 99L
            url = "https://news.example/a"
            sourceType = BrowserSourceType.BOOKMARK
            visitCount = 1
        }
        `when`(repository.findByUrl("https://news.example/a")).thenReturn(existing)

        val result = processor().process(entry(visitCount = 17))

        assertThat(result).isInstanceOf(HistoryProcessorResult.Update::class.java)
        val update = result as HistoryProcessorResult.Update
        assertThat(update.existingId).isEqualTo(99L)
        assertThat(update.visitCount).isEqualTo(17)
        // Update form intentionally carries no sourceType — writer will not
        // touch it, so existing BOOKMARK rows stay BOOKMARK.
    }

    @Test
    fun `processor tracks max PRTime watermark across all entries`() {
        `when`(repository.findByUrl(anyString())).thenReturn(null)
        val p = processor()
        p.process(entry(placeId = 1, url = "https://a.example", prTime = 100L))
        p.process(entry(placeId = 2, url = "https://b.example", prTime = 500L))
        p.process(entry(placeId = 3, url = "https://c.example", prTime = 300L))

        val stats = p.getStats()
        assertThat(stats.maxVisitPrTime).isEqualTo(500L)
        assertThat(stats.created).isEqualTo(3)
        assertThat(stats.updated).isZero()
    }

    @Test
    fun `processor counts created vs updated separately`() {
        `when`(repository.findByUrl("https://existing.example")).thenReturn(
            BrowserBookmark().apply { id = 1L; url = "https://existing.example" }
        )
        `when`(repository.findByUrl("https://new.example")).thenReturn(null)

        val p = processor()
        p.process(entry(placeId = 1, url = "https://existing.example"))
        p.process(entry(placeId = 2, url = "https://new.example"))

        val stats = p.getStats()
        assertThat(stats.created).isEqualTo(1)
        assertThat(stats.updated).isEqualTo(1)
        assertThat(stats.processed).isEqualTo(2)
    }
}
