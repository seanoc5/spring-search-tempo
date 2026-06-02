package com.oconeco.spring_search_tempo.base.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.domain.MirrorConfig
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
class MirrorConfigServiceImpl(
    private val mirrorConfigRepository: MirrorConfigRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val objectMapper: ObjectMapper
) : MirrorConfigService {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorConfigServiceImpl::class.java)
        private val FOLDER_MAPPING_LIST_TYPE = object : TypeReference<List<FolderMapping>>() {}
    }

    override fun count(): Long = mirrorConfigRepository.count()

    override fun findAll(): List<MirrorConfigDTO> =
        mirrorConfigRepository.findAll(Sort.by("id")).map(::toDto)

    override fun findEnabled(): List<MirrorConfigDTO> =
        mirrorConfigRepository.findByEnabledTrue().map(::toDto)

    override fun get(id: Long): MirrorConfigDTO =
        mirrorConfigRepository.findById(id).map(::toDto).orElseThrow { NotFoundException() }

    override fun findByIdOrNull(id: Long): MirrorConfigDTO? =
        mirrorConfigRepository.findById(id).map(::toDto).orElse(null)

    override fun create(
        name: String,
        sourceAccountId: Long,
        destAccountId: Long,
        folderMappings: List<FolderMapping>,
        enabled: Boolean,
        appendRateLimitPerSecond: Int?
    ): MirrorConfigDTO {
        val dto = MirrorConfigDTO().apply {
            this.name = name
            this.sourceAccountId = sourceAccountId
            this.destAccountId = destAccountId
            this.folderMappings = folderMappings
            this.enabled = enabled
            this.appendRateLimitPerSecond = appendRateLimitPerSecond
        }
        val id = create(dto)
        return get(id)
    }

    @Transactional
    override fun create(dto: MirrorConfigDTO): Long {
        validate(dto)

        val entity = MirrorConfig().apply {
            applyFrom(this, dto)
            if (uri.isNullOrBlank()) {
                uri = generateUri(dto)
            }
        }
        val saved = mirrorConfigRepository.save(entity)
        log.info(
            "Created MirrorConfig id={} name='{}' source={} dest={} folders={}",
            saved.id, saved.name, saved.sourceAccountId, saved.destAccountId,
            dto.folderMappings.size
        )
        return saved.id!!
    }

    @Transactional
    override fun update(id: Long, dto: MirrorConfigDTO) {
        validate(dto)
        val entity = mirrorConfigRepository.findById(id).orElseThrow { NotFoundException() }
        applyFrom(entity, dto)
        mirrorConfigRepository.save(entity)
    }

    @Transactional
    override fun delete(id: Long) {
        if (!mirrorConfigRepository.existsById(id)) throw NotFoundException()
        mirrorConfigRepository.deleteById(id)
    }

    @Transactional
    override fun recordDispatched(id: Long, dispatchedAt: OffsetDateTime) {
        val entity = mirrorConfigRepository.findById(id).orElseThrow { NotFoundException() }
        entity.lastDispatchedAt = dispatchedAt
        mirrorConfigRepository.save(entity)
    }

    // ------- internals -------

    private fun validate(dto: MirrorConfigDTO) {
        val src = dto.sourceAccountId
            ?: throw IllegalArgumentException("sourceAccountId is required")
        val dst = dto.destAccountId
            ?: throw IllegalArgumentException("destAccountId is required")
        require(dto.name?.isNotBlank() == true) { "name must not be blank" }
        require(src != dst) { "source and destination accounts must differ" }

        val srcAccount = emailAccountRepository.findById(src).orElseThrow {
            IllegalArgumentException("source account $src not found")
        }
        val dstAccount = emailAccountRepository.findById(dst).orElseThrow {
            IllegalArgumentException("destination account $dst not found")
        }
        require(srcAccount.enabled) { "source account $src is disabled" }
        require(dstAccount.enabled) { "destination account $dst is disabled" }

        val cron = dto.cronSchedule?.trim()
        if (!cron.isNullOrBlank()) {
            try {
                CronExpression.parse(cron)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid cronSchedule '$cron': ${e.message}")
            }
        }
    }

    private fun applyFrom(entity: MirrorConfig, dto: MirrorConfigDTO) {
        entity.sourceAccountId = dto.sourceAccountId
        entity.destAccountId = dto.destAccountId
        entity.name = dto.name
        entity.enabled = dto.enabled
        entity.appendRateLimitPerSecond = dto.appendRateLimitPerSecond
        entity.folderMappings = objectMapper.writeValueAsString(dto.folderMappings)
        entity.cronSchedule = dto.cronSchedule?.trim()?.takeIf { it.isNotBlank() }
        entity.label = dto.label
        entity.description = dto.description
        if (!dto.uri.isNullOrBlank()) entity.uri = dto.uri
        if (entity.version == null) entity.version = 0L
    }

    private fun generateUri(dto: MirrorConfigDTO): String =
        "mirror://${dto.sourceAccountId}->${dto.destAccountId}/${dto.name?.lowercase()?.replace(Regex("\\s+"), "-")}"

    private fun toDto(entity: MirrorConfig): MirrorConfigDTO = MirrorConfigDTO().apply {
        id = entity.id
        uri = entity.uri
        status = entity.status
        analysisStatus = entity.analysisStatus
        label = entity.label
        description = entity.description
        version = entity.version
        sourceAccountId = entity.sourceAccountId
        destAccountId = entity.destAccountId
        name = entity.name
        enabled = entity.enabled
        appendRateLimitPerSecond = entity.appendRateLimitPerSecond
        lastRunStartedAt = entity.lastRunStartedAt
        lastRunCompletedAt = entity.lastRunCompletedAt
        lastError = entity.lastError
        cronSchedule = entity.cronSchedule
        lastDispatchedAt = entity.lastDispatchedAt
        dateCreated = entity.dateCreated
        lastUpdated = entity.lastUpdated
        folderMappings = parseFolderMappings(entity.folderMappings)
    }

    private fun parseFolderMappings(json: String?): List<FolderMapping> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, FOLDER_MAPPING_LIST_TYPE)
        } catch (e: Exception) {
            log.warn("Failed to parse folder mappings JSON, returning empty list: {}", e.message)
            emptyList()
        }
    }
}
