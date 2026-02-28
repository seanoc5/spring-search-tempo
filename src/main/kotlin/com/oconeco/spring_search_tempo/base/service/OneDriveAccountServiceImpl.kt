package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.domain.OneDriveAccount
import com.oconeco.spring_search_tempo.base.model.JobRunProgressDTO
import com.oconeco.spring_search_tempo.base.model.OneDriveAccountDTO
import com.oconeco.spring_search_tempo.base.model.OneDriveAccountSummaryDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.repos.OneDriveAccountRepository
import com.oconeco.spring_search_tempo.base.repos.OneDriveItemRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
class OneDriveAccountServiceImpl(
    private val oneDriveAccountRepository: OneDriveAccountRepository,
    private val oneDriveAccountMapper: OneDriveAccountMapper,
    private val oneDriveItemRepository: OneDriveItemRepository,
    private val contentChunkRepository: ContentChunkRepository,
    private val jobRunRepository: JobRunRepository,
    private val tokenEncryptionService: TokenEncryptionService
) : OneDriveAccountService {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveAccountServiceImpl::class.java)
    }

    override fun count(): Long = oneDriveAccountRepository.count()

    override fun findAll(): List<OneDriveAccountDTO> {
        val accounts = oneDriveAccountRepository.findAll(Sort.by("id"))
        return accounts.map { account ->
            oneDriveAccountMapper.updateOneDriveAccountDTO(account, OneDriveAccountDTO())
        }
    }

    override fun findEnabled(): List<OneDriveAccountDTO> {
        val accounts = oneDriveAccountRepository.findByEnabledTrue()
        return accounts.map { account ->
            oneDriveAccountMapper.updateOneDriveAccountDTO(account, OneDriveAccountDTO())
        }
    }

    override fun get(id: Long): OneDriveAccountDTO = oneDriveAccountRepository.findById(id)
        .map { account -> oneDriveAccountMapper.updateOneDriveAccountDTO(account, OneDriveAccountDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(oneDriveAccountDTO: OneDriveAccountDTO): Long {
        val account = OneDriveAccount()
        oneDriveAccountMapper.updateOneDriveAccount(oneDriveAccountDTO, account)
        return oneDriveAccountRepository.save(account).id!!
    }

    override fun update(id: Long, oneDriveAccountDTO: OneDriveAccountDTO) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        oneDriveAccountMapper.updateOneDriveAccount(oneDriveAccountDTO, account)
        oneDriveAccountRepository.save(account)
    }

    @Transactional
    override fun delete(id: Long) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }

        log.info("Deleting OneDrive account {} and all related data", account.email)

        // Cascade delete in correct order (children first)
        val chunksDeleted = contentChunkRepository.deleteByOneDriveAccountId(id)
        log.info("Deleted {} content chunks for OneDrive account {}", chunksDeleted, id)

        val itemsDeleted = oneDriveItemRepository.deleteByOneDriveAccountId(id)
        log.info("Deleted {} OneDrive items for account {}", itemsDeleted, id)

        oneDriveAccountRepository.delete(account)
        log.info("Deleted OneDrive account {}", account.email)
    }

    override fun storeTokens(id: Long, encryptedRefreshToken: String) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.encryptedRefreshToken = encryptedRefreshToken
        account.tokenObtainedAt = OffsetDateTime.now()
        oneDriveAccountRepository.save(account)
    }

    override fun getDecryptedRefreshToken(id: Long): String? {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val encrypted = account.encryptedRefreshToken ?: return null
        return tokenEncryptionService.decrypt(encrypted)
    }

    override fun updateDeltaToken(id: Long, deltaToken: String?) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.deltaToken = deltaToken
        account.lastDeltaSyncAt = OffsetDateTime.now()
        oneDriveAccountRepository.save(account)
    }

    override fun clearDeltaToken(id: Long) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.deltaToken = null
        oneDriveAccountRepository.save(account)
    }

    override fun updateDriveInfo(id: Long, driveId: String, driveType: String?, quotaTotal: Long?, quotaUsed: Long?) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.driveId = driveId
        account.driveType = driveType
        account.driveQuotaTotal = quotaTotal
        account.driveQuotaUsed = quotaUsed
        oneDriveAccountRepository.save(account)
    }

    override fun recordError(id: Long, error: String) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.lastError = error.take(2000)
        account.lastErrorAt = OffsetDateTime.now()
        oneDriveAccountRepository.save(account)
    }

    override fun clearError(id: Long) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.lastError = null
        account.lastErrorAt = null
        oneDriveAccountRepository.save(account)
    }

    override fun findAllWithSummary(): List<OneDriveAccountSummaryDTO> {
        val accounts = oneDriveAccountRepository.findAll(Sort.by("id"))

        // Batch load all active OneDrive sync jobs
        val activeJobs = jobRunRepository.findAllActiveOneDriveSyncJobs()
            .associateBy { job ->
                // Extract account ID from job name: oneDriveSync_{accountId}
                job.jobName?.removePrefix("oneDriveSync_")?.toLongOrNull()
            }

        return accounts.map { account ->
            buildSummaryDTO(account, activeJobs[account.id])
        }
    }

    override fun getSummary(id: Long): OneDriveAccountSummaryDTO {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val activeJob = jobRunRepository.findActiveOneDriveSyncJobForAccount(id)
        return buildSummaryDTO(account, activeJob)
    }

    override fun findByMicrosoftAccountId(microsoftAccountId: String): OneDriveAccountDTO? {
        return oneDriveAccountRepository.findByMicrosoftAccountId(microsoftAccountId)?.let { account ->
            oneDriveAccountMapper.updateOneDriveAccountDTO(account, OneDriveAccountDTO())
        }
    }

    override fun updateSyncStats(id: Long, totalItems: Long, totalSize: Long) {
        val account = oneDriveAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        account.totalItems = totalItems
        account.totalSize = totalSize
        oneDriveAccountRepository.save(account)
    }

    private fun buildSummaryDTO(
        account: OneDriveAccount,
        activeJob: com.oconeco.spring_search_tempo.base.domain.JobRun?
    ): OneDriveAccountSummaryDTO {
        return OneDriveAccountSummaryDTO().apply {
            id = account.id
            email = account.email
            displayName = account.displayName
            enabled = account.enabled
            driveId = account.driveId
            driveType = account.driveType
            driveQuotaTotal = account.driveQuotaTotal
            driveQuotaUsed = account.driveQuotaUsed
            lastDeltaSyncAt = account.lastDeltaSyncAt
            lastError = account.lastError
            lastErrorAt = account.lastErrorAt
            totalItems = account.totalItems
            totalSize = account.totalSize
            isConnected = account.encryptedRefreshToken != null
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

}
