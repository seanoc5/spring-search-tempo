package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.OffsetDateTime


@Service
class EmailAccountServiceImpl(
    private val emailAccountRepository: EmailAccountRepository,
    private val emailAccountMapper: EmailAccountMapper
) : EmailAccountService {

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

    override fun delete(id: Long) {
        val emailAccount = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailAccountRepository.delete(emailAccount)
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

}
