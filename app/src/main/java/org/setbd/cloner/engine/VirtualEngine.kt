package org.setbd.cloner.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.setbd.cloner.core.CloneLifecycleManager
import org.setbd.cloner.core.VirtualStorageManager
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.engine.guest.GuestLoadException
import org.setbd.cloner.engine.guest.GuestRuntime
import org.setbd.cloner.engine.hook.ActivityManagerHook
import org.setbd.cloner.engine.hook.ClonerInstrumentation
import org.setbd.cloner.engine.stub.ExtraKeys
import org.setbd.cloner.util.ClonerLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The container engine.
 *
 * Owns the per-clone [GuestRuntime] instances and the two framework hooks:
 *
 *  1. [ClonerInstrumentation] — swaps stub launches to real guest classes and
 *     attaches the virtual (per-clone, redirected) environment before guest
 *     onCreate runs.
 *  2. [ActivityManagerHook] — rewrites startActivity calls made BY guest code
 *     so in-app navigation stays inside the container.
 *
 * All hooks live in the host process only. Nothing here touches the system
 * server, other apps, or any security mechanism — a guest simply runs inside
 * this process, under this app's permissions, reading/writing only this
 * app's private storage.
 */
class VirtualEngine private constructor() {

    private lateinit var appContext: Context
    private lateinit var storage: VirtualStorageManager
    private lateinit var repository: CloneRepository
    private lateinit var lifecycleManager: CloneLifecycleManager

    private val runtimes = ConcurrentHashMap<Long, GuestRuntime>()

    /** Guest activities currently alive, keyed by identity hash. */
    private val guestActivities = ConcurrentHashMap<Int, Activity>()

    fun initialize(
        context: Context,
        storage: VirtualStorageManager,
        repository: CloneRepository,
        lifecycleManager: CloneLifecycleManager
    ) {
        if (this::appContext.isInitialized) return
        this.appContext = context.applicationContext
        this.storage = storage
        this.repository = repository
        this.lifecycleManager = lifecycleManager

        HiddenApiUnlock.unlock()
        ActivityManagerHook.install()
        replaceInstrumentation()

        ClonerLog.i(TAG, "virtual engine initialized (hiddenApi=${HiddenApiUnlock.unlocked})")
    }

