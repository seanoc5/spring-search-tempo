package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.config.ErrorDisplayConfig
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountSummaryDTO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Unit tests for [EmailErrorDisplayHelper] (issue #59).
 *
 * The helper drives the "Last Error" UI on email account pages — both the human-readable
 * age phrase ("3 hours ago") and the visibility cutoff that suppresses fossil errors.
 */
@DisplayName("EmailErrorDisplayHelper — age formatting + stale-cutoff (issue #59)")
class EmailErrorDisplayHelperTest {

    private val now: OffsetDateTime = OffsetDateTime.of(2026, 6, 5, 12, 0, 0, 0, ZoneOffset.UTC)
    private val fixedClock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    private fun helper(maxAgeDays: Int = 7): EmailErrorDisplayHelper {
        val cfg = EmailConfiguration(errorDisplay = ErrorDisplayConfig(maxAgeDays = maxAgeDays))
        return EmailErrorDisplayHelper(cfg, fixedClock)
    }

    @Nested
    inner class FormatAge {
        @Test fun `seconds becomes 'just now'`() {
            assertThat(helper().formatAge(now.minusSeconds(15))).isEqualTo("just now")
        }

        @Test fun `1 minute uses singular form`() {
            assertThat(helper().formatAge(now.minusMinutes(1))).isEqualTo("1 minute ago")
        }

        @Test fun `multiple minutes are pluralised`() {
            assertThat(helper().formatAge(now.minusMinutes(42))).isEqualTo("42 minutes ago")
        }

        @Test fun `1 hour uses singular form`() {
            assertThat(helper().formatAge(now.minusHours(1))).isEqualTo("1 hour ago")
        }

        @Test fun `multiple hours are pluralised`() {
            assertThat(helper().formatAge(now.minusHours(3))).isEqualTo("3 hours ago")
        }

        @Test fun `1 day uses singular form`() {
            assertThat(helper().formatAge(now.minusDays(1))).isEqualTo("1 day ago")
        }

        @Test fun `multiple days are pluralised`() {
            assertThat(helper().formatAge(now.minusDays(3))).isEqualTo("3 days ago")
        }
    }

    @Nested
    inner class VisibilityCutoff {
        @Test fun `null error means not visible`() {
            val dto = EmailAccountDTO().apply { lastError = null; lastErrorAt = null }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
            assertThat(dto.errorAgeDescription).isNull()
        }

        @Test fun `blank error means not visible`() {
            val dto = EmailAccountDTO().apply { lastError = "   "; lastErrorAt = now.minusHours(1) }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
        }

        @Test fun `error without timestamp is not visible`() {
            val dto = EmailAccountDTO().apply { lastError = "boom"; lastErrorAt = null }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
        }

        @Test fun `2-day-old error is visible with maxAgeDays=7`() {
            val dto = EmailAccountDTO().apply {
                lastError = "boom"
                lastErrorAt = now.minusDays(2)
            }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isTrue()
            assertThat(dto.errorAgeDescription).isEqualTo("2 days ago")
        }

        @Test fun `8-day-old error is hidden with maxAgeDays=7`() {
            val dto = EmailAccountDTO().apply {
                lastError = "fossil"
                lastErrorAt = now.minusDays(8)
            }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
            // age phrase still populated for any debug/tooltip use
            assertThat(dto.errorAgeDescription).isEqualTo("8 days ago")
        }

        @Test fun `exactly at threshold (7 days) is hidden`() {
            // ChronoUnit.DAYS.between returns 7 → not strictly less than 7 → hidden.
            val dto = EmailAccountDTO().apply {
                lastError = "boom"
                lastErrorAt = now.minusDays(7)
            }
            helper().apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
        }

        @Test fun `summary DTO is handled the same as full DTO`() {
            val summary = EmailAccountSummaryDTO().apply {
                lastError = "boom"
                lastErrorAt = now.minusHours(3)
            }
            helper().apply(summary)
            assertThat(summary.errorDisplayVisible).isTrue()
            assertThat(summary.errorAgeDescription).isEqualTo("3 hours ago")
        }

        @Test fun `maxAgeDays is configurable`() {
            val dto = EmailAccountDTO().apply {
                lastError = "boom"
                lastErrorAt = now.minusDays(5)
            }
            helper(maxAgeDays = 3).apply(dto)
            assertThat(dto.errorDisplayVisible).isFalse()
        }
    }
}
