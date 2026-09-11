package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogCanonicalUrlPolicyTest {
    @Test
    fun reconstructsFantlabSeriesUrlFromStableCatalogId() {
        assertEquals(
            "catalog://fantlab/series/fantlab%3A82803%3A%D1%81%D1%82%D0%B5%D0%BB%D0%BB%D0%B0%D1%80",
            CatalogCanonicalUrlPolicy.canonicalSeriesUrl("catalog::fantlab:82803:стеллар")
        )
    }

    @Test
    fun reconstructsBookUrlFromStableCatalogBookId() {
        val seriesId = "catalog::fantlab:82803:стеллар"
        assertEquals(
            "catalog://fantlab/book/12345",
            CatalogCanonicalUrlPolicy.canonicalBookUrl(seriesId, "$seriesId::fantlab:12345")
        )
    }

    @Test
    fun preservesRemoteIdTailAfterFirstProviderSeparator() {
        val seriesId = "catalog::open-library:series:space-opera"
        assertEquals(
            "catalog://open-library/book/OL%3Awork%3A123",
            CatalogCanonicalUrlPolicy.canonicalBookUrl(seriesId, "$seriesId::open-library:OL:work:123")
        )
    }

    @Test
    fun refusesToRepairNonCatalogOrMalformedIdentities() {
        assertNull(CatalogCanonicalUrlPolicy.canonicalSeriesUrl("provider-series"))
        assertNull(CatalogCanonicalUrlPolicy.canonicalSeriesUrl("catalog::broken"))
        assertNull(CatalogCanonicalUrlPolicy.canonicalBookUrl("catalog::fantlab:82803:стеллар", "provider-book"))
        assertNull(CatalogCanonicalUrlPolicy.canonicalBookUrl("catalog::fantlab:82803:стеллар", "catalog::fantlab:82803:стеллар::broken"))
    }
}
