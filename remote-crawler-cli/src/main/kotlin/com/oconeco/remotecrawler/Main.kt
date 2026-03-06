package com.oconeco.remotecrawler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
    // Pre-parse verbosity flags (-v, -vv, --verbose)
    val verbosity = countVerbosityFlags(args)
    val filteredArgs = stripVerbosityFlags(args)

    // Configure logging based on verbosity
    configureLogging(verbosity)

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
        description = "Server username (optional; needed for authenticated operations)"
    )

    val password by parser.option(
        ArgType.String,
        shortName = "p",
        fullName = "password",
        description = "Server password (optional; needed for authenticated operations)"
    )

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

        val adaptiveBatching by option(
            ArgType.Boolean,
            fullName = "adaptive-batching",
            description = "Enable adaptive batch sizing (default: false)"
        ).default(false)

        val beginPath by option(
            ArgType.String,
            fullName = "begin",
            description = "Focused crawl start path override (runs from this folder only)"
        )

        override fun execute() {
            val host = hostName ?: getHostName()
            log.info("Running crawl for host: {}", host)

            val client = createClientOrExit(serverUrl, username, password)
            val checks = checkServer(client)

            if (!checks.connectivity.ok) {
                log.error("Failed connectivity check to server at {}", serverUrl)
                printCheckSummary(serverUrl, checks)
                System.exit(1)
            }
            if (!checks.authentication.authenticated) {
                log.error("Connected to server, but authentication failed")
                printCheckSummary(serverUrl, checks)
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
                batchSize = batchSize,
                adaptiveBatching = adaptiveBatching
            )

            // Run each config
            var totalSuccess = 0
            var totalFailed = 0

            for (config in configsToRun) {
                val effectiveConfig = if (!beginPath.isNullOrBlank()) {
                    val normalizedBegin = Paths.get(beginPath!!.trim()).toAbsolutePath().toString()
                    log.info(
                        "Applying focused crawl override for config {}: start path -> {}",
                        config.crawlConfigId,
                        normalizedBegin
                    )
                    config.copy(startPaths = listOf(normalizedBegin))
                } else {
                    config
                }

                log.info("=== Running config: {} (ID: {}) ===", effectiveConfig.name, effectiveConfig.crawlConfigId)

                val result = crawler.crawl(effectiveConfig, host)

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

            val client = createClientOrExit(serverUrl, username, password)
            val checks = checkServer(client)
            printCheckSummary(serverUrl, checks)
            println("Host: $host")
            println()

            if (!checks.connectivity.ok) {
                log.error("Failed to connect to server at {}", serverUrl)
                println("Status: OFFLINE")
                System.exit(1)
            }

            if (!checks.authentication.authenticated) {
                println("Status: ONLINE (connected, authentication failed)")
                System.exit(1)
            }

            println("Status: ONLINE (connected + authenticated)")
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

        val beginPath by option(
            ArgType.String,
            shortName = "b",
            fullName = "begin",
            description = "Focused discovery start folder (overrides --paths/interactive selection)"
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

        val forceNewSession by option(
            ArgType.Boolean,
            shortName = "n",
            fullName = "new",
            description = "Create a new discovery session and archive older sessions for this host"
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
            if (!beginPath.isNullOrBlank()) {
                println("Begin path: ${beginPath!!.trim()}")
            }
            if (forceNewSession) {
                println("Session mode: NEW (archive old sessions)")
            }
            println()

            val client = createClientOrExit(serverUrl, username, password)
            val checks = checkServer(client)

            if (!checks.connectivity.ok) {
                println("ERROR: Failed connectivity check to server at $serverUrl")
                printCheckSummary(serverUrl, checks)
                System.exit(1)
            }
            if (!checks.authentication.authenticated) {
                println("ERROR: Connected to server, but authentication failed.")
                printCheckSummary(serverUrl, checks)
                System.exit(1)
            }
            println("Server connection: OK")
            println("Authentication: OK")
            println()

            // Determine which paths to discover
            if (!beginPath.isNullOrBlank() && !paths.isNullOrBlank()) {
                println("ERROR: Use either --begin or --paths, not both.")
                System.exit(1)
            }

            val rootPaths = if (!beginPath.isNullOrBlank()) {
                listOf(Paths.get(beginPath!!.trim()))
            } else if (paths != null) {
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
                    discoveryDurationMs = result.durationMs,
                    createNewSession = forceNewSession
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

    class TestCommand : Subcommand("test", "Test server connectivity and authentication") {
        override fun execute() {
            log.info("Testing connection to {}", serverUrl)

            val client = createClientOrExit(serverUrl, username, password)
            val checks = checkServer(client)

            printCheckSummary(serverUrl, checks)

            if (!checks.connectivity.ok) {
                System.exit(1)
            }
            if (!checks.authentication.authenticated) {
                System.exit(1)
            }
        }
    }

    class DryRunCommand : Subcommand("dry-run", "Preview how folders would be classified during a crawl") {
        val configId by option(
            ArgType.Int,
            shortName = "c",
            fullName = "config",
            description = "Crawl config ID (required)"
        ).required()

        val detailed by option(
            ArgType.Boolean,
            shortName = "d",
            fullName = "detailed",
            description = "Show all folders (default: only explicit pattern matches)"
        ).default(false)

        val sessionIdOpt by option(
            ArgType.Int,
            fullName = "session",
            description = "Discovery session ID (default: select from top ranked options, then most recent)"
        )

        val statusFilter by option(
            ArgType.String,
            fullName = "status",
            description = "Filter by status (SKIP, LOCATE, INDEX, ANALYZE, SEMANTIC)"
        )

        val pathPrefix by option(
            ArgType.String,
            fullName = "path",
            description = "Filter by path prefix (e.g., 'C:\\Users')"
        )

        val limit by option(
            ArgType.Int,
            shortName = "n",
            fullName = "limit",
            description = "Maximum folders to return"
        ).default(500)

        val outputFile by option(
            ArgType.String,
            shortName = "o",
            fullName = "output",
            description = "Output file for JSON results"
        )

        val showPatterns by option(
            ArgType.Boolean,
            fullName = "show-patterns",
            description = "Show matched patterns in output"
        ).default(false)

        override fun execute() {
            log.info("Running dry-run for config {}", configId)

            val client = createClientOrExit(serverUrl, username, password)
            val checks = checkServer(client)

            if (!checks.connectivity.ok) {
                println("ERROR: Failed connectivity check to server at $serverUrl")
                printCheckSummary(serverUrl, checks)
                System.exit(1)
            }
            if (!checks.authentication.authenticated) {
                println("ERROR: Connected to server, but authentication failed.")
                printCheckSummary(serverUrl, checks)
                System.exit(1)
            }

            try {
                val preferredHost = (hostName ?: getHostName()).trim()
                val resolvedSessionId = resolveDryRunSessionId(
                    client = client,
                    configId = configId.toLong(),
                    explicitSessionId = sessionIdOpt?.toLong(),
                    preferredHost = preferredHost
                )

                val result = client.getDryRun(
                    configId = configId.toLong(),
                    detailed = detailed,
                    sessionId = resolvedSessionId,
                    status = statusFilter,
                    pathPrefix = pathPrefix,
                    limit = limit
                )

                // Print summary
                println()
                println("=".repeat(70))
                println("  Dry Run: ${result.configName}")
                println("=".repeat(70))
                println()
                println("Config ID:    ${result.configId}")
                println("Session ID:   ${result.sessionId}")
                println("Host:         ${result.host}")
                println("Mode:         ${if (result.detailed) "Detailed (all folders)" else "Short (explicit matches only)"}")
                println()
                println("-".repeat(70))
                println("  Classification Summary")
                println("-".repeat(70))
                println()
                println("  SKIP:      ${result.summary.skip.toString().padStart(8)}")
                println("  LOCATE:    ${result.summary.locate.toString().padStart(8)}")
                println("  INDEX:     ${result.summary.index.toString().padStart(8)}")
                println("  ANALYZE:   ${result.summary.analyze.toString().padStart(8)}")
                println("  SEMANTIC:  ${result.summary.semantic.toString().padStart(8)}")
                println()
                println("  Explicit:  ${result.summary.explicitCount.toString().padStart(8)} (pattern matches)")
                println("  Inherited: ${result.summary.inheritedCount.toString().padStart(8)} (from parent)")
                println()
                println("-".repeat(70))
                println("  Folders (${result.returnedFolders} of ${result.totalFolders}${if (result.truncated) ", truncated" else ""})")
                println("-".repeat(70))
                println()

                // Group by status for cleaner output
                val byStatus = result.folders.groupBy { it.status }

                for (status in listOf("SKIP", "LOCATE", "INDEX", "ANALYZE", "SEMANTIC")) {
                    val folders = byStatus[status] ?: continue
                    println("[$status] (${folders.size})")
                    for (folder in folders.take(50)) {
                        val marker = if (folder.explicit) "*" else " "
                        print("  $marker ${folder.path}")
                        if (showPatterns && folder.matchedPattern != null) {
                            print("  <- ${folder.matchedPattern}")
                        } else if (!folder.explicit && folder.inheritedFrom != null) {
                            print("  (inherited)")
                        }
                        println()
                    }
                    if (folders.size > 50) {
                        println("  ... and ${folders.size - 50} more")
                    }
                    println()
                }

                println("-".repeat(70))
                println("Duration: ${result.durationMs}ms")
                println()
                println("Legend: * = explicit pattern match, (no marker) = inherited from parent")
                println()

                // Write to output file if specified
                if (outputFile != null) {
                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
                        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    java.io.File(outputFile!!).writeText(mapper.writeValueAsString(result))
                    println("Results written to: $outputFile")
                }

            } catch (e: Exception) {
                println("ERROR: ${e.message}")
                log.error("Dry run failed", e)
                System.exit(1)
            }
        }
    }

    val crawlCommand = CrawlCommand()
    val statusCommand = StatusCommand()
    val onboardCommand = OnboardCommand()
    val testCommand = TestCommand()
    val dryRunCommand = DryRunCommand()

    parser.subcommands(crawlCommand, statusCommand, onboardCommand, testCommand, dryRunCommand)
    parser.parse(filteredArgs)
}

private data class ServerCheckSummary(
    val connectivity: ConnectivityCheckResult,
    val authentication: AuthCheckResult
)

private fun createClientOrExit(serverUrl: String, username: String?, password: String?): RemoteCrawlClient {
    val normalizedUser = username?.trim()?.takeIf { it.isNotBlank() }
    val normalizedPass = password?.trim()?.takeIf { it.isNotBlank() }
    if ((normalizedUser == null) != (normalizedPass == null)) {
        println("ERROR: Provide both --username and --password, or provide neither.")
        System.exit(1)
    }
    return RemoteCrawlClient(
        baseUrl = serverUrl,
        username = normalizedUser,
        password = normalizedPass
    )
}

private fun checkServer(client: RemoteCrawlClient): ServerCheckSummary =
    ServerCheckSummary(
        connectivity = client.testConnectivity(),
        authentication = client.testAuthentication()
    )

private fun printCheckSummary(serverUrl: String, checks: ServerCheckSummary) {
    println("Server: $serverUrl")
    println(
        "Connectivity: " +
            if (checks.connectivity.ok) {
                "OK (${checks.connectivity.statusCode ?: "n/a"})"
            } else {
                "FAILED (${checks.connectivity.message})"
            }
    )
    val authStatus = if (checks.authentication.authenticated) {
        "OK (${checks.authentication.statusCode ?: "n/a"})"
    } else {
        "FAILED (${checks.authentication.message})"
    }
    println("Authentication: $authStatus")
}

private fun resolveDryRunSessionId(
    client: RemoteCrawlClient,
    configId: Long,
    explicitSessionId: Long?,
    preferredHost: String
): Long? {
    if (explicitSessionId != null) return explicitSessionId

    val candidates = try {
        client.listDiscoverySessions(
            configId = configId,
            requestedHost = preferredHost,
            limit = 3
        )
    } catch (e: Exception) {
        log.debug("Could not load discovery session candidates for config {}: {}", configId, e.message)
        return null
    }

    if (candidates.isEmpty()) return null
    if (candidates.size == 1) {
        val only = candidates.first()
        println("Using discovery session ${only.sessionId} (${only.host}, ${only.status})")
        return only.sessionId
    }

    println()
    println("Multiple discovery sessions found for config $configId:")
    candidates.forEachIndexed { index, candidate ->
        val updated = candidate.lastUpdated ?: candidate.dateCreated ?: "unknown"
        val hostMarker = if (candidate.hostMatched) " host-match" else ""
        println(
            "  [${index + 1}] session=${candidate.sessionId} host=${candidate.host}$hostMarker " +
                "status=${candidate.status} updated=$updated " +
                "classified=${candidate.classifiedFolders}/${candidate.totalFolders}"
        )
    }

    if (System.console() == null) {
        val selected = candidates.first()
        println("Non-interactive mode detected; selecting [1] session ${selected.sessionId}.")
        return selected.sessionId
    }

    while (true) {
        print("Select discovery session [1-${candidates.size}] (Enter for 1): ")
        val input = readLine()?.trim().orEmpty()
        if (input.isBlank()) return candidates.first().sessionId
        val idx = input.toIntOrNull()
        if (idx != null && idx in 1..candidates.size) {
            return candidates[idx - 1].sessionId
        }
        println("Invalid selection '$input'.")
    }
}

/**
 * Count verbosity flags in args.
 * -v = 1, -vv = 2, -vvv = 3, etc.
 * --verbose counts as 1
 */
private fun countVerbosityFlags(args: Array<String>): Int {
    var count = 0
    for (arg in args) {
        when {
            arg == "--verbose" -> count++
            arg.startsWith("-") && !arg.startsWith("--") && arg.all { it == '-' || it == 'v' } -> {
                // Count 'v' characters in flags like -v, -vv, -vvv
                count += arg.count { it == 'v' }
            }
        }
    }
    return count
}

/**
 * Strip verbosity flags from args so the parser doesn't complain about unknown options.
 */
private fun stripVerbosityFlags(args: Array<String>): Array<String> {
    return args.filter { arg ->
        when {
            arg == "--verbose" -> false
            arg.startsWith("-") && !arg.startsWith("--") && arg.all { it == '-' || it == 'v' } -> false
            else -> true
        }
    }.toTypedArray()
}

/**
 * Configure logging level based on verbosity.
 * 0 = INFO (default)
 * 1 = DEBUG for com.oconeco packages
 * 2+ = TRACE for com.oconeco packages
 */
private fun configureLogging(verbosity: Int) {
    if (verbosity <= 0) return

    val level = if (verbosity >= 2) Level.TRACE else Level.DEBUG

    // Configure the package logger and all relevant child loggers
    val loggerNames = listOf(
        "com.oconeco",
        "com.oconeco.remotecrawler",
        "com.oconeco.remotecrawler.client",
        "com.oconeco.remotecrawler.crawler",
        "com.oconeco.remotecrawler.discovery",
        "RemoteCrawler"
    )

    for (loggerName in loggerNames) {
        val logger = LoggerFactory.getLogger(loggerName) as? Logger
        logger?.setLevel(level)
    }

    log.debug("Verbosity level {} - logging set to {}", verbosity, level)
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
