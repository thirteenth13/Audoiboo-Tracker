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
        assertSame(
            AudiobooSourcePlugin,
            registry.forUrl("https://audioboo.org/book", SourceCapability.DOWNLOAD_RESOLUTION)
        )
        assertNull(registry.forUrl("https://example.org/book"))
        assertEquals(
            listOf(AudiobooSourcePlugin),
            registry.withCapability(SourceCapability.DOWNLOAD_RESOLUTION)
        )
    }

    @Test
    fun capabilityAwareRoutingSkipsPluginWithoutRequestedCapability() {
        val metadataOnly = fakePlugin("metadata", setOf(SourceCapability.BOOK_LOOKUP))
        val series = fakePlugin("series", setOf(SourceCapability.SERIES_LOOKUP))
        val registry = SourcePluginRegistry(listOf(metadataOnly, series))

        assertSame(metadataOnly, registry.forUrl("https://example.org/item"))
        assertSame(series, registry.forUrl("https://example.org/item", SourceCapability.SERIES_LOOKUP))
        assertNull(registry.forUrl("https://example.org/item", SourceCapability.DOWNLOAD_RESOLUTION))
    }

    @Test(expected = IllegalArgumentException::class)
    fun registryRejectsDuplicatePluginIds() {
        val duplicate = object : SourcePlugin {
            override val descriptor = AudiobooSourcePlugin.descriptor.copy(name = "Duplicate")
            override fun supports(url: String) = false
        }
        SourcePluginRegistry(listOf(AudiobooSourcePlugin, duplicate))
    }

    private fun fakePlugin(id: String, capabilities: Set<SourceCapability>) = object : SourcePlugin {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("example.org"),
            capabilities = capabilities
        )

        override fun supports(url: String): Boolean = url.startsWith("https://example.org/")
    }
}
