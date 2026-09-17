package org.setbd.cloner.util

import java.io.File

/**
 * Android 10 (API 29) hardened the ART runtime: apps targeting API 29+ are
 * no longer allowed to execute DEX bytecode from a WRITABLE file. Loading a
 * guest APK with [dalvik.system.PathClassLoader] while the file still has
 * write bits fails with:
 *
 *     "Writable dex file '/data/.../apk/base.apk' is not allowed."
 *
 * Every APK the engine copies into a clone namespace is therefore flipped to
 * read-only (mode 0444) BEFORE any class loader is built over it. The check
 * ART performs is on the file's permission bits, not its location — a
 * read-only APK inside the app's private storage loads fine.
 *
 * Enforcement is idempotent, cheap (a chmod per file, skipped when the file
 * is already read-only) and additionally HEALS APK sets written by older app
 * versions, so clones created before this fix recover on the next launch
 * without re-importing the app.
 */
object ApkFileGuard {

    /**
     * Makes every given file read-only. Missing files are skipped silently;
     * directories (never expected here) are skipped as well.
     *
     * @throws IllegalStateException when the OS refuses the chmod — the
     *         subsequent DEX load would fail anyway, so the caller surfaces
     *         this instead of an obscure runtime error.
     */
    fun enforceReadOnly(files: Iterable<File>) {
        for (file in files) {
            if (!file.isFile) continue
            if (file.canWrite() && !file.setReadOnly()) {
                throw IllegalStateException(
                    "cannot lock guest APK read-only: ${file.name} " +
                        "(location: ${file.parentFile?.absolutePath})"
                )
            }
        }
    }

    /** Convenience overload for path strings; non-existent paths are skipped. */
    fun enforceReadOnlyPaths(paths: Iterable<String>) {
        enforceReadOnly(paths.map(::File))
    }
}
