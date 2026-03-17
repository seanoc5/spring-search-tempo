package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.SourceHost
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryFolderObservationRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryRunRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.repos.RemoteCrawlTaskRepository
import com.oconeco.spring_search_tempo.base.repos.SourceHostRepository
import com.oconeco.spring_search_tempo.base.repos.UserSourceHostRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SourceHostService(
    private val sourceHostRepository: SourceHostRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val discoverySessionRepository: DiscoverySessionRepository,
    private val userSourceHostRepository: UserSourceHostRepository,
    private val remoteCrawlTaskRepository: RemoteCrawlTaskRepository,
    private val crawlDiscoveryRunRepository: CrawlDiscoveryRunRepository,
    private val crawlDiscoveryFolderObservationRepository: CrawlDiscoveryFolderObservationRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun resolveOrCreate(host: String?, osType: String? = null): SourceHost? {
        val normalized = normalizeHost(host) ?: return null
        val existing = sourceHostRepository.findByNormalizedHost(normalized)
        if (existing != null) {
            if (existing.displayName.isNullOrBlank()) {
                existing.displayName = host?.trim()?.ifBlank { normalized } ?: normalized
            }
            if (existing.osType.isNullOrBlank() && !osType.isNullOrBlank()) {
                existing.osType = osType.trim()
            }
            existing.lastSeenAt = OffsetDateTime.now()
            return sourceHostRepository.save(existing)
        }

        val entity = SourceHost().apply {
            normalizedHost = normalized
            displayName = host?.trim()?.ifBlank { normalized } ?: normalized
            this.osType = osType?.trim()?.ifBlank { null }
            uri = "tempo:source-host:$normalized"
            label = displayName
            description = "Source host $displayName"
            type = "SOURCE_HOST"
            sourceHost = normalized
            status = com.oconeco.spring_search_tempo.base.domain.Status.CURRENT
            analysisStatus = com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.LOCATE
            lastSeenAt = OffsetDateTime.now()
        }
        return sourceHostRepository.save(entity)
    }

    @Transactional
    fun backfillCoreReferences(): SourceHostBackfillResult {
        val hosts = linkedSetOf<String>()
        var crawlConfigsUpdated = 0
        var discoverySessionsUpdated = 0
        var userAssignmentsUpdated = 0
        var remoteTasksUpdated = 0
        var discoveryRunsUpdated = 0
        var discoveryObservationsUpdated = 0

        val changedConfigs = mutableListOf<com.oconeco.spring_search_tempo.base.domain.CrawlConfig>()
        crawlConfigRepository.findAll().forEach { config ->
            if (config.sourceHostRef == null) {
                val ref = resolveOrCreate(config.sourceHost)
                if (ref != null) {
                    config.sourceHostRef = ref
                    crawlConfigsUpdated++
                    hosts += ref.normalizedHost!!
                    changedConfigs += config
                }
            }
        }
        if (changedConfigs.isNotEmpty()) {
            crawlConfigRepository.saveAll(changedConfigs)
        }

        val changedSessions = mutableListOf<com.oconeco.spring_search_tempo.base.domain.DiscoverySession>()
        discoverySessionRepository.findAll().forEach { session ->
            if (session.sourceHostRef == null) {
                val ref = resolveOrCreate(session.host, session.osType)
                if (ref != null) {
                    session.sourceHostRef = ref
                    discoverySessionsUpdated++
                    hosts += ref.normalizedHost!!
                    changedSessions += session
                }
            }
        }
        if (changedSessions.isNotEmpty()) {
            discoverySessionRepository.saveAll(changedSessions)
        }

        val changedAssignments = mutableListOf<com.oconeco.spring_search_tempo.base.domain.UserSourceHost>()
        userSourceHostRepository.findAll().forEach { assignment ->
            if (assignment.sourceHostRef == null) {
                val ref = resolveOrCreate(assignment.sourceHost)
                if (ref != null) {
                    assignment.sourceHostRef = ref
                    userAssignmentsUpdated++
                    hosts += ref.normalizedHost!!
                    changedAssignments += assignment
                }
            }
        }
        if (changedAssignments.isNotEmpty()) {
            userSourceHostRepository.saveAll(changedAssignments)
        }

        val changedTasks = mutableListOf<com.oconeco.spring_search_tempo.base.domain.RemoteCrawlTask>()
        remoteCrawlTaskRepository.findAll().forEach { task ->
            if (task.sourceHostRef == null) {
                val ref = resolveOrCreate(task.host)
                if (ref != null) {
                    task.sourceHostRef = ref
                    remoteTasksUpdated++
                    hosts += ref.normalizedHost!!
                    changedTasks += task
                }
            }
        }
        if (changedTasks.isNotEmpty()) {
            remoteCrawlTaskRepository.saveAll(changedTasks)
        }

        val changedRuns = mutableListOf<com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryRun>()
        crawlDiscoveryRunRepository.findAll().forEach { run ->
            if (run.sourceHostRef == null) {
                val ref = resolveOrCreate(run.host)
                if (ref != null) {
                    run.sourceHostRef = ref
                    discoveryRunsUpdated++
                    hosts += ref.normalizedHost!!
                    changedRuns += run
                }
            }
        }
        if (changedRuns.isNotEmpty()) {
            crawlDiscoveryRunRepository.saveAll(changedRuns)
        }

        val changedObservations = mutableListOf<com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryFolderObservation>()
        crawlDiscoveryFolderObservationRepository.findAll().forEach { observation ->
            if (observation.sourceHostRef == null) {
                val ref = resolveOrCreate(observation.host)
                if (ref != null) {
                    observation.sourceHostRef = ref
                    discoveryObservationsUpdated++
                    hosts += ref.normalizedHost!!
                    changedObservations += observation
                }
            }
        }
        if (changedObservations.isNotEmpty()) {
            crawlDiscoveryFolderObservationRepository.saveAll(changedObservations)
        }

        log.info(
            "Backfilled source hosts: {} hosts, {} crawl configs, {} discovery sessions, {} ownership rows, {} remote tasks, {} discovery runs, {} discovery observations",
            hosts.size,
            crawlConfigsUpdated,
            discoverySessionsUpdated,
            userAssignmentsUpdated,
            remoteTasksUpdated,
            discoveryRunsUpdated,
            discoveryObservationsUpdated
        )

        return SourceHostBackfillResult(
            hostsCreatedOrResolved = hosts.size,
            crawlConfigsUpdated = crawlConfigsUpdated,
            discoverySessionsUpdated = discoverySessionsUpdated,
            userAssignmentsUpdated = userAssignmentsUpdated,
            remoteTasksUpdated = remoteTasksUpdated,
            discoveryRunsUpdated = discoveryRunsUpdated,
            discoveryObservationsUpdated = discoveryObservationsUpdated
        )
    }

    fun findAllHosts(): List<SourceHost> =
        sourceHostRepository.findAll().sortedBy { it.displayName ?: it.normalizedHost ?: "" }

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

data class SourceHostBackfillResult(
    val hostsCreatedOrResolved: Int,
    val crawlConfigsUpdated: Int,
    val discoverySessionsUpdated: Int,
    val userAssignmentsUpdated: Int,
    val remoteTasksUpdated: Int,
    val discoveryRunsUpdated: Int,
    val discoveryObservationsUpdated: Int
)
