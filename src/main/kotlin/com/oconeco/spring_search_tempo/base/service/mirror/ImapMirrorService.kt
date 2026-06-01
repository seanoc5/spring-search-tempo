package com.oconeco.spring_search_tempo.base.service.mirror

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.domain.MirroredMessage
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.io.OutputStream
import java.time.OffsetDateTime
import java.util.Date
import java.util.Properties

/**
 * Lossless message copy from one IMAP folder to another, keyed by
 * `Message-ID` for idempotent re-runs. See ADR-005 and issue #23.
 *
 * Per-message flow (one round-trip per leg):
 *
 *   FETCH source → BODY[] + INTERNALDATE + FLAGS + Message-ID
 *   look up MirroredMessage(mirrorConfigId, messageId)
 *     → present? return AlreadyMirrored; do NOT touch destination
 *   throttle via MirrorRateLimiter (per-config APPENDs/sec)
 *   APPEND to dest folder, preserving INTERNALDATE + FLAGS
 *   insert MirroredMessage row (dest UID may be null if APPENDUID absent)
 *
 * Streaming: the destination APPEND is fed by [StreamingMirrorMessage],
 * which delegates `writeTo` to the source `IMAPMessage`. That keeps large
 * attachments off the JVM heap; nothing in this method buffers the full
 * RFC822 body into memory.
 *
 * Messages lacking an RFC 5322 `Message-ID` header get a synthetic key
 * (`synthetic:<configId>:<folder>:<uid>`) so dedup still works.
 */
