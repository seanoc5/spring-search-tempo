package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Unit tests for [TcpReachabilityProbe] (issue #72).
 *
 * The probe is the extracted, directly-testable helper the issue's test plan
 * calls out: it backs both the pre-save sanity check on the EmailAccount
 * edit form and the first stage of the existing Test Connection flow.
 */
@DisplayName("TcpReachabilityProbe — fast TCP connect probe (issue #72)")
class TcpReachabilityProbeTest {

    private val probe = TcpReachabilityProbe()

    private lateinit var listener: ServerSocket

    @BeforeEach
    fun openLocalListener() {
        // Bind to an ephemeral port on the loopback interface so we have
        // something guaranteed-reachable for the success case.
        listener = ServerSocket(0)
    }

    @AfterEach
    fun closeLocalListener() {
        listener.close()
    }

    @Test
    @DisplayName("Reachable when a local listener accepts the connect")
    fun reachableLocalhost() {
        val result = probe.probe("127.0.0.1", listener.localPort, timeoutMs = 1_000)
        assertThat(result).isEqualTo(TcpReachabilityProbe.Result.Reachable)
    }

    @Test
    @DisplayName("Unreachable when the hostname does not resolve")
    fun unknownHost() {
        // RFC 2606 reserves .invalid for this exact purpose — guaranteed to never resolve.
        val result = probe.probe("does-not-resolve.invalid", 993, timeoutMs = 1_000)
        assertThat(result).isInstanceOf(TcpReachabilityProbe.Result.Unreachable::class.java)
        val reason = (result as TcpReachabilityProbe.Result.Unreachable).reason
        assertThat(reason).containsIgnoringCase("does-not-resolve.invalid")
    }

    @Test
    @DisplayName("Unreachable with a connection-refused style message when the port is closed")
    fun connectionRefused() {
        // Bind a server socket then immediately close it — the port goes from "open" to
        // "refused" so the probe surfaces a fast ECONNREFUSED rather than a timeout.
        val ephemeral = ServerSocket(0).use { it.localPort }
        val result = probe.probe("127.0.0.1", ephemeral, timeoutMs = 1_000)
        assertThat(result).isInstanceOf(TcpReachabilityProbe.Result.Unreachable::class.java)
    }

    @Test
    @DisplayName("Unreachable when host is blank")
    fun blankHost() {
        val result = probe.probe("", 993)
        assertThat(result).isInstanceOf(TcpReachabilityProbe.Result.Unreachable::class.java)
    }

    @Test
    @DisplayName("Unreachable when port is non-positive")
    fun badPort() {
        val result = probe.probe("127.0.0.1", 0)
        assertThat(result).isInstanceOf(TcpReachabilityProbe.Result.Unreachable::class.java)
    }
}
