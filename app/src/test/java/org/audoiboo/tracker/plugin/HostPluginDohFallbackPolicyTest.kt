package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HostPluginDohFallbackPolicyTest {
    @Test
    fun dohIsUsedOnlyForRealDnsFailure() {
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(UnknownHostException("dns")))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(ConnectException("blocked")))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(NoRouteToHostException("route")))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(SocketTimeoutException("timeout")))
    }

    @Test
    fun proxyAcceptsDnsAndConnectionFailures() {
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(UnknownHostException("dns")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(ConnectException("blocked")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(NoRouteToHostException("route")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(SocketTimeoutException("timeout")))
        assertFalse(HostPluginHttpTransport.shouldRetryWithProxy(IllegalArgumentException("bad url")))
    }

    @Test
    fun findsNetworkFailureInsideWrappedException() {
        val wrapped = IllegalStateException("wrapper", ConnectException("connect"))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(wrapped))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(wrapped))
        assertEquals("ConnectException", HostPluginHttpTransport.networkFailureName(wrapped))
    }

    @Test
    fun fantlabUsesBoundedTransportTimeout() {
        assertEquals(6_000, HostPluginHttpTransport.requestTimeoutMs("https://api.fantlab.ru/search-autors?q=test"))
        assertEquals(20_000, HostPluginHttpTransport.requestTimeoutMs("https://example.org/book"))
    }

    @Test
    fun eofStillUsesDedicatedTruncatedResponseRetry() {
        assertTrue(HostPluginHttpTransport.shouldRetryTruncatedResponse(EOFException("truncated")))
        assertFalse(HostPluginHttpTransport.shouldRetryTruncatedResponse(ConnectException("connect")))
    }
}
