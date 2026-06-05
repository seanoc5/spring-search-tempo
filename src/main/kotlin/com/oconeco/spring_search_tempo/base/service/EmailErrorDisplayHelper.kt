package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountSummaryDTO
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Computes user-facing display state for EmailAccount.lastError (issue #59).
 *
 * Renders age as a short human phrase ("3 hours ago", "2 days ago") and decides whether
 * the error card is fresh enough to render at all. Errors older than
 * `app.email.error-display.max-age-days` (default 7) are suppressed — the DB row is kept
 * for debugging, but the user no longer sees fossil errors months after the underlying
 * code path was fixed.
 */
@Component
class EmailErrorDisplayHelper(
    private val emailConfiguration: EmailConfiguration,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    fun apply(dto: EmailAccountDTO) {
        dto.errorAgeDescription = dto.lastErrorAt?.let { formatAge(it) }
        dto.errorDisplayVisible = isVisible(dto.lastError, dto.lastErrorAt)
    }

    fun apply(dto: EmailAccountSummaryDTO) {
        dto.errorAgeDescription = dto.lastErrorAt?.let { formatAge(it) }
        dto.errorDisplayVisible = isVisible(dto.lastError, dto.lastErrorAt)
    }

    private fun isVisible(message: String?, at: OffsetDateTime?): Boolean {
        if (message.isNullOrBlank() || at == null) return false
        val ageDays = ChronoUnit.DAYS.between(at, OffsetDateTime.now(clock))
        return ageDays < emailConfiguration.errorDisplay.maxAgeDays
    }

    fun formatAge(at: OffsetDateTime): String {
        val now = OffsetDateTime.now(clock)
        val seconds = ChronoUnit.SECONDS.between(at, now).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> plural(seconds / 60, "minute")
            seconds < 86400 -> plural(seconds / 3600, "hour")
            else -> plural(seconds / 86400, "day")
        }
    }

    private fun plural(n: Long, unit: String): String =
        if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
}
