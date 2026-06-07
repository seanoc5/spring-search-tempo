package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.EmailFolder
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Folder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Discovers IMAP folders for an account and persists them as `EmailFolder` rows.
 *
 * Runs `IMAPFolder.list("*")` (LIST-ALL) on first connect and whenever the
 * user requests a refresh from the UI. Each row carries the IMAP attribute
 * flags (`\HasChildren`, `\Noselect`, ...) as boolean columns so downstream
 * code (UI, sync orchestrator) does not have to re-parse raw attribute
 * strings — server-specific casing variations are normalised here.
 *
 * Folder names are surfaced through Jakarta Mail's `getFullName()`, which
 * already decodes IMAP modified UTF-7 to UTF-8.
 */
@Service
class EmailFolderSyncService(
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderRepository: EmailFolderRepository,
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailFolderSyncService::class.java)
    }

    /**
     * Enumerate every folder visible to the account and upsert rows.
     *
     * Returns the IDs of folders that exist after enumeration (both newly
     * created and pre-existing). Folders that vanished from the server are
     * NOT deleted here — they may be re-created on rename, and we want to
     * preserve `lastSyncUid` / message history across server-side renames.
     */
    fun enumerateFolders(accountId: Long): List<Long> {
        return enumerate(accountId, optInNewFolders = true).savedIds
    }

    /**
     * Issue #84: re-enumerate folders on an already-enumerated account.
     *
     * Differs from [enumerateFolders] in two ways:
     *
     *  a. Newly-discovered folders are persisted with `syncEnabled=false`
     *     ("opt-in"), so the operator confirms each one from the folder list
     *     before sync starts pulling its history. This matches issue #3
     *     semantics (user controls what gets crawled).
     *  b. Returns the IDs of the *newly created* folders so the UI can show
     *     an "N new folders discovered since last sync" banner.
     *
     * Both [enumerateFolders] and this method update the account's
     * `lastFolderEnumeratedAt` so the orchestrator's drift-detection clock
     * resets.
     */
    fun reEnumerateFolders(accountId: Long): ReEnumerationResult {
        val result = enumerate(accountId, optInNewFolders = false)
        return ReEnumerationResult(
            allSavedIds = result.savedIds,
            newlyDiscoveredFolderIds = result.newlyCreatedIds
        )
    }

    private fun enumerate(accountId: Long, optInNewFolders: Boolean): EnumerationOutcome {
        val account = emailAccountService.get(accountId)
        log.info("Enumerating IMAP folders for {}", account.email)

        // Fetch the descriptors outside any DB transaction — IMAP `LIST *` can be
        // slow for large mailboxes and we don't want to hold a connection while
        // committing rows.
        val descriptors = imapConnectionService.withConnection(account) { store ->
            val rawFolders = store.defaultFolder.list("*")
            log.info("[{}] Server returned {} folders from LIST *", account.email, rawFolders.size)
            rawFolders.mapNotNull { describe(it) }
        }

        return persistDescriptors(
            accountId,
            account.email ?: "<unknown>",
            descriptors,
            optInNewFolders = optInNewFolders,
        )
    }

    @Transactional
    internal fun persistDescriptors(
        accountId: Long,
        accountEmail: String,
        descriptors: List<FolderDescriptor>,
        optInNewFolders: Boolean = true,
    ): EnumerationOutcome {
        val emailAccount = emailAccountRepository.findById(accountId)
            .orElseThrow { NotFoundException("emailAccount not found: $accountId") }
        val now = OffsetDateTime.now()
        val savedIds = mutableListOf<Long>()
        val newlyCreatedIds = mutableListOf<Long>()

        for (descriptor in descriptors) {
            val existing = emailFolderRepository
                .findByEmailAccountIdAndPath(accountId, descriptor.path)
                ?: emailFolderRepository
                    .findByEmailAccountIdAndFolderName(accountId, descriptor.folderName)

            val isNew = existing == null
            val entity = existing ?: EmailFolder().apply {
                this.emailAccount = emailAccount
                this.uri = "email-folder://${accountEmail}/${descriptor.path}"
                this.status = Status.NEW
                this.analysisStatus = AnalysisStatus.LOCATE
                this.version = 0L
                // Default syncEnabled. Initial enumeration opts new folders IN
                // (selectable folders are useful by default). Re-enumeration on
                // an already-enumerated account opts them OUT so the operator
                // confirms newly-server-side-created folders before they start
                // pulling history (issue #84, acceptance criterion f).
                this.syncEnabled = if (optInNewFolders) !descriptor.noselect else false
            }

            entity.applyDescriptor(descriptor, now)

            val saved = emailFolderRepository.save(entity)
            saved.id?.let {
                savedIds.add(it)
                if (isNew) newlyCreatedIds.add(it)
            }
        }

        // Issue #84: stamp the account so the orchestrator's drift detector
        // resets its clock. Done inside the same transaction so a partial
        // failure can't claim "we enumerated" without actually persisting.
        emailAccount.lastFolderEnumeratedAt = now
        emailAccountRepository.save(emailAccount)

        log.info(
            "[{}] Persisted {} folders ({} newly discovered)",
            accountEmail, savedIds.size, newlyCreatedIds.size,
        )
        return EnumerationOutcome(savedIds, newlyCreatedIds)
    }

    /**
     * Internal upsert return type — see [reEnumerateFolders] for the public
     * shape that the orchestrator and controllers consume.
     */
    internal data class EnumerationOutcome(
        val savedIds: List<Long>,
        val newlyCreatedIds: List<Long>,
    )

    /**
     * Issue #84: result surfaced from [reEnumerateFolders] for the orchestrator
     * and UI banner logic.
     */
    data class ReEnumerationResult(
        val allSavedIds: List<Long>,
        val newlyDiscoveredFolderIds: List<Long>,
    )

    /**
     * Quick check used by the orchestrator: does this account have any enumerated
     * folder rows? Used to decide whether to fall back to the legacy
     * `application.yml` `quickSyncFolders` list.
     */
    fun hasEnumeratedFolders(accountId: Long): Boolean =
        emailFolderRepository.countByEmailAccountId(accountId) > 0

    internal fun describe(folder: Folder): FolderDescriptor? {
        val fullName = folder.fullName?.takeIf { it.isNotBlank() } ?: return null
        val attrs: List<String> = when (folder) {
            is IMAPFolder -> runCatching { folder.attributes.toList() }.getOrDefault(emptyList())
            else -> emptyList()
        }
        val normalized = attrs.map { it.lowercase() }
        val noselect = normalized.any { it == "\\noselect" || it == "noselect" }
        val noinferiors = normalized.any { it == "\\noinferiors" || it == "noinferiors" }
        val hasChildren = normalized.any { it == "\\haschildren" || it == "haschildren" }
        val marked = normalized.any { it == "\\marked" || it == "marked" }
        val unmarked = normalized.any { it == "\\unmarked" || it == "unmarked" }
        val isTrashAttr = normalized.any { it == "\\trash" || it == "trash" }
        val isSentAttr = normalized.any { it == "\\sent" || it == "sent" }
        val isDraftsAttr = normalized.any { it == "\\drafts" || it == "drafts" }
        val isSpamAttr = normalized.any { it == "\\junk" || it == "junk" }
        val isArchiveAttr = normalized.any { it == "\\archive" || it == "archive" || it == "\\all" || it == "all" }

        // IMAP returns NUL for folders with no hierarchy (top-level / flat
        // namespace). Treat NUL — and a literal space, which we use as our
        // "no separator" fallback — as "no delimiter".
        val delimiterChar = runCatching { folder.separator }.getOrDefault('/')
        val hasDelimiter = delimiterChar.code != 0 && delimiterChar != ' '
        val leaf = if (hasDelimiter && fullName.contains(delimiterChar)) {
            fullName.substringAfterLast(delimiterChar)
        } else {
            fullName
        }

        return FolderDescriptor(
            path = fullName,
            folderName = leaf,
            delimiter = if (hasDelimiter) delimiterChar.toString() else null,
            noselect = noselect,
            noinferiors = noinferiors,
            hasChildren = hasChildren,
            marked = marked,
            unmarked = unmarked,
            isTrashAttr = isTrashAttr,
            isSentAttr = isSentAttr,
            isDraftsAttr = isDraftsAttr,
            isSpamAttr = isSpamAttr,
            isArchiveAttr = isArchiveAttr,
        )
    }

    private fun EmailFolder.applyDescriptor(d: FolderDescriptor, now: OffsetDateTime) {
        this.folderName = d.folderName
        this.path = d.path
        this.delimiter = d.delimiter
        this.hasChildren = d.hasChildren
        this.noselect = d.noselect
        this.noinferiors = d.noinferiors
        this.marked = d.marked
        this.unmarked = d.unmarked
        this.lastEnumeratedAt = now

        // Semantic flags: prefer IMAP SPECIAL-USE attributes when present,
        // otherwise fall back to a name heuristic so generic servers still
        // surface a sensible "Trash" / "Sent" badge.
        val nameLower = d.folderName.lowercase()
        this.isInbox = nameLower == "inbox" || d.path.equals("INBOX", ignoreCase = true)
        this.isSent = d.isSentAttr || nameLower.contains("sent")
        this.isDraft = d.isDraftsAttr || nameLower.contains("draft")
        this.isTrash = d.isTrashAttr || nameLower.contains("trash") || nameLower.contains("deleted")
        this.isSpam = d.isSpamAttr || nameLower.contains("spam") || nameLower.contains("junk")
        this.isArchive = d.isArchiveAttr || nameLower.contains("archive") || d.path.contains("All Mail", ignoreCase = true)

        if (d.noselect) {
            // \Noselect folders cannot be opened — never a sync target.
            this.syncEnabled = false
        }
    }

    internal data class FolderDescriptor(
        val path: String,
        val folderName: String,
        val delimiter: String?,
        val noselect: Boolean,
        val noinferiors: Boolean,
        val hasChildren: Boolean,
        val marked: Boolean,
        val unmarked: Boolean,
        val isTrashAttr: Boolean,
        val isSentAttr: Boolean,
        val isDraftsAttr: Boolean,
        val isSpamAttr: Boolean,
        val isArchiveAttr: Boolean,
    )

}
