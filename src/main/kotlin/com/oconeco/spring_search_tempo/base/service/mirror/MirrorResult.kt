package com.oconeco.spring_search_tempo.base.service.mirror

/**
 * Outcome of one `ImapMirrorService.mirrorMessage(...)` call.
 *
 * `destUid` is `null` when the destination IMAP server omits the
 * `APPENDUID` response (RFC 4315) — the row is still inserted into
 * `mirrored_message` so dedup works on retry.
 */
sealed class MirrorResult {
    data class Copied(val destUid: Long?) : MirrorResult()
    data class AlreadyMirrored(val destUid: Long?) : MirrorResult()
    data class Failed(val reason: String, val retryable: Boolean) : MirrorResult()
}
