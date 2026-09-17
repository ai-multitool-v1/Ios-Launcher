package org.setbd.cloner.engine.guest

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import org.setbd.cloner.core.VirtualStorageManager
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
 */
class GuestRuntime(
    val cloneId: Long,
    val packageName: String,
    val apkPath: String,
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

    val packageInfo: PackageInfo
    val guestApplicationInfo: android.content.pm.ApplicationInfo
    val manifest: ManifestParser.ManifestData
    val launcherActivityClassName: String?

    // -- Execution environment ---------------------------------------------

    val classLoader: GuestClassLoader
    val guestResources: Resources?
    val applicationIcon: Drawable?

    /** Best-effort guest label (resource label resolved against guest APK). */
    val appLabel: String by lazy {
        GuestResourcesLoader.loadLabel(apkPath, guestApplicationInfo, packageName)
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
        val parsed = pm.getPackageArchiveInfo(
            apkPath,
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        ) ?: throw GuestLoadException("cannot parse guest APK $apkPath")

        manifest = ManifestParser.parse(apkPath, parsed.packageName)
            ?: throw GuestLoadException("cannot parse guest manifest $apkPath")

        packageInfo = parsed

        val appInfo = parsed.applicationInfo ?: android.content.pm.ApplicationInfo().apply {
            packageName = parsed.packageName
        }
        // Point every path the guest can observe into its own namespace.
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.dataDir = storageRoot.absolutePath
        appInfo.deviceProtectedDataDir = storageRoot.absolutePath
        appInfo.nativeLibraryDir = appInfo.nativeLibraryDir ?: ""
        guestApplicationInfo = appInfo

        launcherActivityClassName = manifest.launcherActivity?.className

        val nativeLibPath = appInfo.nativeLibraryDir.takeIf { it.isNotBlank() }
        classLoader = GuestClassLoader(apkPath, nativeLibPath, hostContext.classLoader)
        guestResources = GuestResourcesLoader.createResources(hostContext, apkPath)
        applicationIcon = GuestResourcesLoader.loadIconDrawable(apkPath, appInfo)

        ClonerLog.i(
            TAG,
            "runtime ready clone=$cloneId pkg=$packageName launcher=$launcherActivityClassName " +
                "activities=${manifest.activities.size}"
        )
    }

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
        val fromArchive = packageInfo.activities?.firstOrNull { it.name == className }
        val manifestActivity = manifest.activities.firstOrNull { it.className == className }
        if (fromArchive == null && manifestActivity == null) return null
        return ActivityInfo().apply {
            name = className
            packageName = this@GuestRuntime.packageName
            applicationInfo = guestApplicationInfo
            theme = fromArchive?.theme ?: manifestActivity?.themeRes ?: 0
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
            ?: throw GuestLoadException("guest $packageName has no launcher activity")
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
