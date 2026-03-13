package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountSummaryDTO
import com.oconeco.spring_search_tempo.base.model.JobRunProgressDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
class EmailAccountServiceImpl(
    private val emailAccountRepository: EmailAccountRepository,
    private val emailAccountMapper: EmailAccountMapper,
    private val emailFolderRepository: EmailFolderRepository,
    private val emailMessageRepository: EmailMessageRepository,
    private val contentChunkRepository: ContentChunkRepository,
    private val jobRunRepository: JobRunRepository,
    private val userOwnershipService: UserOwnershipService,
    private val smartDeleteService: SmartDeleteService
) : EmailAccountService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailAccountServiceImpl::class.java)
    }

    override fun count(): Long = emailAccountRepository.count()

    override fun findAll(): List<EmailAccountDTO> {
        val accounts = emailAccountRepository.findAll(Sort.by("id"))
        return accounts.map { account ->
            emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO())
        }
    }

    override fun findEnabled(): List<EmailAccountDTO> {
        val accounts = emailAccountRepository.findByEnabledTrue()
        return accounts.map { account ->
            emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO())
        }
    }

    override fun get(id: Long): EmailAccountDTO = emailAccountRepository.findById(id)
        .map { account -> emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(emailAccountDTO: EmailAccountDTO): Long {
        val emailAccount = EmailAccount()
        emailAccountMapper.updateEmailAccount(emailAccountDTO, emailAccount)
        return emailAccountRepository.save(emailAccount).id!!
    }

    override fun update(id: Long, emailAccountDTO: EmailAccountDTO) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailAccountMapper.updateEmailAccount(emailAccountDTO, emailAccount)
        emailAccountRepository.save(emailAccount)
    }

    @Transactional
    override fun delete(id: Long) {
        smartDeleteService.deleteEmailAccount(id)
    }

    override fun emailExists(email: String): Boolean = emailAccountRepository.existsByEmail(email)

    override fun findByEmail(email: String): EmailAccountDTO? {
        return emailAccountRepository.findByEmail(email)?.let { account ->
            emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO())
        }
    }

    override fun getEmailAccountValues(): Map<Long, Long> =
        emailAccountRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(EmailAccount::id, EmailAccount::id))

    override fun updateQuickSyncState(id: Long, inboxUid: Long?, sentUid: Long?) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }

        if (inboxUid != null) {
            emailAccount.inboxLastSyncUid = inboxUid
        }
        if (sentUid != null) {
            emailAccount.sentLastSyncUid = sentUid
        }
        emailAccount.lastQuickSyncAt = OffsetDateTime.now()

        emailAccountRepository.save(emailAccount)
    }

    override fun updateFullSyncState(id: Long, folderCount: Int) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }

        emailAccount.lastFullSyncAt = OffsetDateTime.now()
        emailAccount.lastFullSyncFolderCount = folderCount

        emailAccountRepository.save(emailAccount)
    }

    override fun recordError(id: Long, error: String) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }

        emailAccount.lastError = error.take(2000)  // Limit error message length
        emailAccount.lastErrorAt = OffsetDateTime.now()

        emailAccountRepository.save(emailAccount)
    }

    override fun clearError(id: Long) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }

        emailAccount.lastError = null
        emailAccount.lastErrorAt = null

        emailAccountRepository.save(emailAccount)
    }

    override fun findAllWithSummary(): List<EmailAccountSummaryDTO> {
        val accounts = emailAccountRepository.findAll(Sort.by("id"))

        // Batch load all active email sync jobs
        val activeJobs = jobRunRepository.findAllActiveEmailSyncJobs()
            .associateBy { job ->
                // Extract account ID from job name: emailQuickSyncJob-{accountId}
                job.jobName?.removePrefix("emailQuickSyncJob-")?.toLongOrNull()
            }

        return accounts.map { account ->
            buildSummaryDTO(account, activeJobs[account.id])
        }
    }

    override fun getSummary(id: Long): EmailAccountSummaryDTO {
        val account = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val activeJob = jobRunRepository.findActiveEmailSyncJobForAccount(id)
        return buildSummaryDTO(account, activeJob)
    }

    private fun buildSummaryDTO(account: EmailAccount, activeJob: com.oconeco.spring_search_tempo.base.domain.JobRun?): EmailAccountSummaryDTO {
        val accountId = account.id!!
        return EmailAccountSummaryDTO().apply {
            id = accountId
            email = account.email
            label = account.label
            provider = account.provider
            enabled = account.enabled
            lastQuickSyncAt = account.lastQuickSyncAt
            lastError = account.lastError
            lastErrorAt = account.lastErrorAt
            folderCount = emailFolderRepository.countByEmailAccountId(accountId)
            messageCount = emailMessageRepository.countByEmailAccountId(accountId)
            unreadCount = emailMessageRepository.countByEmailAccountIdAndIsRead(accountId, false)
            activeJobRun = activeJob?.let { job ->
                JobRunProgressDTO().apply {
                    this.id = job.id
                    expectedTotal = job.expectedTotal
                    processedCount = job.processedCount
                    currentStepName = job.currentStepName
                }
            }
        }
    }

    override fun findAllForCurrentUser(): List<EmailAccountDTO> {
        val userId = userOwnershipService.getCurrentUserId() ?: return emptyList()
        val accounts = emailAccountRepository.findByOwnerId(userId)
        return accounts.map { account ->
            emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO())
        }
    }

    override fun findEnabledForCurrentUser(): List<EmailAccountDTO> {
        val userId = userOwnershipService.getCurrentUserId() ?: return emptyList()
        val accounts = emailAccountRepository.findByOwnerIdAndEnabledTrue(userId)
        return accounts.map { account ->
            emailAccountMapper.updateEmailAccountDTO(account, EmailAccountDTO())
        }
    }

    override fun findAllWithSummaryForCurrentUser(): List<EmailAccountSummaryDTO> {
        val userId = userOwnershipService.getCurrentUserId() ?: return emptyList()
        val accounts = emailAccountRepository.findByOwnerId(userId)

        // Batch load all active email sync jobs
        val activeJobs = jobRunRepository.findAllActiveEmailSyncJobs()
            .associateBy { job ->
                // Extract account ID from job name: emailQuickSyncJob-{accountId}
                job.jobName?.removePrefix("emailQuickSyncJob-")?.toLongOrNull()
            }

        return accounts.map { account ->
            buildSummaryDTO(account, activeJobs[account.id])
        }
    }

}
