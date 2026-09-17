package org.setbd.cloner.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.engine.guest.GuestResourcesLoader
import org.setbd.cloner.util.BitmapUtils
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports applications as clones.
 *
 * Two sources are supported:
 *  - installed applications (base APK read from /data/app — world-readable by
 *    design, no privileged access needed);
 *  - standalone APK files picked through the storage access framework.
 *
 * Every import copies the base APK into the clone's private namespace so the
 * engine owns its copy, parses label/icon/version from the copy, and
 * registers the clone with a fresh isolated id. Split-APK (App Bundle)
 * packages are rejected with an explicit reason — running them requires
 * re-joining all splits, which the POC does not attempt.
 */
class ApkImporter(
    private val context: Context,
    private val repository: CloneRepository
) {

    sealed class ImportResult {
        data class Success(val clone: CloneInfo) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val versionName: String,
        val icon: android.graphics.drawable.Drawable?
    )

    suspend fun importInstalledPackage(packageName: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val sourceApk = appInfo.sourceDir
                    ?: return@withContext ImportResult.Error("base APK path unavailable for $packageName")
                if (!appInfo.splitSourceDirs.isNullOrEmpty()) {
                    return@withContext ImportResult.Error(
                        "$packageName is delivered as split APKs (App Bundle); " +
                            "only monolithic APKs can be virtualized in this version"
                    )
                }
                val tempCopy = newTempFile()
                File(sourceApk).copyTo(tempCopy, overwrite = true)
                registerFromTemp(tempCopy)
            } catch (t: Throwable) {
                ClonerLog.e(TAG, "import of $packageName failed", t)
                ImportResult.Error(t.message ?: "import failed")
            }
        }

    suspend fun importApkUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val tempCopy = newTempFile()
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempCopy.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.Error("cannot open selected file")
            registerFromTemp(tempCopy)
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "APK file import failed", t)
            ImportResult.Error(t.message ?: "import failed")
        }
    }

    private suspend fun registerFromTemp(tempCopy: File): ImportResult {
        val parsed = parseApk(tempCopy)
            ?: run {
                tempCopy.delete()
                return ImportResult.Error("not a valid Android APK")
            }
        if (repository.cloneCountForPackage(parsed.packageName) >= MAX_CLONES_PER_PACKAGE) {
            tempCopy.delete()
            return ImportResult.Error("maximum of $MAX_CLONES_PER_PACKAGE clones for this app")
        }
        val clone = repository.registerClone(
            packageName = parsed.packageName,
            appLabel = parsed.label,
            versionName = parsed.versionName,
            apkFile = tempCopy,
            iconPng = parsed.iconPng
        )
        ClonerLog.i(TAG, "imported ${parsed.packageName} as clone=${clone.cloneId}")
        return ImportResult.Success(clone)
    }

    /**
     * Lists user-visible installed apps offered for import. The host itself
     * and packages without a launch intent are excluded.
     */
    fun listInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val launchables = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val result = ArrayList<InstalledApp>()
        for (info in launchables) {
            val pkg = info.packageName
            if (pkg == context.packageName) continue
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val appInfo = info.applicationInfo ?: continue
            result.add(
                InstalledApp(
                    packageName = pkg,
                    label = appInfo.loadLabel(pm).toString(),
                    versionName = info.versionName ?: "",
                    icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                )
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }

    private fun parseApk(apkFile: File): ParsedApk? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        ) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath
        val label = GuestResourcesLoader.loadLabel(
            apkFile.absolutePath,
            appInfo,
            info.packageName
        )
        val icon = GuestResourcesLoader.loadIconDrawable(apkFile.absolutePath, appInfo)
        return ParsedApk(
            packageName = info.packageName,
            versionName = info.versionName ?: "",
            label = label,
            iconPng = icon?.let { BitmapUtils.drawableToPngBytes(it) }
        )
    }

    private data class ParsedApk(
        val packageName: String,
        val versionName: String,
        val label: String,
        val iconPng: ByteArray?
    )

    private fun newTempFile(): File =
        File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")

    private companion object {
        const val TAG = "ApkImporter"
        const val MAX_CLONES_PER_PACKAGE = 9
    }
}
