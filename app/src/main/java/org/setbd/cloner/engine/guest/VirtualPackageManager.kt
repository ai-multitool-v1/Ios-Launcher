package org.setbd.cloner.engine.guest

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.UserHandle
import org.setbd.cloner.util.ClonerLog

/**
 * PackageManager served to guest code.
 *
 * For lookups of the guest's own package this class answers from the guest
 * APK (package name, components, signatures, version, label, icon), so a
 * virtualized app still believes it is `com.example.app` even though it
 * physically lives in `org.setbd.cloner`. Everything else is delegated to
 * the host PackageManager unchanged — deliberately: the engine never lies
 * about OTHER apps, never spoofs the system, and never bypasses permission
 * checks. Permission checks resolve against the HOST process grants because
 * the guest physically runs inside the host sandbox.
 *
 * The override set mirrors the abstract members of SDK 35's PackageManager
 * exactly (verified against android.jar) plus the guest-critical concrete
 * members.
 */
class VirtualPackageManager(
    private val runtime: GuestRuntime,
    private val host: PackageManager,
    private val hostPackageName: String
) : PackageManager() {

    private val guestPackage: String get() = runtime.packageName

    // ------------------------------------------------------------------
    // Guest-space lookups
    // ------------------------------------------------------------------

    @Throws(NameNotFoundException::class)
    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName == guestPackage) return runtime.packageInfo
        return host.getPackageInfo(packageName, flags)
    }

    @Throws(NameNotFoundException::class)
    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo =
        host.getPackageInfo(versionedPackage, flags)

    override fun currentToCanonicalPackageNames(names: Array<out String>): Array<String> =
        host.currentToCanonicalPackageNames(names)

    override fun canonicalToCurrentPackageNames(names: Array<out String>): Array<String> =
        host.canonicalToCurrentPackageNames(names)

    override fun getLaunchIntentForPackage(packageName: String): Intent? {
        if (packageName == guestPackage) return runtime.guestLaunchIntent()
        return host.getLaunchIntentForPackage(packageName)
    }

    override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? =
        host.getLeanbackLaunchIntentForPackage(packageName)

    @Throws(NameNotFoundException::class)
    override fun getPackageGids(packageName: String): IntArray = host.getPackageGids(packageName)

    @Throws(NameNotFoundException::class)
    override fun getPackageGids(packageName: String, flags: Int): IntArray =
        host.getPackageGids(packageName, flags)

    @Throws(NameNotFoundException::class)
    override fun getPackageUid(packageName: String, flags: Int): Int =
        host.getPackageUid(packageName, flags)

    @Throws(NameNotFoundException::class)
    override fun getPermissionInfo(name: String, flags: Int): PermissionInfo =
        host.getPermissionInfo(name, flags)

    @Throws(NameNotFoundException::class)
    override fun queryPermissionsByGroup(groupName: String?, flags: Int): List<PermissionInfo> =
        host.queryPermissionsByGroup(groupName, flags)

    @Throws(NameNotFoundException::class)
    override fun getPermissionGroupInfo(name: String, flags: Int): PermissionGroupInfo =
        host.getPermissionGroupInfo(name, flags)

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> =
        host.getAllPermissionGroups(flags)

    @Throws(NameNotFoundException::class)
    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo {
        if (packageName == guestPackage) return runtime.guestApplicationInfo
        return host.getApplicationInfo(packageName, flags)
    }

    @Throws(NameNotFoundException::class)
    override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo {
        if (component.packageName == guestPackage) {
            runtime.activityInfo(component.className)?.let { return it }
            throw NameNotFoundException(component.toString())
        }
        return host.getActivityInfo(component, flags)
    }

    @Throws(NameNotFoundException::class)
    override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo {
        if (component.packageName == guestPackage) {
            // Receivers are not virtualized in this POC.
            throw NameNotFoundException("receiver $component not virtualized")
        }
        return host.getReceiverInfo(component, flags)
    }

    @Throws(NameNotFoundException::class)
    override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo {
        if (component.packageName == guestPackage) {
            // Services are not virtualized in this POC.
            throw NameNotFoundException("service $component not virtualized")
        }
        return host.getServiceInfo(component, flags)
    }

    @Throws(NameNotFoundException::class)
    override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo {
        if (component.packageName == guestPackage) {
            // Providers are not virtualized in this POC.
            throw NameNotFoundException("provider $component not virtualized")
        }
        return host.getProviderInfo(component, flags)
    }

    override fun getInstalledPackages(flags: Int): List<PackageInfo> {
        val result = host.getInstalledPackages(flags).toMutableList()
        if (result.none { it.packageName == guestPackage }) {
            runCatching { runtime.packageInfo }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    override fun getPackagesHoldingPermissions(permissions: Array<out String>, flags: Int): List<PackageInfo> =
        host.getPackagesHoldingPermissions(permissions, flags)

    override fun checkPermission(permName: String, packageName: String): Int =
        host.checkPermission(
            permName,
            if (packageName == guestPackage) hostPackageName else packageName
        )

    override fun isPermissionRevokedByPolicy(permName: String, packageName: String): Boolean =
        host.isPermissionRevokedByPolicy(
            permName,
            if (packageName == guestPackage) hostPackageName else packageName
        )

    override fun addPermission(info: PermissionInfo): Boolean = host.addPermission(info)

    override fun addPermissionAsync(info: PermissionInfo): Boolean = host.addPermissionAsync(info)

    override fun removePermission(name: String) = host.removePermission(name)

    override fun checkSignatures(pkg1: String, pkg2: String): Int {
        if (pkg1 == guestPackage && pkg2 == guestPackage) return SIGNATURE_MATCH
        return host.checkSignatures(pkg1, pkg2)
    }

    override fun checkSignatures(uid1: Int, uid2: Int): Int = host.checkSignatures(uid1, uid2)

    override fun getPackagesForUid(uid: Int): Array<String>? = host.getPackagesForUid(uid)

    override fun getNameForUid(uid: Int): String? = host.getNameForUid(uid)

    override fun getInstalledApplications(flags: Int): List<ApplicationInfo> {
        val result = host.getInstalledApplications(flags).toMutableList()
        if (result.none { it.packageName == guestPackage }) {
            result.add(runtime.guestApplicationInfo)
        }
        return result
    }

    override fun isInstantApp(): Boolean = host.isInstantApp

    override fun isInstantApp(packageName: String): Boolean {
        if (packageName == guestPackage) return false
        return host.isInstantApp(packageName)
    }

    override fun getInstantAppCookieMaxBytes(): Int = host.instantAppCookieMaxBytes

    override fun getInstantAppCookie(): ByteArray = host.instantAppCookie

    override fun clearInstantAppCookie() = host.clearInstantAppCookie()

    override fun updateInstantAppCookie(cookie: ByteArray?) = host.updateInstantAppCookie(cookie)

    override fun getSystemSharedLibraryNames(): Array<String>? = host.systemSharedLibraryNames

    override fun getSharedLibraries(flags: Int): List<SharedLibraryInfo> = host.getSharedLibraries(flags)

    override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? =
        host.getChangedPackages(sequenceNumber)

    override fun getSystemAvailableFeatures(): Array<FeatureInfo> = host.getSystemAvailableFeatures()

    override fun hasSystemFeature(featureName: String): Boolean = host.hasSystemFeature(featureName)

    override fun hasSystemFeature(featureName: String, version: Int): Boolean =
        host.hasSystemFeature(featureName, version)

    override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? {
        runtime.resolveGuestActivity(intent)?.let { return it }
        return host.resolveActivity(intent, flags)
    }

    override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
        runtime.resolveGuestActivity(intent)?.let { return arrayListOf(it) }
        return host.queryIntentActivities(intent, flags)
    }

    override fun queryIntentActivityOptions(
        caller: ComponentName?,
        specifics: Array<out Intent>?,
        intent: Intent,
        flags: Int
    ): List<ResolveInfo> = host.queryIntentActivityOptions(caller, specifics, intent, flags)

    override fun queryBroadcastReceivers(intent: Intent, flags: Int): List<ResolveInfo> =
        host.queryBroadcastReceivers(intent, flags)

    override fun resolveService(intent: Intent, flags: Int): ResolveInfo? =
        host.resolveService(intent, flags)

    override fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo> =
        host.queryIntentServices(intent, flags)

    override fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo> =
        host.queryIntentContentProviders(intent, flags)

    override fun resolveContentProvider(name: String, flags: Int): ProviderInfo? =
        host.resolveContentProvider(name, flags)

    override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> =
        host.queryContentProviders(processName, uid, flags)

    @Throws(NameNotFoundException::class)
    override fun getInstrumentationInfo(component: ComponentName, flags: Int): InstrumentationInfo =
        host.getInstrumentationInfo(component, flags)

    override fun queryInstrumentation(targetPackage: String, flags: Int): List<InstrumentationInfo> =
        host.queryInstrumentation(targetPackage, flags)

    override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable {
        if (packageName == guestPackage) {
            return runtime.guestResources?.getDrawable(resid, null)
                ?: throw NameNotFoundException("resource $resid in $packageName")
        }
        return host.getDrawable(packageName, resid, appInfo)
            ?: throw NameNotFoundException("resource $resid in $packageName")
    }

    @Throws(NameNotFoundException::class)
    override fun getActivityIcon(component: ComponentName): Drawable {
        if (component.packageName == guestPackage) {
            runtime.icon(component.className)?.let { return it }
            throw NameNotFoundException(component.toString())
        }
        return host.getActivityIcon(component)
    }

    @Throws(NameNotFoundException::class)
    override fun getActivityIcon(intent: Intent): Drawable {
        val component = intent.component
        if (component != null && component.packageName == guestPackage) {
            runtime.icon(component.className)?.let { return it }
        }
        return host.getActivityIcon(intent)
    }

    @Throws(NameNotFoundException::class)
    override fun getActivityBanner(component: ComponentName): Drawable =
        host.getActivityBanner(component) ?: throw NameNotFoundException(component.toString())

    @Throws(NameNotFoundException::class)
    override fun getActivityBanner(intent: Intent): Drawable =
        host.getActivityBanner(intent) ?: throw NameNotFoundException(intent.toString())

    override fun getDefaultActivityIcon(): Drawable = host.getDefaultActivityIcon()

    override fun getApplicationIcon(info: ApplicationInfo): Drawable {
        if (info.packageName == guestPackage) {
            runtime.applicationIcon?.let { return it }
            throw NameNotFoundException(info.packageName)
        }
        return host.getApplicationIcon(info)
    }

    @Throws(NameNotFoundException::class)
    override fun getApplicationIcon(packageName: String): Drawable {
        if (packageName == guestPackage) {
            runtime.applicationIcon?.let { return it }
            throw NameNotFoundException(packageName)
        }
        return host.getApplicationIcon(packageName)
    }

    override fun getApplicationBanner(info: ApplicationInfo): Drawable =
        host.getApplicationBanner(info) ?: throw NameNotFoundException(info.packageName)

    @Throws(NameNotFoundException::class)
    override fun getApplicationBanner(packageName: String): Drawable =
        host.getApplicationBanner(packageName) ?: throw NameNotFoundException(packageName)

    @Throws(NameNotFoundException::class)
    override fun getActivityLogo(component: ComponentName): Drawable {
        if (component.packageName == guestPackage) {
            runtime.icon(component.className)?.let { return it }
            throw NameNotFoundException(component.toString())
        }
        return host.getActivityLogo(component) ?: throw NameNotFoundException(component.toString())
    }

    @Throws(NameNotFoundException::class)
    override fun getActivityLogo(intent: Intent): Drawable =
        host.getActivityLogo(intent) ?: throw NameNotFoundException(intent.toString())

    override fun getApplicationLogo(info: ApplicationInfo): Drawable =
        host.getApplicationLogo(info) ?: throw NameNotFoundException(info.packageName)

    @Throws(NameNotFoundException::class)
    override fun getApplicationLogo(packageName: String): Drawable =
        host.getApplicationLogo(packageName) ?: throw NameNotFoundException(packageName)

    override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable =
        host.getUserBadgedIcon(drawable, user)

    override fun getUserBadgedDrawableForDensity(
        drawable: Drawable,
        user: UserHandle,
        fillingRect: Rect?,
        density: Int
    ): Drawable = host.getUserBadgedDrawableForDensity(drawable, user, fillingRect, density)

    override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence =
        host.getUserBadgedLabel(label, user)

    override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence {
        if (packageName == guestPackage) {
            return runtime.guestResources?.getText(resid)
                ?: throw NameNotFoundException("resource $resid in $packageName")
        }
        return host.getText(packageName, resid, appInfo)
            ?: throw NameNotFoundException("resource $resid in $packageName")
    }

    override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser {
        if (packageName == guestPackage) {
            return runtime.guestResources?.getXml(resid)
                ?: throw NameNotFoundException("resource $resid in $packageName")
        }
        return host.getXml(packageName, resid, appInfo)
            ?: throw NameNotFoundException("resource $resid in $packageName")
    }

    override fun getApplicationLabel(info: ApplicationInfo): CharSequence {
        if (info.packageName == guestPackage) return runtime.appLabel
        return host.getApplicationLabel(info)
    }

    @Throws(NameNotFoundException::class)
    override fun getResourcesForActivity(component: ComponentName): Resources {
        if (component.packageName == guestPackage) {
            return runtime.guestResources ?: throw NameNotFoundException(component.toString())
        }
        return host.getResourcesForActivity(component)
    }

    @Throws(NameNotFoundException::class)
    override fun getResourcesForApplication(appPackageName: String): Resources {
        if (appPackageName == guestPackage) {
            return runtime.guestResources ?: throw NameNotFoundException(appPackageName)
        }
        return host.getResourcesForApplication(appPackageName)
    }

    @Throws(NameNotFoundException::class)
    override fun getResourcesForApplication(app: ApplicationInfo): Resources {
        if (app.packageName == guestPackage) {
            return runtime.guestResources ?: throw NameNotFoundException(app.packageName)
        }
        return host.getResourcesForApplication(app)
    }

    override fun verifyPendingInstall(id: Int, verificationCode: Int) {
        host.verifyPendingInstall(id, verificationCode)
    }

    override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) {
        host.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay)
    }

    override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) {
        host.setInstallerPackageName(targetPackage, installerPackageName)
    }

    @Deprecated("Deprecated in Java")
    override fun getInstallerPackageName(packageName: String): String? =
        host.getInstallerPackageName(packageName)

    @Deprecated("Deprecated in Java")
    override fun addPackageToPreferred(packageName: String) =
        host.addPackageToPreferred(packageName)

    @Deprecated("Deprecated in Java")
    override fun removePackageFromPreferred(packageName: String) =
        host.removePackageFromPreferred(packageName)

    @Deprecated("Deprecated in Java")
    override fun getPreferredPackages(flags: Int): List<PackageInfo> =
        host.getPreferredPackages(flags)

    @Deprecated("Deprecated in Java")
    override fun addPreferredActivity(
        filter: IntentFilter,
        match: Int,
        set: Array<out ComponentName>?,
        activity: ComponentName
    ) = host.addPreferredActivity(filter, match, set, activity)

    @Deprecated("Deprecated in Java")
    override fun clearPackagePreferredActivities(packageName: String) =
        host.clearPackagePreferredActivities(packageName)

    @Deprecated("Deprecated in Java")
    override fun getPreferredActivities(
        outFilters: List<IntentFilter>,
        outActivities: List<ComponentName>,
        packageName: String?
    ): Int = host.getPreferredActivities(outFilters, outActivities, packageName)

    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        if (componentName.packageName == guestPackage) {
            runtime.setComponentEnabled(componentName, newState)
            return
        }
        host.setComponentEnabledSetting(componentName, newState, flags)
    }

    override fun getComponentEnabledSetting(componentName: ComponentName): Int {
        if (componentName.packageName == guestPackage) {
            return runtime.componentEnabledSetting(componentName)
        }
        return host.getComponentEnabledSetting(componentName)
    }

    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {
        if (packageName == guestPackage) {
            ClonerLog.w(TAG, "setApplicationEnabledSetting on guest ignored")
            return
        }
        host.setApplicationEnabledSetting(packageName, newState, flags)
    }

    override fun getApplicationEnabledSetting(packageName: String): Int {
        if (packageName == guestPackage) return COMPONENT_ENABLED_STATE_ENABLED
        return host.getApplicationEnabledSetting(packageName)
    }

    override fun isSafeMode(): Boolean = host.isSafeMode

    override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) {
        host.setApplicationCategoryHint(packageName, categoryHint)
    }

    override fun getPackageInstaller(): PackageInstaller = host.getPackageInstaller()

    override fun canRequestPackageInstalls(): Boolean = host.canRequestPackageInstalls()

    companion object {
        private const val TAG = "VirtualPM"
    }
}
