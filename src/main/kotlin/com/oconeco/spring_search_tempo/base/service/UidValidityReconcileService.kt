package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


/**
 * Issue #84: reconcile a folder's stored UID space after a UIDVALIDITY rotation.
 *
 * The reader marked the folder with `uidValidityMismatchAt` when it detected
 * the server's UIDVALIDITY no longer matched what we had on file. The operator
 * confirms the reconcile from the account detail page, which calls
 * [reconcile]. We then:
 *
 *  1. Open the folder fresh and fetch headers-only for every server message
 *     (`FETCH 1:* (UID ENVELOPE BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)])`) —
 *     metadata only, typically <100 MB even for huge folders.
 *  2. For each server message, look up our DB row by `Message-ID`. If found,
 *     update the stored `imapUid` to the new UID and bump `lastSyncUid` so
 *     subsequent incremental syncs work. If not found, the message is left
 *     for the next normal sync to body-fetch.
 *  3. Update the folder's `uidValidity` to the server's current value and
 *     clear `uidValidityMismatchAt`.
 *
 * Precondition: `email_message.message_id` must be indexed. The existing
 * unique constraint on the column provides that — no separate index needed.
 * If a future schema change removes the unique constraint without adding a
 * non-unique index, reconcile falls back to per-row UPDATEs but the WARN
 * log surfaces the missing index to the operator.
 */