@Service
class ImapMirrorService(
    private val mirrorConfigRepository: MirrorConfigRepository,
    private val mirroredMessageRepository: MirroredMessageRepository,
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val rateLimiter: MirrorRateLimiter
) {

    companion object {
        private val log = LoggerFactory.getLogger(ImapMirrorService::class.java)
    }

    @Transactional
    fun mirrorMessage(
        mirrorConfigId: Long,
        sourceFolder: String,
        sourceUid: Long,
        destFolder: String
    ): MirrorResult {
        val config = mirrorConfigRepository.findById(mirrorConfigId).orElse(null)
            ?: return MirrorResult.Failed("MirrorConfig $mirrorConfigId not found", retryable = false)

        val sourceAccountId = config.sourceAccountId
            ?: return MirrorResult.Failed("MirrorConfig $mirrorConfigId has no sourceAccountId", retryable = false)
        val destAccountId = config.destAccountId
            ?: return MirrorResult.Failed("MirrorConfig $mirrorConfigId has no destAccountId", retryable = false)

        val srcAccount = try {
            emailAccountService.get(sourceAccountId)
        } catch (e: Exception) {
            return MirrorResult.Failed("Source account $sourceAccountId not found", retryable = false)
        }
        val dstAccount = try {
            emailAccountService.get(destAccountId)
        } catch (e: Exception) {
            return MirrorResult.Failed("Destination account $destAccountId not found", retryable = false)
        }

        try {
            return imapConnectionService.withConnection(srcAccount) { srcStore ->
                imapConnectionService.withConnection(dstAccount) { dstStore ->
                    val srcFolderObj = srcStore.getFolder(sourceFolder) as IMAPFolder
                    srcFolderObj.open(Folder.READ_ONLY)
                    try {
                        val srcMsg = srcFolderObj.getMessageByUID(sourceUid) as? IMAPMessage
                            ?: return@withConnection MirrorResult.Failed(
                                "Source UID $sourceUid not found in folder '$sourceFolder'",
                                retryable = false
                            )

                        // Single FETCH round-trip for FLAGS + INTERNALDATE + UID
                        // + Message-ID header. Body is fetched lazily later via
                        // `writeTo` so it streams straight into the APPEND
                        // literal instead of buffering in the heap.
                        srcFolderObj.fetch(arrayOf(srcMsg), FetchProfile().apply {
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                            add(IMAPFolder.FetchProfileItem.INTERNALDATE)
                            add("Message-ID")
                        })

                        val messageId = extractMessageId(srcMsg)
                            ?: syntheticMessageId(mirrorConfigId, sourceFolder, sourceUid)

                        mirroredMessageRepository
                            .findByMirrorConfigIdAndMessageId(mirrorConfigId, messageId)
                            ?.let {
                                log.debug(
                                    "Skip mirror: message {} already mirrored under config {} (destUid={})",
                                    messageId, mirrorConfigId, it.destUid
                                )
                                return@withConnection MirrorResult.AlreadyMirrored(it.destUid)
                            }

                        rateLimiter.acquire(mirrorConfigId, config.appendRateLimitPerSecond)

                        val dstFolderObj = dstStore.getFolder(destFolder) as IMAPFolder
                        dstFolderObj.open(Folder.READ_WRITE)
                        try {
                            val internalDate: Date = try {
                                srcMsg.receivedDate ?: srcMsg.sentDate ?: Date()
                            } catch (e: Exception) {
                                Date()
                            }
                            val flags: Flags = try {
                                Flags(srcMsg.flags)
                            } catch (e: Exception) {
                                Flags()
                            }

                            val wrapper = StreamingMirrorMessage(
                                session = Session.getInstance(Properties()),
                                source = srcMsg,
                                internalDate = internalDate,
                                sourceFlags = flags
                            )

                            val destUid: Long? = try {
                                val appendUids = dstFolderObj.appendUIDMessages(arrayOf(wrapper))
                                appendUids?.firstOrNull()?.uid?.takeIf { it > 0 }
                            } catch (nsme: NoSuchMethodError) {
                                dstFolderObj.appendMessages(arrayOf(wrapper))
                                null
                            }

                            mirroredMessageRepository.save(
                                MirroredMessage().apply {
                                    this.mirrorConfigId = mirrorConfigId
                                    this.sourceFolder = sourceFolder
                                    this.sourceUid = sourceUid
                                    this.destFolder = destFolder
                                    this.destUid = destUid
                                    this.messageId = messageId
                                    this.mirroredAt = OffsetDateTime.now()
                                }
                            )

                            log.info(
                                "Mirrored {}/{} → {}/{} for config {} (messageId={}, destUid={})",
                                sourceFolder, sourceUid, destFolder, destUid ?: "?",
                                mirrorConfigId, messageId, destUid
                            )
                            MirrorResult.Copied(destUid)
                        } finally {
                            try { dstFolderObj.close(false) } catch (_: Exception) {}
                        }
                    } finally {
                        try { srcFolderObj.close(false) } catch (_: Exception) {}
                    }
                }
            }
        } catch (ioe: IOException) {
            log.warn("Mirror failed (retryable) for config={} src={}/{}: {}",
                mirrorConfigId, sourceFolder, sourceUid, ioe.message)
            return MirrorResult.Failed("APPEND I/O failure: ${ioe.message}", retryable = true)
        } catch (e: jakarta.mail.MessagingException) {
            val retryable = e.cause is IOException
            log.warn("Mirror failed (retryable={}) for config={} src={}/{}: {}",
                retryable, mirrorConfigId, sourceFolder, sourceUid, e.message)
            return MirrorResult.Failed("APPEND IMAP error: ${e.message}", retryable = retryable)
        } catch (e: Exception) {
            log.error("Mirror failed (non-retryable) for config={} src={}/{}: {}",
                mirrorConfigId, sourceFolder, sourceUid, e.message, e)
            return MirrorResult.Failed("Mirror error: ${e.message}", retryable = false)
        }
    }

    private fun extractMessageId(msg: IMAPMessage): String? {
        val header = try {
            msg.getHeader("Message-ID")
        } catch (e: Exception) {
            return null
        }
        return header?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun syntheticMessageId(mirrorConfigId: Long, sourceFolder: String, sourceUid: Long): String =
        "synthetic:$mirrorConfigId:$sourceFolder:$sourceUid"

    /**
     * MimeMessage wrapper used as the IMAP APPEND literal. Delegates the
     * actual RFC822 octet stream to the source `IMAPMessage` so the body
     * is pumped straight from source to destination without ever sitting
     * in a JVM byte array. Carries the source `INTERNALDATE` and `FLAGS`
     * across so the destination APPEND preserves them.
     */
    private class StreamingMirrorMessage(
        session: Session,
        private val source: MimeMessage,
        private val internalDate: Date,
        private val sourceFlags: Flags
    ) : MimeMessage(session) {

        init {
            try {
                setFlags(sourceFlags, true)
            } catch (_: Exception) { /* best-effort; flags also passed via APPEND */ }
        }

        override fun getReceivedDate(): Date = internalDate

        override fun getFlags(): Flags = sourceFlags

        override fun getSize(): Int = try {
            source.size
        } catch (_: Exception) {
            -1
        }

        override fun writeTo(os: OutputStream) {
            source.writeTo(os)
        }

        override fun writeTo(os: OutputStream, ignoreList: Array<String>?) {
            source.writeTo(os, ignoreList)
        }
    }
}
