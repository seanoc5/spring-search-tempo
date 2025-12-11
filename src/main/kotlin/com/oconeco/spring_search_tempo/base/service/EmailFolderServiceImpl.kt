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
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class EmailFolderServiceImpl(
    private val emailFolderRepository: EmailFolderRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderMapper: EmailFolderMapper
) : EmailFolderService {

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

    override fun findOrCreate(accountId: Long, folderName: String, fullPath: String): EmailFolderDTO {
        val existing = emailFolderRepository.findByEmailAccountIdAndFolderName(accountId, folderName)
        if (existing != null) {
            return emailFolderMapper.updateEmailFolderDTO(existing, EmailFolderDTO())
        }

        // Create new folder
        val emailAccount = emailAccountRepository.findById(accountId)
            .orElseThrow { NotFoundException("emailAccount not found") }

        val emailFolder = EmailFolder().apply {
            this.folderName = folderName
            this.fullPath = fullPath
            this.emailAccount = emailAccount
            this.uri = "email-folder://${emailAccount.email}/$folderName"
            this.status = Status.NEW
            this.analysisStatus = AnalysisStatus.LOCATE
            this.version = 0L

            // Detect folder type from name
            val normalizedName = folderName.lowercase()
            this.isInbox = normalizedName == "inbox"
            this.isSent = normalizedName.contains("sent")
            this.isDraft = normalizedName.contains("draft")
            this.isTrash = normalizedName.contains("trash") || normalizedName.contains("deleted")
            this.isSpam = normalizedName.contains("spam") || normalizedName.contains("junk")
            this.isArchive = normalizedName.contains("archive") || normalizedName.contains("all mail")
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

}
