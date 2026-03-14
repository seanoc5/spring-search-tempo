package com.oconeco.spring_search_tempo.base.actuator

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Custom actuator endpoint providing a "top"-like view of active processes.
 *
 * Access via: GET /actuator/top
 *
 * Shows:
 * - Running batch jobs with progress
 * - Thread summary by state
 * - JVM memory stats
 * - System load
 */
@Component
@Endpoint(id = "top")
class TopEndpoint(
    private val jobExplorer: JobExplorer
) {

    @ReadOperation
    fun top(): TopSnapshot {
        return TopSnapshot(
            timestamp = Instant.now(),
            uptime = formatDuration(ManagementFactory.getRuntimeMXBean().uptime),
            jobs = getRunningJobs(),
            threads = getThreadSummary(),
            memory = getMemorySummary(),
            system = getSystemSummary()
        )
    }

    private fun getRunningJobs(): List<RunningJob> {
        val running = mutableListOf<RunningJob>()

        for (jobName in jobExplorer.jobNames) {
            for (execution in jobExplorer.findRunningJobExecutions(jobName)) {
                val startTime = execution.startTime
                val elapsed = if (startTime != null) {
                    Duration.between(startTime, LocalDateTime.now()).toSeconds()
                } else 0L

                // Get current step info
                val runningSteps = execution.stepExecutions
                    .filter { it.status == BatchStatus.STARTED || it.status == BatchStatus.STARTING }
                    .sortedByDescending { it.startTime ?: LocalDateTime.MIN }

                val currentStep: org.springframework.batch.core.StepExecution? = runningSteps.firstOrNull()

                val stepInfo = currentStep?.let { step ->
                    StepProgress(
                        name = step.stepName,
                        readCount = step.readCount.toInt(),
                        writeCount = step.writeCount.toInt(),
                        skipCount = step.skipCount.toInt()
                    )
                }

                // Calculate items/sec if we have data
                val itemsPerSec = currentStep?.let { step ->
                    if (elapsed > 0) {
                        val rate = step.writeCount.toDouble() / elapsed
                        if (rate > 0) "%.1f".format(rate) else null
                    } else null
                }

                running.add(
                    RunningJob(
                        executionId = execution.id,
                        jobName = jobName,
                        status = execution.status.name,
                        startTime = startTime?.atZone(ZoneId.systemDefault())?.toInstant(),
                        elapsed = formatDuration(elapsed * 1000),
                        currentStep = stepInfo,
                        itemsPerSec = itemsPerSec
                    )
                )
            }
        }

        return running.sortedByDescending { it.startTime }
    }

    private fun getThreadSummary(): ThreadSummary {
        val threads = Thread.getAllStackTraces().keys
        val byState = threads.groupBy { it.state }

        return ThreadSummary(
            total = threads.size,
            runnable = byState[Thread.State.RUNNABLE]?.size ?: 0,
            waiting = byState[Thread.State.WAITING]?.size ?: 0,
            timedWaiting = byState[Thread.State.TIMED_WAITING]?.size ?: 0,
            blocked = byState[Thread.State.BLOCKED]?.size ?: 0,
            activeNames = threads
                .filter { it.state == Thread.State.RUNNABLE }
                .filter { !isSystemThread(it.name) }
                .take(10)
                .map { it.name }
        )
    }

    private fun isSystemThread(name: String): Boolean {
        val systemPrefixes = listOf(
            "Reference Handler", "Finalizer", "Signal Dispatcher",
            "Common-Cleaner", "Notification Thread", "C1 CompilerThread",
            "C2 CompilerThread", "VM Thread", "GC Thread", "VM Periodic Task",
            "DestroyJavaVM", "Attach Listener"
        )
        return systemPrefixes.any { name.startsWith(it) }
    }

    private fun getMemorySummary(): MemorySummary {
        val runtime = Runtime.getRuntime()
        val memoryMxBean = ManagementFactory.getMemoryMXBean()
        val heapUsage = memoryMxBean.heapMemoryUsage

        return MemorySummary(
            heapUsedMb = heapUsage.used / (1024 * 1024),
            heapMaxMb = heapUsage.max / (1024 * 1024),
            heapUsedPercent = ((heapUsage.used.toDouble() / heapUsage.max) * 100).toInt(),
            nonHeapUsedMb = memoryMxBean.nonHeapMemoryUsage.used / (1024 * 1024),
            availableProcessors = runtime.availableProcessors()
        )
    }

    private fun getSystemSummary(): SystemSummary {
        val osMxBean = ManagementFactory.getOperatingSystemMXBean()

        // Try to get detailed CPU info if available (Sun/Oracle JVM)
        val processCpuLoad = try {
            val method = osMxBean.javaClass.getMethod("getProcessCpuLoad")
            method.isAccessible = true
            val load = method.invoke(osMxBean) as Double
            if (load >= 0) "%.1f%%".format(load * 100) else "N/A"
        } catch (e: Exception) {
            "N/A"
        }

        val systemCpuLoad = try {
            val method = osMxBean.javaClass.getMethod("getCpuLoad")
            method.isAccessible = true
            val load = method.invoke(osMxBean) as Double
            if (load >= 0) "%.1f%%".format(load * 100) else "N/A"
        } catch (e: Exception) {
            // Try legacy method name
            try {
                val method = osMxBean.javaClass.getMethod("getSystemCpuLoad")
                method.isAccessible = true
                val load = method.invoke(osMxBean) as Double
                if (load >= 0) "%.1f%%".format(load * 100) else "N/A"
            } catch (e2: Exception) {
                "N/A"
            }
        }

        return SystemSummary(
            loadAverage = osMxBean.systemLoadAverage.let {
                if (it >= 0) "%.2f".format(it) else "N/A"
            },
            processCpu = processCpuLoad,
            systemCpu = systemCpuLoad
        )
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }
}

// ========== Data Classes ==========

data class TopSnapshot(
    val timestamp: Instant,
    val uptime: String,
    val jobs: List<RunningJob>,
    val threads: ThreadSummary,
    val memory: MemorySummary,
    val system: SystemSummary
)

data class RunningJob(
    val executionId: Long,
    val jobName: String,
    val status: String,
    val startTime: Instant?,
    val elapsed: String,
    val currentStep: StepProgress?,
    val itemsPerSec: String?
)

data class StepProgress(
    val name: String,
    val readCount: Int,
    val writeCount: Int,
    val skipCount: Int
)

data class ThreadSummary(
    val total: Int,
    val runnable: Int,
    val waiting: Int,
    val timedWaiting: Int,
    val blocked: Int,
    val activeNames: List<String>
)

data class MemorySummary(
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val heapUsedPercent: Int,
    val nonHeapUsedMb: Long,
    val availableProcessors: Int
)

data class SystemSummary(
    val loadAverage: String,
    val processCpu: String,
    val systemCpu: String
)
