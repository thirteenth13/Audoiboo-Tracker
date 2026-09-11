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
    fun retriesDnsAndConnectionFailuresThroughDoh() {
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(UnknownHostException("dns")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(ConnectException("blocked")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(NoRouteToHostException("route")))
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(SocketTimeoutException("timeout")))
    }

    @Test
    fun findsNetworkFailureInsideWrappedException() {
        val wrapped = IllegalStateException("wrapper", ConnectException("connect"))
        assertTrue(HostPluginHttpTransport.shouldRetryWithDoh(wrapped))
        assertEquals("ConnectException", HostPluginHttpTransport.networkFailureName(wrapped))
    }

    @Test
    fun doesNotSendParserOrTlsLogicErrorsToDoh() {
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(IllegalArgumentException("bad url")))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(EOFException("truncated")))
    }

    @Test
    fun eofStillUsesDedicatedTruncatedResponseRetry() {
        assertTrue(HostPluginHttpTransport.shouldRetryTruncatedResponse(EOFException("truncated")))
        assertFalse(HostPluginHttpTransport.shouldRetryTruncatedResponse(ConnectException("connect")))
    }
}
