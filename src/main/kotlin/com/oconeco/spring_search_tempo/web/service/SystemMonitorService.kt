package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import com.oconeco.spring_search_tempo.web.model.*
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.CompositeHealth
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@Service
class SystemMonitorService(
    private val dataSource: DataSource,
    private val healthEndpoint: HealthEndpoint,
    private val environment: Environment,
    private val buildProperties: BuildProperties? = null,
    private val embeddingService: EmbeddingService? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(SystemMonitorService::class.java)
        private val startInstant: Instant = Instant.now()
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        /** Format bytes to human-readable string. Called from Thymeleaf via SpEL T() operator. */
        @JvmStatic
        fun formatBytes(bytes: Long?): String {
            if (bytes == null || bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val idx = digitGroups.coerceAtMost(units.size - 1)
            return "%.1f %s".format(bytes / Math.pow(1024.0, idx.toDouble()), units[idx])
        }
    }

    fun getMonitorData(): SystemMonitorDTO {
        return SystemMonitorDTO(
            appInfo = getAppInfo(),
            health = getHealthStatus(),
            database = getDatabaseInfo(),
            jvm = getJvmInfo(),
            system = getSystemInfo(),
            embedding = getEmbeddingStatus()
        )
    }

    /** Live metrics only (health, DB pool, JVM, CPU) - called by the polling endpoint. */
    fun getLiveData(): SystemMonitorDTO {
        return SystemMonitorDTO(
            appInfo = getAppInfo(),
            health = getHealthStatus(),
            database = getDatabaseInfo(),
            jvm = getJvmInfo(),
            system = getSystemInfo(),
            embedding = getEmbeddingStatus()
        )
    }

    private fun getEmbeddingStatus(): EmbeddingStatusDTO? {
        if (embeddingService == null) {
            return null
        }

        return try {
            val available = embeddingService.isAvailable()
            val gpuStatus = embeddingService.checkGpuStatus()

            EmbeddingStatusDTO(
                available = available,
                mode = gpuStatus.mode,
                gpuDevice = gpuStatus.gpuDevice,
                gpuAvailable = gpuStatus.gpuAvailable,
                warning = gpuStatus.warning,
                modelName = embeddingService.getModelName()
            )
        } catch (e: Exception) {
            log.debug("Failed to get embedding status: {}", e.message)
            EmbeddingStatusDTO(
                available = false,
                mode = "unavailable",
                gpuDevice = null,
                gpuAvailable = false,
                warning = "Embedding service unavailable: ${e.message}",
                modelName = embeddingService.getModelName()
            )
        }
    }

    private fun getAppInfo(): AppInfoDTO {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val uptimeMillis = runtime.uptime
        val uptime = formatDuration(Duration.ofMillis(uptimeMillis))
        val startTime = dateFormatter.format(Instant.ofEpochMilli(runtime.startTime))

        val springBootVersion = org.springframework.boot.SpringBootVersion.getVersion() ?: "unknown"

        return AppInfoDTO(
            name = environment.getProperty("spring.application.name", "Spring Search Tempo"),
            version = buildProperties?.version
                ?: javaClass.`package`?.implementationVersion
                ?: "unknown",
            profiles = environment.activeProfiles.toList(),
            uptime = uptime,
            startTime = startTime,
            serverPort = environment.getProperty("server.port", "8082"),
            javaVersion = System.getProperty("java.version", "unknown"),
            kotlinVersion = KotlinVersion.CURRENT.toString(),
            springBootVersion = springBootVersion,
            hostName = HostNameHolder.currentHostName
        )
    }

    private fun getHealthStatus(): HealthStatusDTO {
        return try {
            val healthComponent = healthEndpoint.health()
            val composite = healthComponent as? CompositeHealth
            val components = composite?.components?.map { (name, component) ->
                val details = when (component) {
                    is Health -> component.details
                    else -> emptyMap()
                }
                HealthComponentDTO(
                    name = name,
                    status = component.status.code,
                    details = details
                )
            } ?: emptyList()

            HealthStatusDTO(
                status = healthComponent.status.code,
                components = components.sortedBy { it.name }
            )
        } catch (e: Exception) {
            log.warn("Failed to get health status: {}", e.message)
            HealthStatusDTO(status = Status.UNKNOWN.code, components = emptyList())
        }
    }

    private fun getDatabaseInfo(): DatabaseInfoDTO {
        val hikari = dataSource as? HikariDataSource
        val pool = hikari?.hikariPoolMXBean

        val dbProduct: String
        val dbVersion: String
        val driverName: String
        val dbUsername: String

        try {
            dataSource.connection.use { conn ->
                val meta = conn.metaData
                dbProduct = meta.databaseProductName ?: "unknown"
                dbVersion = meta.databaseProductVersion ?: "unknown"
                driverName = meta.driverName ?: "unknown"
                dbUsername = meta.userName ?: "unknown"
            }
        } catch (e: Exception) {
            log.warn("Failed to get database metadata: {}", e.message)
            return DatabaseInfoDTO(
                jdbcUrl = hikari?.jdbcUrl ?: "unknown",
                databaseProduct = "unavailable",
                databaseVersion = "unavailable",
                driverName = "unavailable",
                username = "unavailable",
                activeConnections = 0,
                idleConnections = 0,
                totalConnections = 0,
                maxConnections = hikari?.maximumPoolSize ?: 0,
                threadsAwaiting = 0
            )
        }

        return DatabaseInfoDTO(
            jdbcUrl = hikari?.jdbcUrl ?: "unknown",
            databaseProduct = dbProduct,
            databaseVersion = dbVersion,
            driverName = driverName,
            username = dbUsername,
            activeConnections = pool?.activeConnections ?: 0,
            idleConnections = pool?.idleConnections ?: 0,
            totalConnections = pool?.totalConnections ?: 0,
            maxConnections = hikari?.maximumPoolSize ?: 0,
            threadsAwaiting = pool?.threadsAwaitingConnection ?: 0
        )
    }

    private fun getJvmInfo(): JvmInfoDTO {
        val memoryMxBean = ManagementFactory.getMemoryMXBean()
        val heap = memoryMxBean.heapMemoryUsage
        val nonHeap = memoryMxBean.nonHeapMemoryUsage
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val classLoadingMxBean = ManagementFactory.getClassLoadingMXBean()

        var gcRuns = 0L
        var gcTime = 0L
        ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
            if (gc.collectionCount >= 0) gcRuns += gc.collectionCount
            if (gc.collectionTime >= 0) gcTime += gc.collectionTime
        }

        val heapUsagePercent = if (heap.max > 0) ((heap.used.toDouble() / heap.max) * 100).toInt() else 0

        return JvmInfoDTO(
            heapUsed = heap.used,
            heapMax = heap.max,
            heapCommitted = heap.committed,
            nonHeapUsed = nonHeap.used,
            gcRuns = gcRuns,
            gcTime = gcTime,
            threadCount = threadMxBean.threadCount,
            peakThreadCount = threadMxBean.peakThreadCount,
            loadedClassCount = classLoadingMxBean.loadedClassCount,
            heapUsagePercent = heapUsagePercent
        )
    }

    private fun getSystemInfo(): SystemInfoDTO {
        val osMxBean = ManagementFactory.getOperatingSystemMXBean()

        // Safe cast to com.sun.management for CPU and memory stats (HotSpot/Java 21)
        val sunOs = osMxBean as? com.sun.management.OperatingSystemMXBean

        val systemCpuLoad = sunOs?.cpuLoad?.let { if (it < 0) null else (it * 100) }
        val processCpuLoad = sunOs?.processCpuLoad?.let { if (it < 0) null else (it * 100) }
        val totalPhysicalMemory = sunOs?.totalMemorySize
        val freePhysicalMemory = sunOs?.freeMemorySize

        val root = File("/")
        val diskTotal = root.totalSpace
        val diskFree = root.freeSpace
        val diskUsable = root.usableSpace
        val diskUsagePercent = if (diskTotal > 0) (((diskTotal - diskUsable).toDouble() / diskTotal) * 100).toInt() else 0

        return SystemInfoDTO(
            osName = osMxBean.name ?: "unknown",
            osVersion = osMxBean.version ?: "unknown",
            osArch = osMxBean.arch ?: "unknown",
            systemCpuLoad = systemCpuLoad,
            processCpuLoad = processCpuLoad,
            totalPhysicalMemory = totalPhysicalMemory,
            freePhysicalMemory = freePhysicalMemory,
            diskTotal = diskTotal,
            diskFree = diskFree,
            diskUsable = diskUsable,
            diskUsagePercent = diskUsagePercent
        )
    }

    private fun formatDuration(duration: Duration): String {
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }
}
