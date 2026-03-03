package com.oconeco.spring_search_tempo.web.model

/**
 * DTOs for the System Monitor page.
 */
data class SystemMonitorDTO(
    val appInfo: AppInfoDTO,
    val health: HealthStatusDTO,
    val database: DatabaseInfoDTO,
    val jvm: JvmInfoDTO,
    val system: SystemInfoDTO,
    val embedding: EmbeddingStatusDTO? = null
)

data class AppInfoDTO(
    val name: String,
    val version: String,
    val profiles: List<String>,
    val uptime: String,
    val startTime: String,
    val serverPort: String,
    val javaVersion: String,
    val kotlinVersion: String,
    val springBootVersion: String,
    val hostName: String
)

data class HealthStatusDTO(
    val status: String,
    val components: List<HealthComponentDTO>
)

data class HealthComponentDTO(
    val name: String,
    val status: String,
    val details: Map<String, Any>
)

data class DatabaseInfoDTO(
    val jdbcUrl: String,
    val databaseProduct: String,
    val databaseVersion: String,
    val driverName: String,
    val username: String,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val maxConnections: Int,
    val threadsAwaiting: Int
)

data class JvmInfoDTO(
    val heapUsed: Long,
    val heapMax: Long,
    val heapCommitted: Long,
    val nonHeapUsed: Long,
    val gcRuns: Long,
    val gcTime: Long,
    val threadCount: Int,
    val peakThreadCount: Int,
    val loadedClassCount: Int,
    val heapUsagePercent: Int
)

data class SystemInfoDTO(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val systemCpuLoad: Double?,
    val processCpuLoad: Double?,
    val totalPhysicalMemory: Long?,
    val freePhysicalMemory: Long?,
    val diskTotal: Long,
    val diskFree: Long,
    val diskUsable: Long,
    val diskUsagePercent: Int
)

/**
 * Status of the embedding service (Ollama + GPU).
 */
data class EmbeddingStatusDTO(
    /** Whether embedding service is available */
    val available: Boolean,
    /** GPU mode: "GPU" or "CPU" */
    val mode: String,
    /** GPU device name if available */
    val gpuDevice: String?,
    /** True if GPU is being used */
    val gpuAvailable: Boolean,
    /** Warning message if GPU not available */
    val warning: String?,
    /** Embedding model name */
    val modelName: String
)
