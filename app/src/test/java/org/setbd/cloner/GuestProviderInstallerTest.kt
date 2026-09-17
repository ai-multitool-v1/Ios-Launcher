package org.setbd.cloner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.setbd.cloner.engine.guest.GuestProviderInstaller

/**
 * Pure-JVM tests for the guest provider installer's authority registry —
 * the piece that decides whether a clone may install a given provider
 * authority (cross-clone collisions must be refused, not crash).
 */
class GuestProviderInstallerTest {

    @Test
    fun `first claim always wins`() {
        assertTrue(GuestProviderInstaller.claimAuthority("test.first.authority", 1L))
    }

    @Test
    fun `same clone re-claiming is idempotent`() {
        GuestProviderInstaller.claimAuthority("test.second.authority", 2L)
        assertTrue(GuestProviderInstaller.claimAuthority("test.second.authority", 2L))
    }

    @Test
    fun `different clone claiming an owned authority is refused`() {
        GuestProviderInstaller.claimAuthority("test.third.authority", 3L)
        assertFalse(GuestProviderInstaller.claimAuthority("test.third.authority", 4L))
    }

    @Test
    fun `release frees the authority for another clone`() {
        GuestProviderInstaller.claimAuthority("test.fourth.authority", 5L)
        GuestProviderInstaller.releaseAuthoritiesOf(5L)
        assertTrue(GuestProviderInstaller.claimAuthority("test.fourth.authority", 6L))
    }

    @Test
    fun `anti-detect providers are recognized`() {
        assertTrue(GuestProviderInstaller.isAntiDetectProvider("com.meituan.hades.Provider"))
        assertTrue(GuestProviderInstaller.isAntiDetectProvider("com.alibaba.securityguard.foo"))
        assertFalse(GuestProviderInstaller.isAntiDetectProvider("androidx.startup.InitializationProvider"))
    }

    @Test
    fun `registry stays bounded-safe under mixed traffic`() {
        val before = GuestProviderInstaller.registeredAuthorityCount()
        GuestProviderInstaller.claimAuthority("test.fifth.authority", 7L)
        assertEquals(before + 1, GuestProviderInstaller.registeredAuthorityCount())
    }
}
