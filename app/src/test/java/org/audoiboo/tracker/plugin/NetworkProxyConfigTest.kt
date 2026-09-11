package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProxyConfigTest {
    @Test
    fun disabledProxyIsNeverUsable() {
        assertFalse(NetworkProxyConfig(enabled = false, host = "proxy.example", port = 8080).isUsable)
    }

    @Test
    fun enabledProxyNeedsValidHostAndPort() {
        assertFalse(NetworkProxyConfig(enabled = true, host = "", port = 8080).isUsable)
        assertFalse(NetworkProxyConfig(enabled = true, host = "proxy.example", port = 0).isUsable)
        assertTrue(NetworkProxyConfig(enabled = true, host = "proxy.example", port = 8080).isUsable)
    }

    @Test
    fun sanitizesCommonHostInputWithoutChangingCredentials() {
        val clean = NetworkProxyConfig(
            enabled = true,
            host = " https://proxy.example/ ",
            port = 8080,
            username = " user ",
            password = " secret "
        ).sanitized()

        assertEquals("proxy.example", clean.host)
        assertEquals("user", clean.username)
        assertEquals(" secret ", clean.password)
    }
}
