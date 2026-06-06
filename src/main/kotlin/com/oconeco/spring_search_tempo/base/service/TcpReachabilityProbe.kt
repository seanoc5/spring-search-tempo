package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Fast, side-effect-free TCP-connect probe used as a pre-save sanity check
 * for IMAP host/port pairs (issue #72). Catches typos in single-digit
 * seconds before a full sync would otherwise hang on a 60s connect.
 *
 * Returns a [Result] sealed type rather than throwing; callers turn that
 * into a field-level form error.
 */
@Service
class TcpReachabilityProbe {

    private val log = LoggerFactory.getLogger(javaClass)

    fun probe(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result {
        if (host.isBlank() || port <= 0) {
            return Result.Unreachable("Missing host or port")
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            Result.Reachable
        } catch (e: SocketTimeoutException) {
            log.debug("TCP probe timeout for {}:{}", host, port)
            Result.Unreachable("Connection timed out after ${timeoutMs}ms")
        } catch (e: UnknownHostException) {
            log.debug("TCP probe unknown host for {}:{}", host, port)
            Result.Unreachable("Unknown host: $host")
        } catch (e: IOException) {
            log.debug("TCP probe failed for {}:{}: {}", host, port, e.message)
            Result.Unreachable(e.message ?: "Connection refused")
        }
    }

    sealed class Result {
        data object Reachable : Result()
        data class Unreachable(val reason: String) : Result()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}