    private fun replaceInstrumentation() {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
                ?: throw IllegalStateException("ActivityThread not ready")
            val field = activityThreadClass.getDeclaredField("mInstrumentation")
            field.isAccessible = true
            if (field.get(currentActivityThread) is ClonerInstrumentation) return
            field.set(currentActivityThread, ClonerInstrumentation())
            ClonerLog.i(TAG, "instrumentation hook installed")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "instrumentation hook FAILED — container disabled", t)
        }
    }

    // ------------------------------------------------------------------
    // Runtime management
    // ------------------------------------------------------------------

    fun runtimeFor(cloneId: Long): GuestRuntime? = runtimes[cloneId]

    /**
     * Loads (or returns the cached) runtime for a clone. Throws
     * [GuestLoadException] when the guest APK cannot be executed here.
     */
    fun ensureRuntime(clone: CloneInfo): GuestRuntime {
        runtimes[clone.cloneId]?.let { return it }
        synchronized(this) {
            runtimes[clone.cloneId]?.let { return it }
            val runtime = buildRuntime(clone)
            runtimes[clone.cloneId] = runtime
            return runtime
        }
    }

    private fun buildRuntime(clone: CloneInfo): GuestRuntime {
        val storageRoot = File(clone.storagePath)
        val cloneDirName = storageRoot.name
        val externalRoot = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            "virtual_apps/$cloneDirName"
        )
        return GuestRuntime(
            cloneId = clone.cloneId,
            packageName = clone.originalPackageName,
            apkPath = clone.apkPath,
            splitApkPaths = clone.splitApkPaths,
            storedLauncherClass = clone.launcherClass.takeIf { it.isNotBlank() },
            storageRoot = storageRoot,
            externalRoot = externalRoot,
            hostContext = appContext,
            storage = storage
        )
    }

    /**
     * Rebuilds a runtime after process death. The host may be killed while a
     * guest task sits in Recents; when the user taps that task the stub
     * wrapper arrives with no live runtime. The registry is the source of
     * truth, so the runtime is rebuilt from it (one short blocking read —
     * happens at most once per clone per process). Returns null when the
     * clone no longer exists (deleted while dead).
     */
    fun recoverRuntime(cloneId: Long): GuestRuntime? {
        runtimes[cloneId]?.let { return it }
        return synchronized(this) {
            runtimes[cloneId]?.let { return it }
            val clone = kotlinx.coroutines.runBlocking {
                runCatching { repository.getClone(cloneId) }.getOrNull()
            } ?: return null
            val runtime = try {
                buildRuntime(clone)
            } catch (t: Throwable) {
                ClonerLog.e(TAG, "runtime recovery failed for clone=$cloneId", t)
                return null
            }
            runtimes[cloneId] = runtime
            ClonerLog.i(TAG, "runtime RECOVERED after process death for clone=$cloneId")
            runtime
        }
    }

    fun unloadRuntime(cloneId: Long) {
        runtimes.remove(cloneId)
        ClonerLog.i(TAG, "runtime unloaded for clone=$cloneId")
    }

    // ------------------------------------------------------------------
    // Launch plumbing (called by the hooks)
    // ------------------------------------------------------------------

    /** Instrumentation: resolves a stub wrapper intent to (runtime, guest class). */
    fun resolveStubLaunch(intent: Intent): Pair<GuestRuntime, String>? {
        if (!intent.hasExtra(ExtraKeys.GUEST_INTENT)) return null
        val cloneId = intent.getLongExtra(ExtraKeys.CLONE_ID, -1L)
        var runtime = runtimes[cloneId]
        if (runtime == null) {
            // Restored after process death: rebuild from the registry so the
            // Recents task keeps working instead of silently finishing.
            runtime = recoverRuntime(cloneId)
            if (runtime == null) {
                ClonerLog.w(TAG, "no runtime and no registry entry for clone=$cloneId")
                return null
            }
        }
        val unwrapped = runtime.unwrapLaunch(intent) ?: return null
        val (guestClassName, _, _) = unwrapped
        lifecycleManager.report(cloneId, CloneLifecycle.RUNNING)
        return runtime to guestClassName
    }

    /** ActivityManagerHook: rewrites guest-initiated startActivity calls. */
    fun remapIfGuestIntent(intent: Intent): Intent? {
        if (intent.hasExtra(ExtraKeys.GUEST_INTENT)) return null // already a wrapper
        val component = intent.component ?: return null
        val runtime = runtimes.values.firstOrNull { it.packageName == component.packageName }
            ?: return null
        if (runtime.componentEnabledSetting(component) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            ClonerLog.w(TAG, "blocked launch of disabled component $component")
            return null
        }
        return try {
            runtime.buildStubIntent(intent)
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "stub mapping failed for $component", t)
            null
        }
    }

    fun isGuestActivity(activity: Activity): Boolean =
        guestActivities.containsKey(System.identityHashCode(activity))

    fun onGuestActivityDestroyed(activity: Activity) {
        guestActivities.remove(System.identityHashCode(activity))
        // Find the owning runtime via intent extras.
        val cloneId = activity.intent?.getLongExtra(ExtraKeys.CLONE_ID, -1L) ?: -1L
        val runtime = runtimes[cloneId] ?: return
        if (runtime.activityCount.get() > 0 && guestActivities.values.none {
                it.intent?.getLongExtra(ExtraKeys.CLONE_ID, -1L) == cloneId
            }
        ) {
            lifecycleManager.report(cloneId, CloneLifecycle.STOPPING)
            lifecycleManager.report(cloneId, CloneLifecycle.STOPPED)
            ClonerLog.i(TAG, "clone=$cloneId has no live activities — stopped")
        }
    }

    /** Marks a live guest activity for destroy-tracking. */
    fun trackGuestActivity(activity: Activity) {
        guestActivities[System.identityHashCode(activity)] = activity
    }

    fun reportRuntimeError(runtime: GuestRuntime, t: Throwable) {
        lifecycleManager.report(runtime.cloneId, CloneLifecycle.ERROR)
        ClonerLog.e(TAG, "clone=${runtime.cloneId} runtime error", t)
    }

    companion object {
        const val TAG = "VirtualEngine"

        @Volatile
        private var instance: VirtualEngine? = null

        fun getInstance(): VirtualEngine =
            instance ?: synchronized(this) {
                instance ?: VirtualEngine().also { instance = it }
            }
    }
}
