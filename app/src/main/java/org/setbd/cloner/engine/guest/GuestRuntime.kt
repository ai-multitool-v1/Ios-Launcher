package org.setbd.cloner.engine.guest

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import org.setbd.cloner.core.VirtualStorageManager
import org.setbd.cloner.engine.hook.GuestInflaterFactory
import org.setbd.cloner.engine.stub.ExtraKeys
import org.setbd.cloner.engine.stub.StubActivity
import org.setbd.cloner.engine.stub.StubSingleInstanceActivity
import org.setbd.cloner.engine.stub.StubSingleTaskActivity
import org.setbd.cloner.engine.stub.StubTransparentActivity
import org.setbd.cloner.util.ClonerLog
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when a guest APK cannot be loaded into the container. */
class GuestLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runtime state of ONE clone inside the host process.
 *
 * Holds the guest class loader, resources, parsed manifest, fake application
 * info, the component→stub mapping and the in-flight activity counter. Two
 * clones of the same app each own a separate [GuestRuntime], therefore
 * separate class loaders — their static state never collides.
 *
 * Split-APK guests (App Bundle packages) are first-class: base + every split
 * are joined into ONE dex path, ONE asset path set and ONE native-library
 * search path — the same way the platform loads installed App Bundle apps.
 */
class GuestRuntime(
    val cloneId: Long,
    val packageName: String,
    val apkPath: String,
    /** Additional split APK copies stored next to [apkPath] (may be empty). */
    val splitApkPaths: List<String> = emptyList(),
    /** Launcher activity captured at import time (last-resort entry point). */
    val storedLauncherClass: String? = null,
    val storageRoot: File,
    val externalRoot: File,
    private val hostContext: android.content.Context,
    private val storage: VirtualStorageManager
) {

    // -- Guest locations --------------------------------------------------

    val filesDir: File = storage.guestFilesDir(storageRoot).apply { mkdirs() }
    val cacheDir: File = storage.guestCacheDir(storageRoot).apply { mkdirs() }
    val databasesDir: File = storage.guestDatabasesDir(storageRoot).apply { mkdirs() }
    val odexDir: File = storage.guestOdexDir(storageRoot).apply { mkdirs() }

    // -- Parsed guest metadata --------------------------------------------

    /** May be null when the framework archive parser could not read the
     *  APK — the engine then relies on the XML manifest and/or the stored
     *  launcher captured at import time. */
    val packageInfo: PackageInfo?
    val guestApplicationInfo: android.content.pm.ApplicationInfo
    /** Merged from the XML manifest, archive metadata and stored launcher. */
    val manifest: ManifestMerger.MergedManifest
    val launcherActivityClassName: String?

    // -- Execution environment ---------------------------------------------

    val classLoader: GuestClassLoader
    val guestResources: Resources?
    val applicationIcon: Drawable?

    /** Every APK of this guest: base first, then splits (never empty). */
    val allApkPaths: List<String> =
        buildList {
            add(apkPath)
            addAll(splitApkPaths.filter { it.isNotBlank() && it != apkPath })
        }

    /** Best-effort guest label (resource label resolved against guest APK). */
    val appLabel: String by lazy {
        GuestResourcesLoader.loadLabel(allApkPaths, guestApplicationInfo, packageName)
    }

    var guestApplication: Application? = null
        private set

    val virtualPackageManager: VirtualPackageManager by lazy {
        VirtualPackageManager(this, hostContext.packageManager, hostContext.packageName)
    }

    // -- Component mapping and counters ------------------------------------

    private val componentToStub = ConcurrentHashMap<String, String>()
    private val enabledOverrides = ConcurrentHashMap<String, Int>()
    val activityCount = AtomicInteger(0)
    val unsupportedUses = Collections.synchronizedSet(LinkedHashSet<String>())

    init {
        val pm = hostContext.packageManager
        // 1. Framework archive metadata — INDEPENDENT source #1. The
        //    framework's own parser is unaffected by hidden-API enforcement
        //    and gives us the application class and every declared activity.
        packageInfo = runCatching {
            pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or
                    PackageManager.GET_ACTIVITIES
            )
        }.getOrNull()
        if (packageInfo == null) {
            ClonerLog.w(TAG, "archive metadata unavailable for $apkPath — degraded sources")
        }
        val archiveMeta = packageInfo?.toArchiveMeta()

        // 2. Binary XML manifest — source #2. Best effort: failure here no
        //    longer kills the launch (the archive + stored launcher cover it).
        val xmlManifest = runCatching {
            ManifestParser.parse(apkPath, extractManifestPackage())
        }.getOrNull()
        if (xmlManifest == null) {
            ClonerLog.w(TAG, "xml manifest parse failed for $apkPath — merging without it")
        }

        // 3. MERGE the sources. Throws only when NOTHING is runnable.
        manifest = ManifestMerger.merge(xmlManifest, archiveMeta, storedLauncherClass)
            ?: throw GuestLoadException(
                "cannot resolve any launchable entry for $packageName — re-import the app"
            )

        val appInfo = packageInfo?.applicationInfo
            ?: android.content.pm.ApplicationInfo().apply {
                packageName = extractManifestPackage()
            }
        // Point every path the guest can observe into its own namespace.
        appInfo.packageName = packageName
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.splitSourceDirs = allApkPaths.drop(1).toTypedArray()
        appInfo.splitPublicSourceDirs = allApkPaths.drop(1).toTypedArray()
        appInfo.dataDir = storageRoot.absolutePath
        appInfo.deviceProtectedDataDir = storageRoot.absolutePath
        // Restore label/icon resource ids when only the XML saw them.
        if (appInfo.labelRes == 0 && manifest.labelRes != 0) appInfo.labelRes = manifest.labelRes
        if (appInfo.icon == 0 && manifest.iconRes != 0) appInfo.icon = manifest.iconRes
        if (appInfo.nativeLibraryDir.isNullOrBlank()) {
            // PathClassLoader locates native libs INSIDE the base/split APKs
            // (lib/<abi>/*.so entries) automatically on modern Android.
            appInfo.nativeLibraryDir = odexDir.absolutePath
        }
        guestApplicationInfo = appInfo

        // 4. Launch entry — resolved by the merger: XML MAIN/LAUNCHER filter,
        //    then the import-time stored launcher, then first declared.
        launcherActivityClassName = (
            manifest.launcherActivity
                ?: manifest.firstActivity
            )?.className

        // 5. Class loading: base + splits on ONE dex path. Zip-embedded
        //    native libraries are found through the same paths.
        val dexPath = allApkPaths.joinToString(File.pathSeparator)
        val nativeLibPath = appInfo.nativeLibraryDir.takeIf { it.isNotBlank() } ?: ""
        classLoader = GuestClassLoader(dexPath, nativeLibPath, hostContext.classLoader)
        guestResources = GuestResourcesLoader.createResources(hostContext, allApkPaths)
        applicationIcon = GuestResourcesLoader.loadIconDrawable(allApkPaths, appInfo)

        ClonerLog.i(
            TAG,
            "runtime ready clone=$cloneId pkg=$packageName launcher=$launcherActivityClassName " +
                "activities=${manifest.activities.size} apks=${allApkPaths.size} " +
                "sources=(xml=${xmlManifest != null},archive=${archiveMeta != null},stored=$storedLauncherClass)"
        )
    }

    /** Projects the framework archive parse into merger input. */
    private fun PackageInfo.toArchiveMeta(): ManifestMerger.ArchiveMeta {
        val info = this
        val appInfo = info.applicationInfo
        return ManifestMerger.ArchiveMeta(
            applicationClass = appInfo?.className,
            labelRes = appInfo?.labelRes ?: 0,
            iconRes = appInfo?.icon ?: 0,
            applicationThemeRes = appInfo?.theme ?: 0,
            activities = info.activities.orEmpty().map { a ->
                ManifestMerger.ArchiveActivity(
                    className = a.name,
                    themeRes = a.theme,
                    launchMode = when (a.launchMode) {
                        ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
                        ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
                        ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
                        else -> "standard"
                    }
                )
            }
        )
    }

    private fun extractManifestPackage(): String =
        packageInfo?.packageName ?: packageName

    /**
     * Guest-bound LayoutInflater served through VirtualContext for
     * LAYOUT_INFLATER_SERVICE. Built directly on the given (virtual)
     * context — never via LayoutInflater.from, which would recurse back
     * into this service lookup.
     */
    fun guestLayoutInflater(context: Context): LayoutInflater =
        GuestInflaterFactory.create(guestContext = context)

    // ------------------------------------------------------------------
    // Component mapping
    // ------------------------------------------------------------------

    /** Stub activity class that must host the given guest activity. */
    fun assignStubFor(guestClassName: String): String {
        return componentToStub.getOrPut(guestClassName) {
            val activity = manifest.activities.firstOrNull { it.className == guestClassName }
            val launchMode = activity?.launchMode ?: "standard"
            when (launchMode) {
                "singleTask" -> StubSingleTaskActivity::class.java.name
                "singleInstance" -> StubSingleInstanceActivity::class.java.name
                else -> StubActivity::class.java.name
            }
        }
    }

    fun activityInfo(className: String): ActivityInfo? {
        val fromArchive = packageInfo?.activities?.firstOrNull { it.name == className }
        val manifestActivity = manifest.activities.firstOrNull { it.className == className }
        if (fromArchive == null && manifestActivity == null) return null
        return ActivityInfo().apply {
            name = className
            packageName = this@GuestRuntime.packageName
            applicationInfo = guestApplicationInfo
            // Guest activity theme, falling back to the guest's
            // application-level theme so AppCompat guests never run themeless.
            theme = fromArchive?.theme
                ?: manifestActivity?.themeRes?.takeIf { it != 0 }
                ?: manifest.applicationThemeRes
            launchMode = when (manifestActivity?.launchMode) {
                "singleTask" -> android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
                "singleInstance" -> android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
                "singleTop" -> android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP
                else -> android.content.pm.ActivityInfo.LAUNCH_MULTIPLE
            }
            exported = true
        }
    }

    fun icon(className: String): Drawable? {
        return applicationIcon
    }

    fun guestLaunchIntent(): Intent {
        val className = launcherActivityClassName
            ?: throw GuestLoadException(
                "guest $packageName declares no launchable activity"
            )
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, className)
    }

    /** Serves resolveActivity/queryIntentActivities for the guest's own space. */
    fun resolveGuestActivity(intent: Intent): ResolveInfo? {
        val componentClass = if (intent.component != null && intent.component!!.packageName == packageName) {
            intent.component!!.className
        } else if (intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        ) {
            launcherActivityClassName
        } else {
            null
        } ?: return null
        val info = activityInfo(componentClass) ?: return null
        return ResolveInfo().apply {
            activityInfo = info
            resolvePackageName = packageName
            priority = 0
            preferredOrder = 0
            match = 0
            isDefault = true
        }
    }

    fun setComponentEnabled(component: ComponentName, newState: Int) {
        enabledOverrides[component.className] = newState
    }

    fun componentEnabledSetting(component: ComponentName): Int =
        enabledOverrides[component.className] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    fun recordUnsupportedUse(operation: String) {
        unsupportedUses.add(operation)
    }

    // ------------------------------------------------------------------
    // Guest Application lifecycle
    // ------------------------------------------------------------------

    /**
     * Instantiates and initializes the guest Application inside the container.
     * Runs ONCE per runtime. Failures degrade the clone (activities still
     * launch) and are reported, never silently swallowed.
     */
    fun createGuestApplication(): Application? {
        if (guestApplication != null) return guestApplication
        val appClassName = manifest.applicationClassName ?: return null
        return try {
            val instrumentation = android.app.Instrumentation()
            val context = VirtualContext(hostContext.applicationContext, this)
            val app = instrumentation.newApplication(classLoader, appClassName, context)
            instrumentation.callApplicationOnCreate(app)
            guestApplication = app
            ClonerLog.i(TAG, "guest application initialized: $appClassName (clone=$cloneId)")
            app
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "guest application init failed (clone=$cloneId) — running degraded", t)
            null
        }
    }

    // ------------------------------------------------------------------
    // Launch plumbing used by the instrumentation hook
    // ------------------------------------------------------------------

    /**
     * Wraps a guest intent into a stub-targeted intent the system can resolve.
     */
    fun buildStubIntent(guestIntent: Intent): Intent {
        val guestClassName = guestIntent.component?.className
            ?: throw GuestLoadException("guest intent has no explicit component")
        val stubClass = assignStubFor(guestClassName)
        val wrapper = Intent(hostContext, resolveStubClass(stubClass))
        wrapper.putExtra(ExtraKeys.GUEST_INTENT, guestIntent)
        wrapper.putExtra(ExtraKeys.GUEST_CLASS, guestClassName)
        wrapper.putExtra(ExtraKeys.CLONE_ID, cloneId)
        wrapper.flags = guestIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        return wrapper
    }

    private fun resolveStubClass(stubClassName: String): Class<*> =
        (GuestRuntime::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
            .loadClass(stubClassName)

    /** Reads a stub wrapper intent back into (guest class, guest intent, clone id). */
    @Suppress("DEPRECATION")
    fun unwrapLaunch(wrapperIntent: Intent): Triple<String, Intent, Long>? {
        val guestIntent = wrapperIntent.getParcelableExtra<Intent>(ExtraKeys.GUEST_INTENT)
            ?: return null
        val guestClass = wrapperIntent.getStringExtra(ExtraKeys.GUEST_CLASS)
            ?: guestIntent.component?.className
            ?: return null
        val clone = wrapperIntent.getLongExtra(ExtraKeys.CLONE_ID, cloneId)
        return Triple(guestClass, guestIntent, clone)
    }

    companion object {
        private const val TAG = "GuestRuntime"
    }
}
