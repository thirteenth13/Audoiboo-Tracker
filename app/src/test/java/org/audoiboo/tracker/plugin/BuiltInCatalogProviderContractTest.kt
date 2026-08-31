package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInCatalogProviderContractTest {
    @Test
    fun `ships three independent author catalog providers`() {
        val providers = BuiltInSourcePluginManager.registry
            .withCapability(SourceCapability.AUTHOR_CATALOG)

        assertEquals(
            setOf("open-library", "google-books", "fantlab"),
            providers.map { it.descriptor.id }.toSet()
        )
        assertTrue(providers.all { it is AuthorCatalogProvider })
    }

    @Test
    fun `catalog providers do not masquerade as audio series search providers`() {
        val providers = BuiltInSourcePluginManager.registry
            .withCapability(SourceCapability.AUTHOR_CATALOG)

        assertTrue(providers.none { SourceCapability.SERIES_SEARCH in it.descriptor.capabilities })
        assertTrue(providers.none { it is SeriesSearchProvider })
    }
}
