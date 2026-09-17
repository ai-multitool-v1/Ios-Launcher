package org.setbd.cloner.engine.guest

import android.content.res.XmlResourceParser
import org.setbd.cloner.util.ClonerLog

/**
 * Minimal binary-manifest reader for guest APKs.
 *
 * Extracts exactly what the container engine needs: the application class,
 * the label/icon resource ids, and every activity with its theme, launchMode
 * and MAIN/LAUNCHER intent-filter status. Reads the manifest through the
 * guest AssetManager (openXmlResourceParser("AndroidManifest.xml")), which
 * works for any installed-or-copied APK without privileged APIs.
 */
object ManifestParser {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val TAG = "ManifestParser"

    data class GuestActivity(
        val className: String,
        val themeRes: Int,
        val launchMode: String,
        val isLauncher: Boolean
    )

    data class ManifestData(
        val applicationClassName: String?,
        val labelRes: Int,
        val iconRes: Int,
        /** Application-level theme (android:theme on <application>). */
        val applicationThemeRes: Int,
        val activities: List<GuestActivity>
    ) {
        val launcherActivity: GuestActivity? get() = activities.firstOrNull { it.isLauncher }

        /** First declared activity — fallback when no MAIN/LAUNCHER filter exists. */
        val firstActivity: GuestActivity? get() = activities.firstOrNull()
    }

    fun parse(apkPath: String, manifestPackage: String): ManifestData? {
        val assets = GuestResourcesLoader.createAssetManager(apkPath) ?: return null
        return try {
            val parser: XmlResourceParser = assets.openXmlResourceParser("AndroidManifest.xml")
            parser.use { p ->
                readManifest(p, manifestPackage)
            }
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "manifest parse failed for $apkPath", t)
            null
        }
    }

    private fun readManifest(parser: XmlResourceParser, manifestPackage: String): ManifestData {
        var applicationClassName: String? = null
        var labelRes = 0
        var iconRes = 0
        var applicationThemeRes = 0
        val activities = ArrayList<GuestActivity>()

        var currentActivity: String? = null
        var currentTheme = 0
        var currentLaunchMode = "standard"
        var inIntentFilter = false
        var sawMain = false
        var sawLauncher = false

        var event = parser.eventType
        while (event != XmlResourceParser.END_DOCUMENT) {
            when (event) {
                XmlResourceParser.START_TAG -> when (parser.name) {
                    "application" -> {
                        applicationClassName = stringAttr(parser, ANDROID_NAME)?.let { resolveClass(it, manifestPackage) }
                        labelRes = resourceAttr(parser, ANDROID_LABEL)
                        iconRes = resourceAttr(parser, ANDROID_ICON)
                        applicationThemeRes = resourceAttr(parser, ANDROID_THEME)
                    }
                    "activity", "activity-alias" -> {
                        currentActivity = stringAttr(parser, ANDROID_NAME)?.let { resolveClass(it, manifestPackage) }
                        currentTheme = resourceAttr(parser, ANDROID_THEME)
                        currentLaunchMode = stringAttr(parser, ANDROID_LAUNCH_MODE) ?: "standard"
                        sawMain = false
                        sawLauncher = false
                        if (parser.name == "activity-alias") {
                            // Alias points at the real activity class.
                            currentActivity = stringAttr(parser, ANDROID_TARGET_ACTIVITY)
                                ?.let { resolveClass(it, manifestPackage) }
                                ?: currentActivity
                        }
                    }
                    "intent-filter" -> {
                        inIntentFilter = true
                        sawMain = false
                        sawLauncher = false
                    }
                    "action" -> {
                        if (inIntentFilter && stringAttr(parser, ANDROID_NAME) == "android.intent.action.MAIN") {
                            sawMain = true
                        }
                    }
                    "category" -> {
                        if (inIntentFilter && stringAttr(parser, ANDROID_NAME) == "android.intent.category.LAUNCHER") {
                            sawLauncher = true
                        }
                    }
                }
                XmlResourceParser.END_TAG -> when (parser.name) {
                    "intent-filter" -> inIntentFilter = false
                    "activity", "activity-alias" -> {
                        val className = currentActivity
                        if (className != null && !activities.any { it.className == className }) {
                            activities.add(
                                GuestActivity(
                                    className = className,
                                    themeRes = currentTheme,
                                    launchMode = currentLaunchMode,
                                    isLauncher = sawMain && sawLauncher
                                )
                            )
                        }
                        currentActivity = null
                        currentTheme = 0
                        currentLaunchMode = "standard"
                    }
                }
            }
            event = parser.next()
        }

        return ManifestData(applicationClassName, labelRes, iconRes, applicationThemeRes, activities)
    }

    private fun resolveClass(name: String, manifestPackage: String): String =
        if (name.startsWith(".")) manifestPackage + name else name

    private const val ANDROID_NAME = android.R.attr.name
    private const val ANDROID_LABEL = android.R.attr.label
    private const val ANDROID_ICON = android.R.attr.icon
    private const val ANDROID_THEME = android.R.attr.theme
    private const val ANDROID_LAUNCH_MODE = android.R.attr.launchMode
    private const val ANDROID_TARGET_ACTIVITY = android.R.attr.targetActivity

    private fun stringAttr(parser: XmlResourceParser, attr: Int): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResource(i) == attr) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun resourceAttr(parser: XmlResourceParser, attr: Int): Int {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResource(i) == attr) {
                return parser.getAttributeResourceValue(i, 0)
            }
        }
        return 0
    }
}
