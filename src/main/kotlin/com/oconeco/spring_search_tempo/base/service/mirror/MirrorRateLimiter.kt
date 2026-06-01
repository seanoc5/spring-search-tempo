package com.oconeco.spring_search_tempo.base.service.mirror

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal per-config token-bucket throttle for IMAP APPEND.
 *
 * One bucket per `mirrorConfigId`. Each `acquire()` consumes one token; if
 * the bucket is empty, the caller is parked until the next token arrives.
 * Tokens refill at `permitsPerSecond` (passed in on construction of the
 * bucket — typically `MirrorConfig.appendRateLimitPerSecond`). Bucket size
 * is capped at one second of permits so a paused mirror can't burst-flood
 * the destination after a long idle period.
 *
 * Why a hand-rolled limiter instead of Guava's `RateLimiter`: the project
 * doesn't depend on Guava and a single-purpose token bucket is ~30 lines.
 * Resilience4j ships a `RateLimiter`, but adding the dependency for one
 * throttle is over-investment. Revisit if/when a second consumer needs the
 * same primitive.
 *
 * Thread-safety: per-bucket state is guarded by `synchronized(bucket)`. The
 * bucket map uses `ConcurrentHashMap.computeIfAbsent` so two mirror runs on
 * the same config share the same bucket.
 */
class MirrorRateLimiter {

    private val buckets = ConcurrentHashMap<Long, TokenBucket>()

    /**
     * Acquire one APPEND permit for the given mirror config.
     *
     * @param mirrorConfigId scope of the throttle.
     * @param permitsPerSecond null → unthrottled (no-op). Re-configuring the
     *        rate for an existing config requires a process restart; that's
     *        acceptable for the initial migration use case.
     */
    fun acquire(mirrorConfigId: Long, permitsPerSecond: Int?) {
        if (permitsPerSecond == null || permitsPerSecond <= 0) return
        val bucket = buckets.computeIfAbsent(mirrorConfigId) {
            TokenBucket(permitsPerSecond.toDouble())
        }
        bucket.acquire()
    }

    /** Visible for tests; resets state between runs. */
    internal fun reset() {
        buckets.clear()
    }

    private class TokenBucket(private val permitsPerSecond: Double) {
        private val maxTokens = permitsPerSecond.coerceAtLeast(1.0)
        private var tokens = maxTokens
        private var lastRefillNanos = System.nanoTime()

        fun acquire() {
            while (true) {
                val waitMillis = synchronized(this) {
                    refill()
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        0L
                    } else {
                        val needed = 1.0 - tokens
                        ((needed / permitsPerSecond) * 1000.0).toLong().coerceAtLeast(1L)
                    }
                }
                if (waitMillis == 0L) return
                try {
                    Thread.sleep(waitMillis)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
            if (elapsedSec > 0.0) {
                tokens = (tokens + elapsedSec * permitsPerSecond).coerceAtMost(maxTokens)
                lastRefillNanos = now
            }
        }
    }
}
