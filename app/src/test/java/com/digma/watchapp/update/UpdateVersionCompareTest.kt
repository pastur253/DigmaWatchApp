package com.digma.watchapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionCompareTest {

    private fun cleanVersion(version: String): String {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split("-")[0]
            .split("+")[0]
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    @Test
    fun testVersionComparison() {
        val vCurrent = cleanVersion("v0.0.1")
        val vNew = cleanVersion("v0.0.2")

        assertTrue(compareVersions(vNew, vCurrent) > 0)
        assertFalse(compareVersions(vCurrent, vNew) > 0)
        assertEquals(0, compareVersions(vCurrent, vCurrent))
    }

    @Test
    fun testMajorMinorPatchComparison() {
        assertTrue(compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(compareVersions("0.1.0", "0.0.9") > 0)
        assertTrue(compareVersions("0.0.10", "0.0.9") > 0)
    }

    @Test
    fun testVersionCleaning() {
        assertEquals("0.0.2", cleanVersion("v0.0.2"))
        assertEquals("1.2.3", cleanVersion("V1.2.3-beta1"))
        assertEquals("2.0.0", cleanVersion("2.0.0+build123"))
    }
}
