package org.setbd.cloner.engine.guest

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import org.setbd.cloner.util.ClonerLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs the GUEST's declared ContentProviders into the host process,
 * using the platform's own [ActivityThread.installProvider] machinery —
 * exactly what the framework does for a real installation and the same
 * technique the BlackBox engine uses for virtualized guests.
 *
 * Why this matters: modern AndroidX apps initialize critical subsystems
 * (WorkManager, Firebase, emoji2, ProfileInstaller, Lifecycle) through
 * `androidx.startup`'s InitializationProvider. Without the provider step
 * those initializations never run, the guest Application/first activity
 * hits uninitialized singletons and the app self-exits moments after
 * opening — the classic "clone opens then immediately closes" report.
 *
 * Providers are installed BEFORE `Application.onCreate`, in the same order
 * the platform uses. Once installed, `ContentResolver` calls with those
 * authorities resolve LOCALLY in this process (ActivityThread's provider
 * map), no system_server involvement.
 */
object GuestProviderInstaller {

    private const val TAG = "GuestProviders"

    /** authority → owning cloneId. Guards cross-clone authority collisions. */
    private val authorityRegistry = ConcurrentHashMap<String, Long>()

    /** True when [authority] may be installed by [cloneId] (idempotent per owner). */
    fun claimAuthority(authority: String, cloneId: Long): Boolean {
        require(authority.isNotBlank()) { "blank authority" }
        return when (val owner = authorityRegistry.putIfAbsent(authority, cloneId)) {
            null -> true
            cloneId -> true
            else -> {
                ClonerLog.w(TAG, "authority '$authority' already owned by clone=$owner — skipping")
                false
            }
        }
    }

    fun registeredAuthorityCount(): Int = authorityRegistry.size

    /** Test/diagnostic helper. */
    fun releaseAuthoritiesOf(cloneId: Long) {
        authorityRegistry.entries.removeIf { it.value == cloneId }
    }

    /**
     * Installs every applicable provider of the guest. Best-effort per
     * provider: one broken provider never blocks the others. Returns the
     * number of providers successfully installed.
     */
    fun installAll(runtime: GuestRuntime, context: Context): Int {
        val providers = runtime.guestProviders
        if (providers.isEmpty()) {
            ClonerLog.i(TAG, "clone=${runtime.cloneId} declares no content providers")
            return 0
        }
        val mainThread = currentActivityThread() ?: run {
            ClonerLog.e(TAG, "ActivityThread unavailable — guest providers skipped (clone=${runtime.cloneId})")
            return 0
        }
        var installed = 0
        for (info in providers) {
            val name = info.name ?: continue
            if (isAntiDetectProvider(name)) {
                ClonerLog.w(TAG, "skipping anti-virtualization provider $name (clone=${runtime.cloneId})")
                continue
            }
            val authorities = info.authority?.split(';')?.filter { it.isNotBlank() } ?: emptyList()
            if (authorities.isEmpty()) continue
            if (authorities.none { claimAuthority(it, runtime.cloneId) }) continue
            // Keep the ProcessProvider metadata consistent with OUR namespace.
            val scoped = ProviderInfo(info).apply {
                packageName = runtime.packageName
                applicationInfo = runtime.guestApplicationInfo
                processName = runtime.packageName
            }
            val ok = runCatching {
                installProvider(mainThread, context, scoped)
            }.onFailure {
                ClonerLog.w(TAG, "provider $name failed (clone=${runtime.cloneId}): ${it.message}")
            }.isSuccess
            if (ok) {
                installed++
                ClonerLog.i(TAG, "installed provider $name (${info.authority}) clone=${runtime.cloneId}")
            }
        }
        ClonerLog.i(TAG, "clone=${runtime.cloneId} providers installed=$installed/${providers.size}")
        return installed
    }

    /**
     * The platform's private install path. Signature (API 26–35):
     * `void installProvider(Context context, IContentProviderHolder holder,
     *  ProviderInfo info, boolean noisy, boolean noReleaseNeeded, boolean stable)`
     * Called with noisy=false, noReleaseNeeded=true, stable=true — the same
     * arguments the framework uses for providers of the app's own package.
     */
    private fun installProvider(mainThread: Any, context: Context, info: ProviderInfo) {
        val method: Method = findInstallProvider(mainThread) ?: throw IllegalStateException(
            "ActivityThread.installProvider not found on this Android version"
        )
        val token = Binder.clearCallingIdentity()
        try {
            method.invoke(mainThread, context, null, info, false, true, true)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun findInstallProvider(mainThread: Any): Method? {
        var clazz: Class<*>? = mainThread.javaClass
        while (clazz != null && clazz != Any::class.java) {
            if (clazz.name == "android.app.ActivityThread") {
                return clazz.declaredMethods.firstOrNull {
                    it.name == "installProvider" && it.parameterTypes.size == 6
                }?.apply { isAccessible = true }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun currentActivityThread(): Any? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        // android.app.ActivityThread is @hide; the runtime class is what we get.
        threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
            .invoke(null)
    }.getOrNull()

    /**
     * Providers used by known anti-virtualization SDKs would only help the
     * guest detect us — skip them (BlackBox parity).
     */
    fun isAntiDetectProvider(className: String): Boolean {
        val lower = className.lowercase()
        return lower.contains("hades") ||              // Meituan anti-cheat
            lower.contains("ztuni") ||
            lower.contains("securityguard") ||         // Alibaba security
            lower.contains("avdetector") ||
            lower.contains("virtualdetect") ||
            lower.contains("emulatorcheck")
    }
}