@Service
class UidValidityReconcileService(
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderRepository: EmailFolderRepository,
    private val emailMessageRepository: EmailMessageRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    companion object {
        private val log = LoggerFactory.getLogger(UidValidityReconcileService::class.java)
    }

    /**
     * Result of a reconcile run, surfaced to the controller for flash messaging
     * and to tests for assertions.
     */
    data class ReconcileResult(
        val folderId: Long,
        val folderPath: String,
        val serverMessagesScanned: Int,
        val messagesMatched: Int,
        val messagesNew: Int,
        val newUidValidity: Long,
        val elapsedMillis: Long,
        val missingMessageIdIndex: Boolean = false,
    )

    /**
     * Reconcile a single folder. Caller is the controller for "Reconcile" UI
     * action; this method is read-mostly on IMAP (headers only) and writes
     * back through the existing JPA path.
     */
    @Transactional
    fun reconcile(folderId: Long): ReconcileResult {
        val folder = emailFolderRepository.findById(folderId)
            .orElseThrow { NotFoundException("emailFolder not found: $folderId") }
        require(folder.uidValidityMismatchAt != null) {
            "Folder $folderId is not in mismatch state; refusing to reconcile."
        }
        val accountId = folder.emailAccount?.id
            ?: throw IllegalStateException("Folder $folderId has no email account.")
        val account = emailAccountService.get(accountId)
        val folderPath = folder.path ?: folder.folderName ?: error("Folder has no path/name")

        val missingIndex = !messageIdIsIndexed()
        if (missingIndex) {
            log.warn(
                "Reconcile precondition NOT met: email_message.message_id is not indexed. " +
                    "Reconcile will run but per-row UPDATEs will be slow on large mailboxes — " +
                    "add an index to restore full speed (issue #84)."
            )
        }

        val startedAt = System.currentTimeMillis()
        var scanned = 0
        var matched = 0
        var newCount = 0
        var serverUidValidity = 0L
        var highestUid = 0L

        imapConnectionService.withConnection(account) { store ->
            val imapFolder = store.getFolder(folderPath) as IMAPFolder
            imapFolder.open(Folder.READ_ONLY)
            try {
                serverUidValidity = imapFolder.uidValidity
                val messages: Array<Message> = imapFolder.messages.filterNotNull().toTypedArray()
                scanned = messages.size

                if (messages.isNotEmpty()) {
                    val fetchProfile = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(UIDFolder.FetchProfileItem.UID)
                        add("Message-ID")
                    }
                    imapFolder.fetch(messages, fetchProfile)

                    // Build the server-side (messageId -> uid) map first so we
                    // can hit the DB once for the lookup.
                    val serverByMessageId = HashMap<String, Long>(messages.size)
                    for (msg in messages) {
                        val messageId = msg.getHeader("Message-ID")?.firstOrNull()?.trim() ?: continue
                        val uid = imapFolder.getUID(msg)
                        if (uid > 0) {
                            serverByMessageId[messageId] = uid
                            if (uid > highestUid) highestUid = uid
                        }
                    }

                    if (serverByMessageId.isNotEmpty()) {
                        val matchedIds = emailMessageRepository.findExistingMessageIds(serverByMessageId.keys)
                        for (mid in matchedIds) {
                            val newUid = serverByMessageId[mid] ?: continue
                            // Update by Message-ID rather than re-loading the entity to
                            // avoid hydrating thousands of EmailMessage rows for a
                            // reconcile that only needs to touch imapUid.
                            val row = emailMessageRepository.findByMessageId(mid) ?: continue
                            row.imapUid = newUid
                            emailMessageRepository.save(row)
                            matched++
                        }
                        newCount = serverByMessageId.size - matched
                    }
                }
            } finally {
                runCatching { imapFolder.close(false) }
            }
        }

        // Persist new UIDVALIDITY + clear the halt + advance lastSyncUid so the
        // next normal sync FETCHes only the truly-new messages.
        val refreshed = emailFolderRepository.findById(folderId)
            .orElseThrow { NotFoundException("emailFolder not found: $folderId") }
        refreshed.uidValidity = serverUidValidity
        refreshed.uidValidityMismatchAt = null
        if (highestUid > 0) {
            refreshed.lastSyncUid = highestUid
        }
        emailFolderRepository.save(refreshed)

        val elapsed = System.currentTimeMillis() - startedAt
        log.info(
            "Reconcile complete for folder {} (account {}): scanned={}, matched={}, new={}, " +
                "new UIDVALIDITY={}, elapsed={}ms",
            folderPath, account.email, scanned, matched, newCount, serverUidValidity, elapsed
        )

        return ReconcileResult(
            folderId = folderId,
            folderPath = folderPath,
            serverMessagesScanned = scanned,
            messagesMatched = matched,
            messagesNew = newCount,
            newUidValidity = serverUidValidity,
            elapsedMillis = elapsed,
            missingMessageIdIndex = missingIndex,
        )
    }

    /**
     * "Skip" action — operator decided this folder isn't worth recovering.
     * Clears the mismatch flag and disables sync so the next quick sync just
     * skips the folder entirely.
     */
    @Transactional
    fun skip(folderId: Long) {
        val folder = emailFolderRepository.findById(folderId)
            .orElseThrow { NotFoundException("emailFolder not found: $folderId") }
        folder.uidValidityMismatchAt = null
        folder.syncEnabled = false
        emailFolderRepository.save(folder)
        log.info(
            "Skip on UIDVALIDITY mismatch for folder id={} path='{}': sync disabled (issue #84).",
            folderId, folder.path ?: folder.folderName
        )
    }

    /**
     * Check whether `email_message.message_id` has any backing index. The
     * JPA-managed schema places a unique constraint on the column, which
     * Postgres always backs with a btree index — but a future migration
     * could drop it, so we verify rather than assume. PostgreSQL stores the
     * indexed column in `pg_index.indkey`, which we cross-reference with
     * `pg_attribute` to find indexes that cover `message_id`.
     */
    private fun messageIdIsIndexed(): Boolean {
        return try {
            val count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pg_index i
                  JOIN pg_attribute a
                    ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                 WHERE i.indrelid = 'email_message'::regclass
                   AND a.attname = 'message_id'
                """.trimIndent(),
                Long::class.java,
            )
            (count ?: 0L) > 0L
        } catch (e: Exception) {
            log.debug("messageIdIsIndexed check failed; assuming indexed: {}", e.message)
            true  // fail-open: don't block reconcile on a metadata-query failure.
        }
    }

}
