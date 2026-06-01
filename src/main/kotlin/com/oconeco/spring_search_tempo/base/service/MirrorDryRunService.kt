package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Store
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.ceil

/**
 * Pre-flight estimation for a configured `MirrorConfig` — connects to both
 * endpoints, enumerates the mapped folders, counts messages, sums
 * `RFC822.SIZE` to estimate transfer bytes, and reports how many messages
 * have already been mirrored under this config (so the user sees what a
 * real run would skip).
 *
 * The service is read-only on both sides: it never APPENDs to the
 * destination. Per ADR-005 / issue #25 guardrails, source and destination
 * are probed in parallel via `CompletableFuture`.
 *
 * Timeout scope: the **connect** stage is bounded by 5s per side
 * (matching `EmailConfigValidationService`). The subsequent per-folder
 * FETCH stage is intentionally **unbounded** — fetching `RFC822.SIZE`
 * for every message in a folder is exactly the point of dry-run; on a
 * Gmail-class account with hundreds of thousands of messages it can
 * legitimately take several seconds even on a healthy server. Callers
 * should treat dry-run as a "probe, then measure" operation rather than
 * a five-second health check.
 */
@Service
class MirrorDryRunService(
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val imapConnectionService: ImapConnectionService,
    private val mirroredMessageRepository: MirroredMessageRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun dryRun(mirrorConfigId: Long): MirrorDryRunResult {
        val config = try {
            mirrorConfigService.get(mirrorConfigId)
        } catch (e: NotFoundException) {
            throw IllegalArgumentException("MirrorConfig $mirrorConfigId not found", e)
        }

        val mappings = config.folderMappings.filter { it.enabled }
        if (mappings.isEmpty()) {
            return MirrorDryRunResult.Ok(
                perFolder = emptyList(),
                totals = DryRunTotals(
                    messages = 0L,
                    bytesEstimate = 0L,
                    alreadyMirrored = 0L,
                    estimatedSeconds = 0L
                )
            )
        }

        val source = emailAccountService.get(
            config.sourceAccountId ?: return MirrorDryRunResult.SourceUnreachable("source account not configured")
        )
        val dest = emailAccountService.get(
            config.destAccountId ?: return MirrorDryRunResult.DestUnreachable("destination account not configured")
        )

        // Connect both sides in parallel with a hard 5s timeout each.
        val executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "mirror-dry-run").apply { isDaemon = true }
        }
        try {
            val sourceConnect = CompletableFuture.supplyAsync({ connectStore(source) }, executor)
            val destConnect = CompletableFuture.supplyAsync({ connectStore(dest) }, executor)

            val sourceStore = awaitConnect(sourceConnect, Side.SOURCE)
                ?: return classifyConnectFailure(sourceConnect, Side.SOURCE)
            val destStore = awaitConnect(destConnect, Side.DEST) ?: run {
                runCatching { sourceStore.close() }
                return classifyConnectFailure(destConnect, Side.DEST)
            }

            return sourceStore.use { src ->
                destStore.use { dst ->
                    measure(config, src, dst, mappings)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun measure(
        config: MirrorConfigDTO,
        sourceStore: Store,
        destStore: Store,
        mappings: List<FolderMapping>
    ): MirrorDryRunResult {
        val perFolder = mappings.map { mapping ->
            val (srcCount, srcBytes) = countAndSize(sourceStore, mapping.source)
            val (destCount, destBytes) = countAndSize(destStore, mapping.dest)
            val alreadyMirrored = mirroredMessageRepository
                .countByMirrorConfigIdAndSourceFolder(config.id!!, mapping.source)
            FolderDryRun(
                sourceFolder = mapping.source,
                destFolder = mapping.dest,
                sourceMessageCount = srcCount,
                sourceBytesEstimate = srcBytes,
                destMessageCount = destCount,
                destBytesEstimate = destBytes,
                alreadyMirroredCount = alreadyMirrored
            )
        }

        val totalSourceMessages = perFolder.sumOf { it.sourceMessageCount }
        val totalAlreadyMirrored = perFolder.sumOf { it.alreadyMirroredCount }
        val toCopy = (totalSourceMessages - totalAlreadyMirrored).coerceAtLeast(0L)
        val totalBytes = perFolder.sumOf { it.sourceBytesEstimate }

        val rateLimit = config.appendRateLimitPerSecond
        val estSeconds = if (rateLimit != null && rateLimit > 0) {
            ceil(toCopy.toDouble() / rateLimit).toLong()
        } else {
            // No throttle configured: report a nominal 0 — caller renders as
            // "limited only by network/server". We don't try to infer a
            // realistic ceiling here; that's the real run's job.
            0L
        }

        return MirrorDryRunResult.Ok(
            perFolder = perFolder,
            totals = DryRunTotals(
                messages = toCopy,
                bytesEstimate = totalBytes,
                alreadyMirrored = totalAlreadyMirrored,
                estimatedSeconds = estSeconds
            )
        )
    }

    private fun connectStore(account: EmailAccountDTO): Store = imapConnectionService.connect(account)

    private fun awaitConnect(future: CompletableFuture<Store>, side: Side): Store? {
        return try {
            future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            log.debug("{} connect probe timed out after {}ms", side, PROBE_TIMEOUT_MS)
            null
        } catch (_: ExecutionException) {
            null
        }
    }

    private fun classifyConnectFailure(future: CompletableFuture<Store>, side: Side): MirrorDryRunResult {
        // If the future was cancelled (timeout) we won't get a cause; report as unreachable.
        if (future.isCancelled) {
            return if (side == Side.SOURCE) {
                MirrorDryRunResult.SourceUnreachable("connection timed out after ${PROBE_TIMEOUT_MS / 1000}s")
            } else {
                MirrorDryRunResult.DestUnreachable("connection timed out after ${PROBE_TIMEOUT_MS / 1000}s")
            }
        }
        val cause = try {
            future.get(0, TimeUnit.MILLISECONDS)
            null
        } catch (e: ExecutionException) {
            e.cause
        } catch (e: Exception) {
            e
        }
        return when (cause) {
            is AuthenticationFailedException ->
                MirrorDryRunResult.AuthFailed(side, cause.message ?: "authentication failed")
            is MessagingException, is IOException ->
                if (side == Side.SOURCE) MirrorDryRunResult.SourceUnreachable(cause.message ?: "connect failed")
                else MirrorDryRunResult.DestUnreachable(cause.message ?: "connect failed")
            else ->
                if (side == Side.SOURCE) MirrorDryRunResult.SourceUnreachable(cause?.message ?: "unknown error")
                else MirrorDryRunResult.DestUnreachable(cause?.message ?: "unknown error")
        }
    }

    /**
     * Open the folder read-only and compute (messageCount, sumOfRfc822Size).
     * Missing/unselectable folders return (0, 0) — the dry-run surfaces what
     * exists, the real job is what fails loudly on a typo.
     */
    private fun countAndSize(store: Store, folderName: String): Pair<Long, Long> {
        return try {
            val folder = store.getFolder(folderName)
            if (!folder.exists() || (folder.type and Folder.HOLDS_MESSAGES) == 0) {
                log.debug("Folder {} does not exist or holds no messages", folderName)
                return 0L to 0L
            }
            folder.open(Folder.READ_ONLY)
            try {
                val count = folder.messageCount
                if (count == 0) return 0L to 0L
                val messages = folder.messages
                // Bulk-fetch RFC822.SIZE in one round-trip on IMAP, when possible.
                if (folder is IMAPFolder) {
                    val fp = jakarta.mail.FetchProfile().apply {
                        add(jakarta.mail.FetchProfile.Item.SIZE)
                    }
                    folder.fetch(messages, fp)
                }
                val totalBytes = messages.sumOf { it.size.toLong().coerceAtLeast(0L) }
                count.toLong() to totalBytes
            } finally {
                runCatching { folder.close(false) }
            }
        } catch (e: MessagingException) {
            log.debug("Folder probe failed for {}: {}", folderName, e.message)
            0L to 0L
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
    }
}

enum class Side { SOURCE, DEST }

sealed class MirrorDryRunResult {

    data class Ok(
        val perFolder: List<FolderDryRun>,
        val totals: DryRunTotals
    ) : MirrorDryRunResult()

    data class SourceUnreachable(val reason: String) : MirrorDryRunResult()
    data class DestUnreachable(val reason: String) : MirrorDryRunResult()
    data class AuthFailed(val side: Side, val reason: String) : MirrorDryRunResult()
}

/**
 * Per-folder estimate. `alreadyMirroredCount` is an approximation — it
 * counts what we have recorded for `(mirrorConfigId, sourceFolder)`, not
 * what the destination actually contains. Exact dedup requires the real
 * run's `Message-ID` check on every source UID. (Issue #25 "out of scope".)
 */
data class FolderDryRun(
    val sourceFolder: String,
    val destFolder: String,
    val sourceMessageCount: Long,
    val sourceBytesEstimate: Long,
    val destMessageCount: Long,
    val destBytesEstimate: Long,
    val alreadyMirroredCount: Long
)

data class DryRunTotals(
    val messages: Long,
    val bytesEstimate: Long,
    val alreadyMirrored: Long,
    val estimatedSeconds: Long
)
