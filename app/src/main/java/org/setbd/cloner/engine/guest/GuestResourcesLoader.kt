package org.setbd.cloner.engine.guest

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import org.setbd.cloner.util.ClonerLog

/**
 * Builds a [Resources] object backed by one or more guest APKs so guest
 * resource ids resolve correctly inside the host process.
 *
 * Split-APK (App Bundle) guests pass base + every split here — all paths are
 * added to the SAME AssetManager, exactly like the platform does for
 * installed App Bundle packages. Resources referenced by the base resolve,
 * and so do resources that live inside a split (density/language tables).
 *
 * Uses the classic asset-path injection technique: a private AssetManager is
 * created reflectively and the guest APK paths are added to it. Several
 * creation strategies are attempted because framework internals moved across
 * Android versions; the first one that yields a working AssetManager wins.
 */
object GuestResourcesLoader {

    private const val TAG = "GuestResources"

    /**
     * Creates an AssetManager with every given APK attached (base first,
     * then splits). Returns null only when no strategy worked.
     */
    fun createAssetManager(apkPaths: List<String>): AssetManager? {
        if (apkPaths.isEmpty()) return null
        val assetManager: AssetManager = newAssetManager() ?: return null
        var attached = 0
        for (path in apkPaths) {
            if (addAssetPath(assetManager, path)) attached++
        }
        if (attached == 0) {
            ClonerLog.e(TAG, "no asset path could be attached: $apkPaths")
            return null
        }
        if (attached < apkPaths.size) {
            ClonerLog.w(TAG, "only $attached/${apkPaths.size} asset paths attached")
        }
        return assetManager
    }

    /** Single-APK convenience overload. */
    fun createAssetManager(apkPath: String): AssetManager? =
        createAssetManager(listOf(apkPath))

    /** Strategy: the hidden public AssetManager() constructor. */
    private fun newAssetManager(): AssetManager? {
        return try {
            val amClass = AssetManager::class.java
            amClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as AssetManager
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "hidden AssetManager constructor failed: ${t.message}")
            // Strategy 2: no-arg newInstance (deprecated public path on some
            // framework versions).
            try {
                AssetManager::class.java.newInstance() as AssetManager
            } catch (t2: Throwable) {
                ClonerLog.e(TAG, "AssetManager creation failed entirely", t2)
                null
            }
        }
    }

    private fun addAssetPath(assetManager: AssetManager, path: String): Boolean {
        return try {
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            )
            addAssetPath.isAccessible = true
            val cookie = addAssetPath.invoke(assetManager, path) as Int
            if (cookie == 0) {
                ClonerLog.e(TAG, "addAssetPath returned 0 for $path")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "addAssetPath failed for $path", t)
            false
        }
    }

    /** Creates guest-scoped [Resources] bound to the host's display metrics. */
    fun createResources(context: Context, apkPaths: List<String>): Resources? {
        return createResources(
            context.resources.displayMetrics,
            context.resources.configuration,
            apkPaths
        )
    }

    /** Single-APK convenience overload. */
    fun createResources(context: Context, apkPath: String): Resources? =
        createResources(context, listOf(apkPath))

    /** Creates guest-scoped [Resources] without a host context (metadata parsing). */
    fun createResources(
        metrics: DisplayMetrics,
        configuration: Configuration,
        apkPaths: List<String>
    ): Resources? {
        val assets = createAssetManager(apkPaths) ?: return null
        return try {
            Resources(assets, metrics, configuration)
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "resources creation failed", t)
            null
        }
    }

    fun defaultMetrics(): DisplayMetrics = DisplayMetrics().apply { setToDefaults() }

    fun defaultConfiguration(): Configuration = Configuration().apply { setToDefaults() }

    /** Resolves the app label directly from the guest APK resources. */
    fun loadLabel(apkPaths: List<String>, appInfo: ApplicationInfo, fallback: String): String {
        val resources = createResources(defaultMetrics(), defaultConfiguration(), apkPaths)
            ?: return appInfo.nonLocalizedLabel?.toString() ?: fallback
        return try {
            if (appInfo.labelRes != 0) {
                resources.getString(appInfo.labelRes)
            } else {
                appInfo.nonLocalizedLabel?.toString() ?: fallback
            }
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "label load failed for $apkPaths", t)
            appInfo.nonLocalizedLabel?.toString() ?: fallback
        }
    }

    /** Single-APK convenience overload. */
    fun loadLabel(apkPath: String, appInfo: ApplicationInfo, fallback: String): String =
        loadLabel(listOf(apkPath), appInfo, fallback)

    /** Resolves the app icon drawable directly from the guest APK resources. */
    fun loadIconDrawable(apkPaths: List<String>, appInfo: ApplicationInfo): Drawable? {
        if (appInfo.icon == 0) return null
        val resources = createResources(defaultMetrics(), defaultConfiguration(), apkPaths)
            ?: return null
        return try {
            resources.getDrawable(appInfo.icon, null)
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "icon load failed for $apkPaths", t)
            null
        }
    }

    /** Single-APK convenience overload. */
    fun loadIconDrawable(apkPath: String, appInfo: ApplicationInfo): Drawable? =
        loadIconDrawable(listOf(apkPath), appInfo)
}
