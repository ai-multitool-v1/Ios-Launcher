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
 * Builds a [Resources] object backed by a guest APK so guest resource ids
 * resolve correctly inside the host process.
 *
 * Uses the classic asset-path injection technique: a private AssetManager is
 * created reflectively and the guest APK path is added to it. Works on all
 * supported API levels once hidden API exemptions are applied.
 */
object GuestResourcesLoader {

    private const val TAG = "GuestResources"

    /** Creates an AssetManager with the guest APK's assets/resources attached. */
    fun createAssetManager(apkPath: String): AssetManager? {
        return try {
            val amClass = AssetManager::class.java
            val assetManager: AssetManager = try {
                // Hidden public constructor — reachable with exemptions applied.
                amClass.getDeclaredConstructor().apply { isAccessible = true }
                    .newInstance() as AssetManager
            } catch (t: Throwable) {
                ClonerLog.e(TAG, "AssetManager construction failed", t)
                return null
            }
            val addAssetPath = amClass.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            val cookie = addAssetPath.invoke(assetManager, apkPath) as Int
            if (cookie == 0) {
                ClonerLog.e(TAG, "addAssetPath returned 0 for $apkPath")
                return null
            }
            assetManager
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "asset manager creation failed for $apkPath", t)
            null
        }
    }

    /** Creates guest-scoped [Resources] bound to the host's display metrics. */
    fun createResources(context: Context, apkPath: String): Resources? {
        return createResources(
            context.resources.displayMetrics,
            context.resources.configuration,
            apkPath
        )
    }

    /** Creates guest-scoped [Resources] without a host context (metadata parsing). */
    fun createResources(metrics: DisplayMetrics, configuration: Configuration, apkPath: String): Resources? {
        val assets = createAssetManager(apkPath) ?: return null
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
    fun loadLabel(apkPath: String, appInfo: ApplicationInfo, fallback: String): String {
        val resources = createResources(defaultMetrics(), defaultConfiguration(), apkPath)
            ?: return appInfo.nonLocalizedLabel?.toString() ?: fallback
        return try {
            if (appInfo.labelRes != 0) {
                resources.getString(appInfo.labelRes)
            } else {
                appInfo.nonLocalizedLabel?.toString() ?: fallback
            }
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "label load failed for $apkPath", t)
            appInfo.nonLocalizedLabel?.toString() ?: fallback
        }
    }

    /** Resolves the app icon drawable directly from the guest APK resources. */
    fun loadIconDrawable(apkPath: String, appInfo: ApplicationInfo): Drawable? {
        if (appInfo.icon == 0) return null
        val resources = createResources(defaultMetrics(), defaultConfiguration(), apkPath) ?: return null
        return try {
            resources.getDrawable(appInfo.icon, null)
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "icon load failed for $apkPath", t)
            null
        }
    }
}
