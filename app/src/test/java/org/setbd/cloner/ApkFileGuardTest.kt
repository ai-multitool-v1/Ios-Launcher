package org.setbd.cloner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.setbd.cloner.util.ApkFileGuard
import java.io.File

/**
 * Pure-JVM tests for the writable-dex fix: every guest APK must be flipped
 * to read-only before the engine builds a class loader over it, because
 * Android 10+ refuses to execute DEX from writable files.
 *
 * Permission-bit semantics are POSIX — skipped on Windows (not a CI target).
 */
class ApkFileGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun posixFs(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") != true

    @Test
    fun `writable apk becomes read-only`() {
        assumeTrue(posixFs())
        val apk = tmp.newFile("base.apk").apply { writeBytes(ByteArray(16)) }
        assertTrue(apk.canWrite())

        ApkFileGuard.enforceReadOnly(listOf(apk))

        assertFalse("APK must lose its write bits", apk.canWrite())
    }

    @Test
    fun `enforcement is idempotent and heals legacy clones`() {
        assumeTrue(posixFs())
        val base = tmp.newFile("base.apk").apply { writeBytes(ByteArray(8)) }
        val split = tmp.newFile("split_config.en.apk").apply { writeBytes(ByteArray(8)) }
        // Simulate a legacy clone: base already read-only, split writable.
        base.setReadOnly()

        ApkFileGuard.enforceReadOnly(listOf(base, split))

        assertFalse(base.canWrite())
        assertFalse(split.canWrite())
    }

    @Test
    fun `already read-only apk survives re-enforcement`() {
        assumeTrue(posixFs())
        val apk = tmp.newFile("base.apk").apply { writeBytes(ByteArray(8)); setReadOnly() }

        ApkFileGuard.enforceReadOnly(listOf(apk))

        assertFalse(apk.canWrite())
    }

    @Test
    fun `missing paths are skipped without throwing`() {
        ApkFileGuard.enforceReadOnly(
            listOf(File(tmp.root, "does-not-exist.apk"), tmp.newFile("real.apk"))
        )
    }

    @Test
    fun `path-string overload covers mixed paths`() {
        assumeTrue(posixFs())
        val apk = tmp.newFile("base.apk").apply { writeBytes(ByteArray(8)) }

        ApkFileGuard.enforceReadOnlyPaths(listOf(apk.absolutePath, File(tmp.root, "ghost.apk").absolutePath))

        assertFalse(apk.canWrite())
    }

    @Test
    fun `missing files and missing parent dirs never throw`() {
        // Non-file paths are skipped by design (guard is best-effort per file).
        ApkFileGuard.enforceReadOnlyPaths(
            listOf(
                File(tmp.root, "no-such-dir/base.apk").absolutePath,
                File(tmp.root, "ghost.apk").absolutePath
            )
        )
    }
}
