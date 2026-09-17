package org.setbd.cloner.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.engine.guest.GuestResourcesLoader
import org.setbd.cloner.engine.guest.ManifestParser
import org.setbd.cloner.util.BitmapUtils
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Imports applications as clones.
 *
 * Three sources are supported:
 *  - installed applications, INCLUDING App Bundle packages delivered as
 *    base + split APKs (the base APK and every [ApplicationInfo.splitSourceDirs]
 *    entry are copied into the clone's private namespace and loaded together);
 *  - monolithic APK files picked through the storage access framework;
 *  - split-APK bundles (.xapk / .apks / .apkm — ZIP archives containing
 *    base.apk plus split_config_*.apk files), which are unpacked and imported
 *    exactly like installed App Bundle packages.
 *
 * Every import copies the FULL APK set (base + splits) into the clone's
 * private area so the engine owns its copies, parses label/icon/version from
 * the base, and registers the clone with a fresh isolated id.
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
        val icon: android.graphics.drawable.Drawable?,
        /** True when the package ships as base + splits (App Bundle). */
        val isSplitPackage: Boolean = false
    )

    /** A full APK set staged in the importer's cache: base first, then splits. */
    private data class StagedApkSet(val base: File, val splits: List<File>)

    suspend fun importInstalledPackage(packageName: String): ImportResult =
        withContext(Dispatchers.IO) {
            val staging = newStagingDir()
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val sourceApk = appInfo.sourceDir
                    ?: return@withContext ImportResult.Error("base APK path unavailable for $packageName")

                // Base + ALL split APKs (App Bundle packages are the norm for
                // Play Store installs). Splits carry ABI/density/language
                // resources, code and native libraries required to run.
                // The base is copied into OUR staging first — the engine
                // never moves a system-owned file out of /data/app.
                val baseCopy = File(staging, "base.apk")
                File(sourceApk).copyTo(baseCopy, overwrite = true)
                val staged = StagedApkSet(baseCopy, emptyList())
                // Capture the REAL launcher component now — the most robust
                // entry point the engine can fall back to at launch time.
                val launcherClass = runCatching {
                    pm.getLaunchIntentForPackage(packageName)?.component?.className
                }.getOrNull()
                registerFromStaged(
                    staged = staged,
                    packageName = packageName,
                    splitSourceDirs = appInfo.splitSourceDirs?.toList().orEmpty(),
                    launcherClass = launcherClass
                )
            } catch (t: Throwable) {
                ClonerLog.e(TAG, "import of $packageName failed", t)
                ImportResult.Error(t.message ?: "import failed")
            } finally {
                staging.deleteRecursively()
            }
        }

    /**
     * Imports a picked APK file. Accepts:
     *  - plain .apk (monolithic);
     *  - .xapk / .apks / .apkm bundles (ZIP with base.apk + splits);
     *  - any ZIP whose entries are APK files (picked via "application/zip").
     */
    suspend fun importApkUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val staging = newStagingDir()
        try {
            val picked = File(staging, "picked.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                picked.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.Error("cannot open selected file")

            val staged = if (isZipArchive(picked)) {
                unpackBundle(picked, staging)
            } else {
                StagedApkSet(picked, emptyList())
            }
            if (staged == null) {
                return@withContext ImportResult.Error(
                    "no usable APK inside this bundle — pick a .apk, .xapk, .apks or .apkm file"
                )
            }
            registerFromStaged(
                staged = staged,
                packageName = null,
                splitSourceDirs = emptyList(),
                launcherClass = null // resolved inside registerFromStaged
            )
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "APK file import failed", t)
            ImportResult.Error(t.message ?: "import failed")
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun registerFromStaged(
        staged: StagedApkSet,
        packageName: String?,
        splitSourceDirs: List<String>,
        launcherClass: String?
    ): ImportResult {
        val parsed = parseApk(staged.base)
            ?: run {
                staged.base.delete()
                return ImportResult.Error("not a valid Android APK")
            }
        if (packageName != null && parsed.packageName != packageName) {
            ClonerLog.w(TAG, "archive package ${parsed.packageName} differs from $packageName — trusting archive")
        }
        if (repository.cloneCountForPackage(parsed.packageName) >= MAX_CLONES_PER_PACKAGE) {
            staged.base.delete()
            return ImportResult.Error("maximum of $MAX_CLONES_PER_PACKAGE clones for this app")
        }

        // Best-effort launcher for file imports: parse the binary manifest
        // of the staged base directly. Null is fine — the engine merges this
        // with the archive metadata at runtime.
        val resolvedLauncher = launcherClass
            ?: runCatching {
                ManifestParser.parse(staged.base.absolutePath, parsed.packageName)
                    ?.launcherActivity?.className
            }.getOrNull().orEmpty()

        // Stage the full split set next to the base when this is an installed
        // App Bundle package (splits copied straight from /data/app).
        val stagedSplits = staged.splits.ifEmpty {
            splitSourceDirs.map { source -> File(source) }
        }
        val clone = repository.registerClone(
            packageName = parsed.packageName,
            appLabel = parsed.label,
            versionName = parsed.versionName,
            apkFile = staged.base,
            splitFiles = stagedSplits,
            iconPng = parsed.iconPng,
            launcherClass = resolvedLauncher
        )
        ClonerLog.i(
            TAG,
            "imported ${parsed.packageName} as clone=${clone.cloneId} " +
                "(base + ${stagedSplits.size} splits)"
        )
        return ImportResult.Success(clone)
    }

    /**
     * Lists user-visible installed apps offered for import. The host itself
     * and packages without a launch intent are excluded. Split (App Bundle)
     * packages are listed and importable since v1.1.
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
                    icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
                    isSplitPackage = !appInfo.splitSourceDirs.isNullOrEmpty()
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

    // ------------------------------------------------------------------
    // Bundle (.xapk/.apks/.apkm) unpacking
    // ------------------------------------------------------------------

    /**
     * Unpacks a ZIP bundle into [staging]. The bundle's base APK is the entry
     * named "base.apk"; when no such entry exists, the single largest APK
     * entry is treated as base (some .apks packs name splits only). Remaining
     * .apk entries become splits. Non-APK entries (icon.png, manifest.json,
     * yolo.png, …) are ignored.
     */
    private fun unpackBundle(bundle: File, staging: File): StagedApkSet? {
        ZipFile(bundle).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .toList()
            val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (apkEntries.isEmpty()) return null

            val baseEntry = apkEntries.firstOrNull { it.name == "base.apk" }
                ?: apkEntries.maxByOrNull { it.size }
            val base = File(staging, "base.apk")
            zip.getInputStream(baseEntry).use { input ->
                base.outputStream().use { output -> input.copyTo(output) }
            }
            val splits = apkEntries
                .filter { it != baseEntry }
                .map { entry ->
                    val name = File(entry.name).name
                    val target = File(staging, name)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                }
            ClonerLog.i(TAG, "bundle unpacked: base + ${splits.size} splits from ${bundle.name}")
            return StagedApkSet(base, splits)
        }
    }

    private fun isZipArchive(file: File): Boolean {
        val header = runCatching {
            file.inputStream().use { stream ->
                val b = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = stream.read(b, read, 4 - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 4) b else null
            }
        }.getOrNull() ?: return false
        // ZIP local file header "PK\u0003\u0004" (or empty/spanned "PK\u0005\u0006" / "PK\u0007\u0008").
        return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
    }

    // ------------------------------------------------------------------
    // Temp staging helpers
    // ------------------------------------------------------------------

    private fun newStagingDir(): File =
        File(context.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }

    private companion object {
        const val TAG = "ApkImporter"
        const val MAX_CLONES_PER_PACKAGE = 9
    }
}
