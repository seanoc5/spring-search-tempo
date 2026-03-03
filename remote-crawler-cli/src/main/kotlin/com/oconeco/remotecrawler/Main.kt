package com.oconeco.remotecrawler

import com.oconeco.remotecrawler.client.*
import com.oconeco.remotecrawler.crawler.FilesystemCrawler
import com.oconeco.remotecrawler.discovery.DiscoveredFolder
import com.oconeco.remotecrawler.discovery.DiscoveryProgress
import com.oconeco.remotecrawler.discovery.FolderDiscovery
import com.oconeco.remotecrawler.util.DriveDetector
import kotlinx.cli.*
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Paths
import java.util.*

private val log = LoggerFactory.getLogger("RemoteCrawler")

fun main(args: Array<String>) {
    val parser = ArgParser("remote-crawler")

    // Global options
    val serverUrl by parser.option(
        ArgType.String,
        shortName = "s",
        fullName = "server",
        description = "Server URL (default: http://localhost:8082)"
    ).default("http://localhost:8082")

    val username by parser.option(
        ArgType.String,
        shortName = "u",
        fullName = "username",
        description = "Server username"
    ).default("admin")

    val password by parser.option(
        ArgType.String,
        shortName = "p",
        fullName = "password",
        description = "Server password"
    ).default("admin")

    val hostName by parser.option(
        ArgType.String,
        shortName = "H",
        fullName = "host",
        description = "Host name to identify this crawler"
    )

    // Subcommands
    class CrawlCommand : Subcommand("crawl", "Run filesystem crawl") {
        val configId by option(
            ArgType.Int,
            shortName = "c",
            fullName = "config",
            description = "Crawl config ID (if not specified, runs all assigned configs)"
        )

        val batchSize by option(
            ArgType.Int,
            shortName = "b",
            fullName = "batch-size",
            description = "Batch size for ingestion"
        ).default(100)

        override fun execute() {
            val host = hostName ?: getHostName()
            log.info("Running crawl for host: {}", host)

            val client = RemoteCrawlClient(serverUrl, username, password)

            // Test connection first
            if (!client.testConnection()) {
                log.error("Failed to connect to server at {}", serverUrl)
                System.exit(1)
            }

            // Get assigned configs
            val bootstrap = client.bootstrap(host)
            log.info("Found {} assigned crawl configs", bootstrap.assignments.size)

            if (bootstrap.assignments.isEmpty()) {
                log.warn("No crawl configs assigned to host '{}'", host)
                return
            }

            // Filter to specific config if specified
            val configsToRun = if (configId != null) {
                bootstrap.assignments.filter { it.crawlConfigId.toInt() == configId }
            } else {
                bootstrap.assignments
            }

            if (configsToRun.isEmpty()) {
                log.error("Config {} not found or not assigned to this host", configId)
                System.exit(1)
            }

            val crawler = FilesystemCrawler(
                client = client,
                batchSize = batchSize
            )

            // Run each config
            var totalSuccess = 0
            var totalFailed = 0

            for (config in configsToRun) {
                log.info("=== Running config: {} (ID: {}) ===", config.name, config.crawlConfigId)

                val result = crawler.crawl(config, host)

                if (result.success) {
                    log.info("Crawl completed: {} folders, {} files in {}ms",
                        result.foldersProcessed, result.filesProcessed, result.durationMs)
                    totalSuccess++
                } else {
                    log.error("Crawl failed: {}", result.errorMessage)
                    totalFailed++
                }
            }

            log.info("=== All crawls complete: {} succeeded, {} failed ===", totalSuccess, totalFailed)

            if (totalFailed > 0) {
                System.exit(1)
            }
        }
    }

    class StatusCommand : Subcommand("status", "Check server status and assigned configs") {
        override fun execute() {
            val host = hostName ?: getHostName()
            log.info("Checking status for host: {}", host)

            val client = RemoteCrawlClient(serverUrl, username, password)

            // Test connection
            if (!client.testConnection()) {
                log.error("Failed to connect to server at {}", serverUrl)
                println("Status: OFFLINE")
                println("Server: $serverUrl")
                System.exit(1)
            }

            println("Status: ONLINE")
            println("Server: $serverUrl")
            println("Host: $host")
            println()

            // Get assigned configs
            val bootstrap = client.bootstrap(host)

            println("Assigned Crawl Configs (${bootstrap.assignments.size}):")
            if (bootstrap.assignments.isEmpty()) {
                println("  (none)")
            } else {
                for (config in bootstrap.assignments) {
                    println("  - ${config.name} (ID: ${config.crawlConfigId})")
                    println("    Paths: ${config.startPaths.joinToString(", ")}")
                    println("    Max Depth: ${config.maxDepth}, Follow Links: ${config.followLinks}")
                }
            }
        }
    }

    class OnboardCommand : Subcommand("onboard", "Initial system discovery and setup") {
        val paths by option(
            ArgType.String,
            shortName = "d",
            fullName = "paths",
            description = "Paths to discover (comma-separated, e.g., 'C:,D:' or '/'). If not specified, detects automatically."
        )

        val maxDepth by option(
            ArgType.Int,
            fullName = "max-depth",
            description = "Maximum folder depth to discover"
        ).default(15)

        val interactive by option(
            ArgType.Boolean,
            shortName = "i",
            fullName = "interactive",
            description = "Prompt for paths interactively"
        ).default(true)

        val skipPrompt by option(
            ArgType.Boolean,
            fullName = "yes",
            description = "Skip confirmation prompts (use defaults)"
        ).default(false)

        override fun execute() {
            val host = hostName ?: getHostName()
            val osType = detectOsType()

            println()
            println("=".repeat(60))
            println("  Spring Search Tempo - System Onboarding")
            println("=".repeat(60))
            println()
            println("Host: $host")
            println("OS: $osType")
            println("Server: $serverUrl")
            println()

            val client = RemoteCrawlClient(serverUrl, username, password)

            // Test connection first
            if (!client.testConnection()) {
                println("ERROR: Failed to connect to server at $serverUrl")
                println("Make sure the server is running and credentials are correct.")
                System.exit(1)
            }
            println("Server connection: OK")
            println()

            // Determine which paths to discover
            val rootPaths = if (paths != null) {
                // User specified paths explicitly
                paths!!.split(",").map { Paths.get(it.trim()) }
            } else if (interactive && !skipPrompt) {
                // Interactive mode - detect and prompt
                selectPathsInteractively()
            } else {
                // Auto-detect defaults
                DriveDetector.getSuggestedRoots().map { it.path }
            }

            if (rootPaths.isEmpty()) {
                println("No paths selected for discovery. Exiting.")
                return
            }

            println()
            println("Paths to discover:")
            rootPaths.forEach { println("  - $it") }
            println()

            if (!skipPrompt && interactive) {
                print("Proceed with discovery? [Y/n]: ")
                val response = readLine()?.trim()?.lowercase()
                if (response == "n" || response == "no") {
                    println("Discovery cancelled.")
                    return
                }
            }

            // Run discovery
            println()
            println("Starting folder discovery...")
            println("(This scans folder structure only - no file content is read)")
            println()

            val discovery = FolderDiscovery(
                maxDepth = maxDepth,
                progressCallback = { progress: DiscoveryProgress ->
                    print("\rDiscovered ${progress.foldersDiscovered} folders...")
                }
            )

            val result = discovery.discover(rootPaths)

            println()
            println()
            println("-".repeat(60))
            println("Discovery Complete!")
            println("-".repeat(60))
            println("Folders discovered: ${result.totalFolders}")
            println("Folders skipped (system): ${result.skippedFolders}")
            println("Errors: ${result.errorCount}")
            println("Duration: ${result.durationMs}ms")
            println()

            // Show summary by suggested status
            val bySuggestion = result.folders.groupBy { it.suggestedStatus }
            println("Suggested classifications:")
            println("  SKIP (caches, build dirs): ${bySuggestion[com.oconeco.remotecrawler.discovery.SuggestedStatus.SKIP]?.size ?: 0}")
            println("  LOCATE (metadata only): ${bySuggestion[com.oconeco.remotecrawler.discovery.SuggestedStatus.LOCATE]?.size ?: 0}")
            println("  INDEX (full text): ${bySuggestion[com.oconeco.remotecrawler.discovery.SuggestedStatus.INDEX]?.size ?: 0}")
            println("  UNKNOWN (to classify): ${bySuggestion[com.oconeco.remotecrawler.discovery.SuggestedStatus.UNKNOWN]?.size ?: 0}")
            println()

            // Upload to server
            println("Uploading discovery results to server...")

            try {
                val uploadRequest = DiscoveryUploadRequest(
                    host = host,
                    folders = result.folders.map { toDTO(it) },
                    rootPaths = rootPaths.map { it.toString() },
                    osType = osType,
                    discoveryDurationMs = result.durationMs
                )

                val uploadResponse = client.uploadDiscovery(uploadRequest)

                println()
                println("=".repeat(60))
                println("  Upload Complete!")
                println("=".repeat(60))
                println()
                println("Session ID: ${uploadResponse.sessionId}")
                println("Folders uploaded: ${uploadResponse.foldersReceived}")
                println()
                println("Next step: Open the classification UI to review and configure:")
                println()
                println("  ${uploadResponse.classifyUrl}")
                println()
                println("After classifying folders, run:")
                println("  remote-crawler crawl")
                println()

            } catch (e: Exception) {
                println()
                println("ERROR: Failed to upload discovery results: ${e.message}")
                log.error("Upload failed", e)
                System.exit(1)
            }
        }

        private fun selectPathsInteractively(): List<java.nio.file.Path> {
            val drives = DriveDetector.detectDrives()

            if (drives.isEmpty()) {
                println("No accessible drives/paths detected.")
                print("Enter path manually: ")
                val manualPath = readLine()?.trim()
                return if (manualPath.isNullOrBlank()) {
                    emptyList()
                } else {
                    listOf(Paths.get(manualPath))
                }
            }

            println("Detected drives/roots:")
            println()
            drives.forEachIndexed { index, drive ->
                val sizeStr = String.format("%.1f GB", drive.totalSpaceGb)
                val freeStr = String.format("%.1f GB free", drive.usableSpaceGb)
                println("  [${index + 1}] ${drive.path}")
                println("      $sizeStr total, $freeStr")
            }
            println()
            println("  [a] All drives")
            println("  [m] Enter path manually")
            println()

            print("Select drives to discover (e.g., '1,2' or 'a' for all): ")
            val input = readLine()?.trim()?.lowercase() ?: ""

            return when {
                input == "a" || input == "all" -> drives.map { it.path }
                input == "m" || input == "manual" -> {
                    print("Enter path: ")
                    val manualPath = readLine()?.trim()
                    if (manualPath.isNullOrBlank()) emptyList()
                    else listOf(Paths.get(manualPath))
                }
                else -> {
                    // Parse comma-separated numbers
                    input.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 1..drives.size }
                        .map { drives[it - 1].path }
                }
            }
        }

        private fun toDTO(folder: DiscoveredFolder): DiscoveredFolderDTO {
            return DiscoveredFolderDTO(
                path = folder.path,
                name = folder.name,
                depth = folder.depth,
                folderCount = folder.folderCount,
                fileCount = folder.fileCount,
                totalSize = folder.totalSize,
                isHidden = folder.isHidden,
                suggestedStatus = folder.suggestedStatus.name
            )
        }

        private fun detectOsType(): String {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("win") -> "WINDOWS"
                osName.contains("mac") -> "MACOS"
                else -> "LINUX"
            }
        }
    }

    class TestCommand : Subcommand("test", "Test server connectivity") {
        override fun execute() {
            log.info("Testing connection to {}", serverUrl)

            val client = RemoteCrawlClient(serverUrl, username, password)

            if (client.testConnection()) {
                println("Connection: OK")
                println("Server: $serverUrl")
                println("Authentication: OK")
            } else {
                println("Connection: FAILED")
                println("Server: $serverUrl")
                System.exit(1)
            }
        }
    }

    val crawlCommand = CrawlCommand()
    val statusCommand = StatusCommand()
    val onboardCommand = OnboardCommand()
    val testCommand = TestCommand()

    parser.subcommands(crawlCommand, statusCommand, onboardCommand, testCommand)
    parser.parse(args)
}

private fun getHostName(): String {
    return try {
        // Check environment variable first
        System.getenv("TEMPO_HOST_NAME")?.takeIf { it.isNotBlank() }
            ?: InetAddress.getLocalHost().hostName.lowercase()
    } catch (e: Exception) {
        log.warn("Could not determine hostname, using 'unknown'")
        "unknown"
    }
}
