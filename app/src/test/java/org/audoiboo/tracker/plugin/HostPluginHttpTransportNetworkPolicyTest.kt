package org.audoiboo.tracker.plugin

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPluginHttpTransportNetworkPolicyTest {
    @Test
    fun retriesProxyForGenericSocketNetworkUnreachable() {
        val error = SocketException("Network is unreachable")

        assertTrue(HostPluginHttpTransport.isNetworkFailure(error))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(error))
        assertFalse(HostPluginHttpTransport.shouldRetryWithDoh(error))
        assertEquals("SocketException", HostPluginHttpTransport.networkFailureName(error))
    }

    @Test
    fun preservesExistingNetworkFailureCoverage() {
        listOf(
            UnknownHostException("dns"),
            ConnectException("connect"),
            NoRouteToHostException("route"),
            SocketTimeoutException("timeout")
        ).forEach { error ->
            assertTrue(error.javaClass.simpleName, HostPluginHttpTransport.isNetworkFailure(error))
            assertTrue(error.javaClass.simpleName, HostPluginHttpTransport.shouldRetryWithProxy(error))
        }
    }

    @Test
    fun nestedSocketFailureIsDetected() {
        val wrapped = IllegalStateException("wrapper", SocketException("Network is unreachable"))

        assertTrue(HostPluginHttpTransport.isNetworkFailure(wrapped))
        assertTrue(HostPluginHttpTransport.shouldRetryWithProxy(wrapped))
        assertEquals("SocketException", HostPluginHttpTransport.networkFailureName(wrapped))
    }
}
