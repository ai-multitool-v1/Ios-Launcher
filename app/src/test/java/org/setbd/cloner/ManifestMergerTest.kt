package org.setbd.cloner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.setbd.cloner.engine.guest.ManifestMerger
import org.setbd.cloner.engine.guest.ManifestParser
import org.junit.Test

/**
 * Pure-JVM tests for the redundant manifest resolution chain that keeps the
 * container launch alive when the binary-XML manifest source fails.
 */
class ManifestMergerTest {

    private fun activity(name: String, launcher: Boolean = false) =
        ManifestParser.GuestActivity(name, themeRes = 0, launchMode = "standard", isLauncher = launcher)

    @Test
    fun `xml launcher wins over stored launcher`() {
        val xml = ManifestParser.ManifestData(
            applicationClassName = "com.app.MainApp",
            labelRes = 1, iconRes = 2, applicationThemeRes = 3,
            activities = listOf(
                activity("com.app.MainActivity", launcher = true),
                activity("com.app.SettingsActivity")
            )
        )
        val merged = ManifestMerger.merge(xml, null, "com.app.OtherActivity")

        assertNotNull(merged)
        assertEquals("com.app.MainApp", merged!!.applicationClassName)
        assertEquals("com.app.MainActivity", merged.launcherActivity?.className)
        assertEquals(2, merged.activities.size)
    }

    @Test
    fun `stored launcher is used when xml has no launcher filter`() {
        val xml = ManifestParser.ManifestData(
            applicationClassName = null, labelRes = 0, iconRes = 0, applicationThemeRes = 0,
            activities = listOf(activity("com.app.SplashActivity"))
        )
        val merged = ManifestMerger.merge(xml, null, "com.app.HomeActivity")

        assertNotNull(merged)
        assertEquals("com.app.HomeActivity", merged!!.launcherActivity?.className)
        // Both the stored launcher and the xml activity remain known.
        assertTrue(merged.activities.any { it.className == "com.app.HomeActivity" })
        assertTrue(merged.activities.any { it.className == "com.app.SplashActivity" })
    }

    @Test
    fun `archive fills in when xml parse fails entirely`() {
        val archive = ManifestMerger.ArchiveMeta(
            applicationClass = "com.app.GuestApp",
            labelRes = 7, iconRes = 8, applicationThemeRes = 9,
            activities = listOf(
                ManifestMerger.ArchiveActivity("com.app.MainActivity", themeRes = 11, launchMode = "singleTask")
            )
        )
        val merged = ManifestMerger.merge(null, archive, "com.app.StoredLauncher")

        assertNotNull(merged)
        // Stored launcher takes priority over the archive's first activity.
        assertEquals("com.app.StoredLauncher", merged!!.launcherActivity?.className)
        assertEquals("com.app.GuestApp", merged.applicationClassName)
        assertEquals(7, merged.labelRes)
        assertTrue(merged.activities.any { it.className == "com.app.MainActivity" && it.launchMode == "singleTask" })
    }

    @Test
    fun `stored launcher alone is enough to run`() {
        val merged = ManifestMerger.merge(null, null, "com.app.MainActivity")

        assertNotNull(merged)
        assertEquals("com.app.MainActivity", merged!!.launcherActivity?.className)
        assertEquals(1, merged.activities.size)
        assertTrue(merged.activities.first().isLauncher)
        assertEquals("standard", merged.activities.first().launchMode)
    }

    @Test
    fun `merge returns null only when nothing is runnable`() {
        assertNull(ManifestMerger.merge(null, null, null))
        assertNull(ManifestMerger.merge(null, null, ""))
        assertNull(ManifestMerger.merge(null, null, "   "))
    }

    @Test
    fun `xml entries override archive entries with the same class`() {
        val xml = ManifestParser.ManifestData(
            applicationClassName = null, labelRes = 0, iconRes = 0, applicationThemeRes = 0,
            activities = listOf(activity("com.app.MainActivity", launcher = true))
        )
        val archive = ManifestMerger.ArchiveMeta(
            applicationClass = null, labelRes = 0, iconRes = 0, applicationThemeRes = 0,
            activities = listOf(
                ManifestMerger.ArchiveActivity("com.app.MainActivity", themeRes = 5, launchMode = "standard")
            )
        )
        val merged = ManifestMerger.merge(xml, archive, null)

        assertNotNull(merged)
        assertEquals(1, merged!!.activities.size)
        val main = merged.activities.first()
        assertTrue(main.isLauncher)
        assertEquals("com.app.MainActivity", merged.launcherActivity?.className)
        assertFalse(main.themeRes == 5)
    }

    @Test
    fun `first declared activity is the final fallback`() {
        val xml = ManifestParser.ManifestData(
            applicationClassName = null, labelRes = 0, iconRes = 0, applicationThemeRes = 0,
            activities = listOf(
                activity("com.app.A"),
                activity("com.app.B")
            )
        )
        val merged = ManifestMerger.merge(xml, null, null)

        assertNotNull(merged)
        assertEquals("com.app.A", merged!!.firstActivity?.className)
    }
}
