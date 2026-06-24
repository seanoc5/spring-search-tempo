package com.oconeco.spring_search_tempo.base.util

import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress


/**
 * Normalize email addresses for contact aggregation (issue #146 Phase 1).
 *
 * Rules:
 *  - Lowercase the address (RFC says local-part is case-sensitive but in
 *    practice no MTA enforces it; collapsing avoids near-duplicate contacts).
 *  - Strip Gmail-style `+suffix` plus-addressing from the local-part so
 *    `seanoc5+newsletter@gmail.com` and `seanoc5@gmail.com` aggregate together.
 *  - Trim whitespace and the surrounding `<...>` of an RFC 5322 mailbox
 *    (`Display Name <local@host>`); the personal display name is returned
 *    separately so callers can keep the latest non-null one.
 */
object EmailAddressNormalizer {

    data class ParsedAddress(
        val normalizedAddress: String,
        val displayName: String?
    )

    /**
     * Parse a single header-style address (`John Doe <john@example.com>` or
     * `john@example.com`) and normalize it. Returns `null` if the input is
     * blank or not a recognizable email address.
     */
    fun parse(raw: String?): ParsedAddress? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val (address, personal) = try {
            val parsed = InternetAddress(trimmed, false)
            parsed.address to parsed.personal?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: AddressException) {
            // Fall back to a naive split on the angle-bracket form so we still
            // get something useful when senders generate non-strict headers.
            val angleStart = trimmed.indexOf('<')
            val angleEnd = trimmed.indexOf('>')
            if (angleStart >= 0 && angleEnd > angleStart) {
                val addr = trimmed.substring(angleStart + 1, angleEnd).trim()
                val personal = trimmed.substring(0, angleStart).trim().trim('"').takeIf { it.isNotEmpty() }
                addr to personal
            } else {
                trimmed to null
            }
        }

        if (address.isNullOrBlank() || !address.contains('@')) return null

        val normalized = normalize(address) ?: return null
        return ParsedAddress(normalized, personal)
    }

    /**
     * Normalize a bare email address (no display name). Returns `null` if the
     * input is not a recognizable email address.
     */
    fun normalize(rawAddress: String?): String? {
        val trimmed = rawAddress?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty() || !trimmed.contains('@')) return null

        val atIndex = trimmed.lastIndexOf('@')
        val local = trimmed.substring(0, atIndex)
        val domain = trimmed.substring(atIndex + 1)
        if (local.isEmpty() || domain.isEmpty()) return null

        val plusIndex = local.indexOf('+')
        val cleanLocal = if (plusIndex >= 0) local.substring(0, plusIndex) else local
        if (cleanLocal.isEmpty()) return null

        return "$cleanLocal@$domain"
    }
}
