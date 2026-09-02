package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortedExperimentalMediaRuntimeTest {
    @Test fun routesProvenExperimentalSitesThroughPortedTraversal() {
        assertTrue(PortedExperimentalMediaRuntime.supports("knigavuhe"))
        assertTrue(PortedExperimentalMediaRuntime.supports("poleknig"))
        assertTrue(PortedExperimentalMediaRuntime.supports("izib"))
        assertTrue(PortedExperimentalMediaRuntime.supports("lis10book"))
        assertFalse(PortedExperimentalMediaRuntime.supports("baza-knig"))
    }
}
