package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.web.model.DashboardSystemInfo
import javax.sql.DataSource
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

@Service
class DashboardSystemInfoService(
    private val environment: Environment,
    private val dataSource: DataSource,
    private val healthEndpoint: HealthEndpoint,
    private val applicationAvailability: ApplicationAvailability,
    private val buildProperties: BuildProperties? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val staticInfo by lazy {
        val appName = environment.getProperty("spring.application.name", "Spring Search Tempo")
        val appVersion = buildProperties?.version
            ?: javaClass.`package`?.implementationVersion
            ?: "unknown"
        val springBootVersion = SpringBootVersion.getVersion() ?: "unknown"
        val databaseName = resolveDatabaseName()
        val tikaVersion = Tika::class.java.`package`?.implementationVersion ?: "unknown"

        StaticDashboardInfo(
            applicationName = appName,
            applicationVersion = appVersion,
            springBootVersion = springBootVersion,
            databaseName = databaseName,
            textExtraction = "Apache Tika $tikaVersion"
        )
    }

    fun getSystemInfo(): DashboardSystemInfo {
        val (statusLabel, statusBadgeClass) = resolveStatus()

        return DashboardSystemInfo(
            applicationName = staticInfo.applicationName,
            applicationVersion = staticInfo.applicationVersion,
            springBootVersion = staticInfo.springBootVersion,
            databaseName = staticInfo.databaseName,
            textExtraction = staticInfo.textExtraction,
            statusLabel = statusLabel,
            statusBadgeClass = statusBadgeClass
        )
    }

    private fun resolveStatus(): Pair<String, String> {
        return try {
            when (healthEndpoint.health().status.code.uppercase()) {
                "UP" -> "Running" to "bg-success"
                "DOWN", "OUT_OF_SERVICE" -> "Down" to "bg-danger"
                "UNKNOWN" -> "Unknown" to "bg-secondary"
                else -> "Degraded" to "bg-warning"
            }
        } catch (ex: Exception) {
            log.warn("Unable to read health status for dashboard system info: {}", ex.message)
            val liveness = applicationAvailability.livenessState
            val readiness = applicationAvailability.readinessState
            when {
                liveness == LivenessState.BROKEN -> "Down" to "bg-danger"
                readiness == ReadinessState.ACCEPTING_TRAFFIC -> "Running" to "bg-success"
                else -> "Starting" to "bg-warning"
            }
        }
    }

    private fun resolveDatabaseName(): String {
        return try {
            dataSource.connection.use { connection ->
                val meta = connection.metaData
                val product = meta.databaseProductName ?: "Unknown DB"
                val version = meta.databaseProductVersion?.takeIf { it.isNotBlank() }
                if (version != null) "$product $version" else product
            }
        } catch (ex: Exception) {
            log.warn("Unable to read database metadata for dashboard system info: {}", ex.message)
            "Unavailable"
        }
    }

    private data class StaticDashboardInfo(
        val applicationName: String,
        val applicationVersion: String,
        val springBootVersion: String,
        val databaseName: String,
        val textExtraction: String
    )
}
