package org.setbd.cloner.core

import android.content.Context
import org.setbd.cloner.util.ClonerLog
import java.io.File

/**
 * Owns the per-clone isolated storage namespace.
 *
 * Layout (under the host's private storage — the only location a normal app
 * may write):
 *
 * <filesDir>/virtual_apps/clone_<n>/
 *     apk/base.apk            copied guest APK used by the engine
 *     icons/original.png      icon captured at import time
 *     icons/custom.png        optional user supplied icon
 *     files/                  guest getFilesDir() target
 *     cache/                  guest getCacheDir() target
 *     shared_prefs/           guest preferences namespace (namespaced mirror)
 *     databases/              guest openOrCreateDatabase() target
 *     code_cache/             guest DexClassLoader odex output
 */
class VirtualStorageManager(context: Context) {

    val baseDir: File = File(context.filesDir, "virtual_apps")

    val nextCloneRoot: File
        get() {
            baseDir.mkdirs()
            var index = 1
            var candidate = File(baseDir, "clone_$index")
            while (candidate.exists()) {
                index++
                candidate = File(baseDir, "clone_$index")
            }
            return candidate
        }

    /** Creates a fresh clone namespace with all guest data subdirectories. */
    fun createCloneDirs(): File {
        val root = nextCloneRoot
        createGuestDirs(root)
        File(root, "apk").mkdirs()
        File(root, "icons").mkdirs()
        File(root, "code_cache").mkdirs()
        ClonerLog.i(TAG, "created clone storage ${root.name}")
        return root
    }

    fun createGuestDirs(root: File) {
        GUEST_DIRS.forEach { File(root, it).mkdirs() }
    }

    fun relocateApk(storagePath: String, tempApk: File): File {
        val target = File(storagePath, "apk/base.apk")
        target.parentFile?.mkdirs()
        if (!tempApk.renameTo(target)) {
            tempApk.copyTo(target, overwrite = true)
            tempApk.delete()
        }
        return target
    }

    /**
     * Moves a freshly imported APK set into the clone's apk directory.
     *
     * SAFETY: the base file comes from OUR staging cache and is renamed;
     * splits may live in /data/app (installed App Bundle packages) and are
     * ALWAYS copied — the engine never touches system-owned files.
     * Returns the stored paths, base first, splits in order.
     */
    fun relocateSplitApks(storagePath: String, base: File, splits: List<File>): List<File> {
        val apkDir = File(storagePath, "apk").apply { mkdirs() }
        val storedBase = relocateApk(storagePath, base)
        val storedSplits = splits.map { split ->
            val target = File(apkDir, split.name)
            split.copyTo(target, overwrite = true)
            target
        }
        return listOf(storedBase) + storedSplits
    }

    fun copyApk(sourceApkPath: String, storagePath: String): File {
        val target = File(storagePath, "apk/base.apk")
        target.parentFile?.mkdirs()
        File(sourceApkPath).copyTo(target, overwrite = true)
        return target
    }

    /** Copies the full APK set (base + splits) of a clone into a new namespace. */
    fun copyApkSet(sourceStoragePath: String, targetStoragePath: String): File {
        val sourceApkDir = File(sourceStoragePath, "apk")
        val targetApkDir = File(targetStoragePath, "apk").apply { mkdirs() }
        sourceApkDir.listFiles()?.forEach { file ->
            file.copyTo(File(targetApkDir, file.name), overwrite = true)
        }
        return File(targetApkDir, "base.apk")
    }

    /** All APK files (base + splits) stored for a clone, base first. */
    fun apkSet(storagePath: String): List<File> {
        val apkDir = File(storagePath, "apk")
        val files = apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: return emptyList()
        return files.sortedWith(
            compareByDescending<File> { it.name == "base.apk" }.thenBy { it.name }
        )
    }

    fun writeOriginalIcon(storagePath: String, png: ByteArray) {
        val target = originalIconFile(storagePath)
        target.parentFile?.mkdirs()
        target.writeBytes(png)
    }

    fun originalIconFile(storagePath: String): File = File(storagePath, "icons/original.png")

    fun customIconFile(storagePath: String): File = File(storagePath, "icons/custom.png")

    fun guestFilesDir(root: File): File = File(root, "files")

    fun guestCacheDir(root: File): File = File(root, "cache")

    fun guestPrefsDir(root: File): File = File(root, "shared_prefs")

    fun guestDatabasesDir(root: File): File = File(root, "databases")

    fun guestOdexDir(root: File): File = File(root, "code_cache")

    /** Removes the whole clone namespace. */
    fun wipeClone(storagePath: String) {
        deleteRecursively(File(storagePath))
        ClonerLog.i(TAG, "wiped ${File(storagePath).name}")
    }

    /** Clears guest app data directories while keeping APK and icons. */
    fun clearGuestData(storagePath: String) {
        val root = File(storagePath)
        GUEST_DIRS.forEach { dir ->
            val d = File(root, dir)
            d.listFiles()?.forEach { deleteRecursively(it) }
        }
        ClonerLog.i(TAG, "cleared guest data of ${root.name}")
    }

    /** Copies guest data directories (files, cache, databases, prefs) between namespaces. */
    fun copyGuestData(sourceRoot: File, targetRoot: File) {
        GUEST_DIRS.forEach { dir ->
            val src = File(sourceRoot, dir)
            if (!src.exists()) return@forEach
            val dst = File(targetRoot, dir)
            dst.mkdirs()
            src.listFiles()?.forEach { it.copyRecursively(File(dst, it.name), overwrite = true) }
        }
    }

    fun usageBytes(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    companion object {
        private const val TAG = "VirtualStorage"

        /** Guest-visible data directories inside every clone namespace. */
        val GUEST_DIRS = arrayOf("files", "cache", "shared_prefs", "databases")
    }
}
