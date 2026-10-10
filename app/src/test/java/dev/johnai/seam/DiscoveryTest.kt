package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching a paired computer among mDNS results. See protocol/PROTOCOL.md. */
class DiscoveryTest {
    private val fp = "ab".repeat(32)

    @Test
    fun matchingFingerprintChosen() {
        val match = DiscoveredService("192.168.1.50", 47100, mapOf("fp" to "AB".repeat(32)))
        val other = DiscoveredService("10.0.0.9", 9, mapOf("fp" to "cd".repeat(32)))
        assertEquals(match, pickDiscoveredService(listOf(other, match), fp))
        assertEquals(match, pickDiscoveredService(listOf(match, other), fp))
        // TXT fp and the stored fingerprint are compared case-insensitively.
        assertEquals(match, pickDiscoveredService(listOf(match), "AB".repeat(32)))
        val upperKey = DiscoveredService("192.168.1.51", 47102, mapOf("FP" to fp))
        assertEquals(upperKey, pickDiscoveredService(listOf(other, upperKey), fp))
    }

    @Test
    fun nonMatchingIgnored() {
        val other = DiscoveredService("10.0.0.9", 47100, mapOf("fp" to "cd".repeat(32)))
        val prefix = DiscoveredService("10.0.0.2", 47100, mapOf("fp" to fp.dropLast(1)))
        assertNull(pickDiscoveredService(listOf(other), fp))
        assertNull(pickDiscoveredService(listOf(other, prefix), "AB".repeat(32)))
        assertNull(serviceToConnect(listOf(other), fp))
    }

    @Test
    fun missingFpIgnored() {
        val noTxt = DiscoveredService("10.0.0.9", 47100, emptyMap())
        val otherKey = DiscoveredService("10.0.0.10", 47100, mapOf("name" to "desktop"))
        val blank = DiscoveredService("10.0.0.11", 47100, mapOf("fp" to ""))
        assertNull(pickDiscoveredService(listOf(noTxt, otherKey, blank), fp))
        assertNull(serviceToConnect(listOf(noTxt, otherKey, blank), fp))
    }

    @Test
    fun emptyListGivesNull() {
        assertNull(pickDiscoveredService(emptyList(), fp))
        assertNull(serviceToConnect(emptyList(), fp))
    }

    @Test
    fun routableAddressIsTriedBeforeLinkLocal() {
        assertEquals(
            listOf("192.168.1.50", "2001:db8::5", "169.254.3.4", "fe80::1%wlan0"),
            orderedConnectHosts(listOf("fe80::1%wlan0", "169.254.3.4", "2001:db8::5", "192.168.1.50")),
        )
        assertEquals(listOf("fe80::1"), orderedConnectHosts(listOf("fe80::1", "", " fe80::1 ")))
        assertTrue(isLinkLocalHost("[fe80::abcd%wlan0]"))
        assertFalse(isLinkLocalHost("192.168.1.50"))
    }

    @Test
    fun laterUpdateDoesNotDropFingerprintPortOrGlobalAddress() {
        val partial = DiscoveredService("fe80::1%wlan0", 0, mapOf("FP" to fp), listOf("fe80::1%wlan0"))
        assertFalse(isResolvedService(partial))
        val withAddress = DiscoveredService(
            "192.168.1.50",
            47100,
            emptyMap(),
            listOf("192.168.1.50", "fe80::1%wlan0"),
        )
        val merged = mergeDiscovered(partial, withAddress)
        assertEquals("192.168.1.50", merged.host)
        assertEquals(47100, merged.port)
        assertEquals(fp, merged.txt["FP"])
        assertEquals(listOf("192.168.1.50", "fe80::1%wlan0"), merged.hosts)
        assertTrue(isResolvedService(merged))
        assertEquals(merged.host, pickDiscoveredService(listOf(merged), fp)?.host)
    }

    @Test
    fun connectTargetSkipsOtherFingerprintsAndPrefersRoutable() {
        val other = DiscoveredService("1.2.3.4", 9, mapOf("fp" to "cd".repeat(32)), listOf("1.2.3.4"))
        val linkLocal = DiscoveredService("fe80::1%wlan0", 47100, mapOf("fp" to fp), listOf("fe80::1%wlan0"))
        val global = DiscoveredService("192.168.1.50", 47100, mapOf("fp" to "AB".repeat(32)), listOf("192.168.1.50"))
        val chosen = serviceToConnect(listOf(other, linkLocal, global), fp)
        assertEquals("192.168.1.50", chosen?.host)
        assertEquals(47100, chosen?.port)
        assertEquals(listOf("192.168.1.50", "fe80::1%wlan0"), chosen?.hosts)
        assertFalse(chosen!!.hosts.contains("1.2.3.4"))
    }

    @Test
    fun partialWithoutFpOrPortIsNotAConnectTarget() {
        val partial = DiscoveredService("10.0.0.2", 0, mapOf("txtvers" to "1"), listOf("10.0.0.2"))
        assertNull(pickDiscoveredService(listOf(partial), fp))
        assertNull(serviceToConnect(listOf(partial), fp))
        val ready = mergeDiscovered(partial, DiscoveredService("10.0.0.2", 47100, mapOf("fp" to fp)))
        assertEquals("10.0.0.2", serviceToConnect(listOf(ready), fp)?.host)
        assertEquals(47100, serviceToConnect(listOf(ready), fp)?.port)
    }
}
