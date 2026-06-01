package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.EmailFolder
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.EmailFolderDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class EmailFolderServiceImpl(
    private val emailFolderRepository: EmailFolderRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderMapper: EmailFolderMapper
) : EmailFolderService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailFolderServiceImpl::class.java)
    }

    override fun count(): Long = emailFolderRepository.count()

    override fun findAll(): List<EmailFolderDTO> {
        val folders = emailFolderRepository.findAll(Sort.by("id"))
        return folders.map { folder ->
            emailFolderMapper.updateEmailFolderDTO(folder, EmailFolderDTO())
        }
    }

    override fun findByAccount(accountId: Long): List<EmailFolderDTO> {
        val folders = emailFolderRepository.findByEmailAccountId(accountId)
        return folders.map { folder ->
            emailFolderMapper.updateEmailFolderDTO(folder, EmailFolderDTO())
        }
    }

    override fun get(id: Long): EmailFolderDTO = emailFolderRepository.findById(id)
        .map { folder -> emailFolderMapper.updateEmailFolderDTO(folder, EmailFolderDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(emailFolderDTO: EmailFolderDTO): Long {
        val emailFolder = EmailFolder()
        emailFolderMapper.updateEmailFolder(emailFolderDTO, emailFolder, emailAccountRepository)
        return emailFolderRepository.save(emailFolder).id!!
    }

    override fun update(id: Long, emailFolderDTO: EmailFolderDTO) {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailFolderMapper.updateEmailFolder(emailFolderDTO, emailFolder, emailAccountRepository)
        emailFolderRepository.save(emailFolder)
    }

    override fun delete(id: Long) {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailFolderRepository.delete(emailFolder)
    }

    override fun getEmailFolderValues(): Map<Long, Long> =
        emailFolderRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(EmailFolder::id, EmailFolder::id))

    override fun findOrCreate(accountId: Long, folderName: String, path: String): EmailFolderDTO {
        // Prefer lookup by path (unique within an account); legacy callers may still pass folderName==path.
        val existing = emailFolderRepository.findByEmailAccountIdAndPath(accountId, path)
            ?: emailFolderRepository.findByEmailAccountIdAndFolderName(accountId, folderName)
        if (existing != null) {
            return emailFolderMapper.updateEmailFolderDTO(existing, EmailFolderDTO())
        }

        // Create new folder
        val emailAccount = emailAccountRepository.findById(accountId)
            .orElseThrow { NotFoundException("emailAccount not found") }

        val emailFolder = EmailFolder().apply {
            this.folderName = folderName
            this.path = path
            this.emailAccount = emailAccount
            this.uri = "email-folder://${emailAccount.email}/$path"
            this.status = Status.NEW
            this.analysisStatus = AnalysisStatus.LOCATE
            this.version = 0L

            applyTypeFlags(folderName)
        }

        val saved = emailFolderRepository.save(emailFolder)
        return emailFolderMapper.updateEmailFolderDTO(saved, EmailFolderDTO())
    }

    override fun updateSyncState(id: Long, lastUid: Long, messageCount: Long) {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }

        emailFolder.lastSyncUid = lastUid
        emailFolder.messageCount = messageCount

        emailFolderRepository.save(emailFolder)
    }

    override fun resetSyncState(id: Long) {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }

        emailFolder.lastSyncUid = 0L
        emailFolder.uidValidity = null

        emailFolderRepository.save(emailFolder)
    }

    override fun resetSyncStateForAccount(accountId: Long) {
        val folders = emailFolderRepository.findByEmailAccountId(accountId)
        folders.forEach { folder ->
            folder.lastSyncUid = 0L
            folder.uidValidity = null
        }
        emailFolderRepository.saveAll(folders)
    }

    override fun updateUidValidity(id: Long, newUidValidity: Long): Boolean {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }

        val oldUidValidity = emailFolder.uidValidity
        val changed = oldUidValidity != null && oldUidValidity != newUidValidity

        if (changed) {
            // UIDVALIDITY changed - UIDs are no longer valid, reset sync state.
            // Scoped to THIS folder only; siblings' UIDVALIDITY is untouched.
            emailFolder.lastSyncUid = 0L
        }
        emailFolder.uidValidity = newUidValidity

        emailFolderRepository.save(emailFolder)
        return changed
    }

    override fun setSyncEnabled(id: Long, enabled: Boolean) {
        val emailFolder = emailFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }

        val wasEnabled = emailFolder.syncEnabled
        emailFolder.syncEnabled = enabled

        if (enabled && !wasEnabled) {
            // Re-enabling: pull full history on next sync (don't trust the
            // pre-disable UID cursor — messages may have moved through the
            // folder while we weren't watching).
            log.info("Re-enabling folder {} (account {}) — resetting lastSyncUid to trigger full history pull",
                emailFolder.path ?: emailFolder.folderName, emailFolder.emailAccount?.id)
            emailFolder.lastSyncUid = 0L
        }

        emailFolderRepository.save(emailFolder)
    }

    override fun fetchTargets(accountId: Long): List<String> {
        return emailFolderRepository
            .findByEmailAccountIdAndSyncEnabledTrueAndNoselectFalse(accountId)
            .mapNotNull { it.path ?: it.folderName }
            .sorted()
    }

    /**
     * Apply heuristic semantic flags (`isInbox`, `isSent`, ...) based on folder name.
     * Used both at find-or-create and during full enumeration so the flags stay in sync.
     */
    private fun EmailFolder.applyTypeFlags(name: String) {
        val normalizedName = name.lowercase()
        this.isInbox = normalizedName == "inbox"
        this.isSent = normalizedName.contains("sent")
        this.isDraft = normalizedName.contains("draft")
        this.isTrash = normalizedName.contains("trash") || normalizedName.contains("deleted")
        this.isSpam = normalizedName.contains("spam") || normalizedName.contains("junk")
        this.isArchive = normalizedName.contains("archive") || normalizedName.contains("all mail")
    }

}
