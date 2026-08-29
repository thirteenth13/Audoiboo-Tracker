package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePluginApiTest {
    @Test
    fun audiobooPluginRecognizesOnlyDeclaredHosts() {
        assertTrue(AudiobooSourcePlugin.supports("https://audioboo.org/litrpg/example.html"))
        assertTrue(AudiobooSourcePlugin.supports("https://www.audioboo.org/xfsearch/cikl/test/"))
        assertFalse(AudiobooSourcePlugin.supports("https://example.org/audioboo.org/book"))
        assertFalse(AudiobooSourcePlugin.supports("not a url"))
    }

    @Test
    fun builtInRegistryFindsAudiobooByUrlAndCapability() {
        val registry = BuiltInSourcePlugins.registry

        assertSame(AudiobooSourcePlugin, registry.byId("audioboo"))
        assertSame(AudiobooSourcePlugin, registry.forUrl("https://audioboo.org/book"))
        assertNull(registry.forUrl("https://example.org/book"))
        assertEquals(
            listOf(AudiobooSourcePlugin),
            registry.withCapability(SourceCapability.DOWNLOAD_RESOLUTION)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun registryRejectsDuplicatePluginIds() {
        val duplicate = object : SourcePlugin {
            override val descriptor = AudiobooSourcePlugin.descriptor.copy(name = "Duplicate")
            override fun supports(url: String) = false
        }
        SourcePluginRegistry(listOf(AudiobooSourcePlugin, duplicate))
    }
}
