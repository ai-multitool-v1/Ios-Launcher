package org.setbd.cloner.engine.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import org.setbd.cloner.engine.VirtualEngine
import org.setbd.cloner.engine.guest.GuestRuntime
import org.setbd.cloner.engine.guest.VirtualContext
import org.setbd.cloner.engine.stub.ExtraKeys
import org.setbd.cloner.engine.stub.StubActivity
import org.setbd.cloner.util.ClonerLog

/**
 * Replaces ActivityThread.mInstrumentation with a container-aware subclass
 * (the same process-wide injection BlackBox-class engines use).
 *
 *  - newActivity(): when the system launches one of our STUB activities with
 *    a wrapped guest intent, the real guest activity class is instantiated
 *    from the owning clone's class loader instead.
 *
 *  - callActivityOnCreate() (and every other lifecycle callback): before the
 *    guest callback runs, the activity's base context is swapped for a
 *    [VirtualContext] (per-clone storage + guest identity), its Resources
 *    are swapped for the guest APK's (so guest R ids resolve), its
 *    ActivityInfo is swapped for the GUEST's (theme/orientation/soft-input
 *    resolve as the guest declared them), its Application is swapped for the
 *    clone's guest Application, and the guest theme is applied.
 *
 *  - CRASH CONTAINMENT: a guest runs inside the host process, so an uncaught
 *    exception in ANY guest lifecycle callback would kill the whole cloner
 *    (every other clone included). Every guest callback is therefore
 *    contained: a crash during onCreate swaps the activity to a readable
 *    error screen; a crash in any later callback is logged and skipped. The
 *    host process always survives.
 *
 * Everything else keeps the framework's default behavior — the hook never
 * touches system activities or the host's own UI.
 */
class ClonerInstrumentation : Instrumentation() {

    private val engine: VirtualEngine get() = VirtualEngine.getInstance()

    // ------------------------------------------------------------------
    // Activity instantiation
    // ------------------------------------------------------------------

    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        val launch = intent?.let { engine.resolveStubLaunch(it) }
        if (launch == null) {
            ClonerLog.w(
                TAG,
                "newActivity: no guest mapping for $className — stub will explain " +
                    "(component=${intent?.component}, hasWrapper=${intent?.hasExtra(ExtraKeys.GUEST_INTENT)})"
            )
            return super.newActivity(cl, className, intent)
        }
        val (runtime, guestClassName) = launch
        return try {
            runtime.activityCount.incrementAndGet()
            ClonerLog.d(TAG, "newActivity guest=$guestClassName clone=${runtime.cloneId}")
            val activity = super.newActivity(runtime.classLoader, guestClassName, intent)
            engine.trackGuestActivity(activity)
            activity
        } catch (t: Throwable) {
            runtime.activityCount.decrementAndGet()
            ClonerLog.e(TAG, "guest activity instantiation failed: $guestClassName", t)
            engine.reportRuntimeError(runtime, t)
            // The stub fallback finishes itself — make it carry the real reason.
            StubActivity.pendingDiagnostics =
                "$guestClassName (${t.javaClass.simpleName}: ${t.message?.take(120) ?: "unknown"})"
            super.newActivity(cl, className, intent)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle containment
    // ------------------------------------------------------------------

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val launch = guestLaunchOf(activity)
        if (launch == null) {
            super.callActivityOnCreate(activity, icicle)
            return
        }
        engine.ensureHooks()
        prepareGuestEnvironment(activity, launch)
        try {
            super.callActivityOnCreate(activity, icicle)
        } catch (t: Throwable) {
            containCreateCrash(activity, launch.first, launch.second, t)
        }
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        val launch = guestLaunchOf(activity)
        if (launch == null) {
            super.callActivityOnCreate(activity, icicle, persistentState)
            return
        }
        engine.ensureHooks()
        prepareGuestEnvironment(activity, launch)
        try {
            super.callActivityOnCreate(activity, icicle, persistentState)
        } catch (t: Throwable) {
            containCreateCrash(activity, launch.first, launch.second, t)
        }
    }

    override fun callActivityOnStart(activity: Activity) =
        guard(activity, "onStart") { super.callActivityOnStart(activity) }

    override fun callActivityOnRestart(activity: Activity) =
        guard(activity, "onRestart") { super.callActivityOnRestart(activity) }

    override fun callActivityOnResume(activity: Activity) =
        guard(activity, "onResume") { super.callActivityOnResume(activity) }

    override fun callActivityOnPause(activity: Activity) =
        guard(activity, "onPause") { super.callActivityOnPause(activity) }

    override fun callActivityOnUserLeaving(activity: Activity) =
        guard(activity, "onUserLeaving") { super.callActivityOnUserLeaving(activity) }

    override fun callActivityOnStop(activity: Activity) =
        guard(activity, "onStop") { super.callActivityOnStop(activity) }

    override fun callActivityOnDestroy(activity: Activity) {
        val wasGuest = engine.isGuestActivity(activity)
        guard(activity, "onDestroy") { super.callActivityOnDestroy(activity) }
        if (wasGuest) {
            engine.onGuestActivityDestroyed(activity)
        }
    }

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent?) =
        guard(activity, "onNewIntent") { super.callActivityOnNewIntent(activity, intent!!) }

    /** API 35 path — the 3-arg overload is what ActivityThread invokes. */
    override fun callActivityOnNewIntent(activity: Activity, intent: Intent, caller: android.app.ComponentCaller) =
        guard(activity, "onNewIntent") { super.callActivityOnNewIntent(activity, intent, caller) }

    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) =
        guard(activity, "onSaveInstanceState") {
            super.callActivityOnSaveInstanceState(activity, outState)
        }

    override fun callActivityOnSaveInstanceState(
        activity: Activity,
        outState: Bundle,
        outPersistentState: android.os.PersistableBundle
    ) = guard(activity, "onSaveInstanceState+p") {
        super.callActivityOnSaveInstanceState(activity, outState, outPersistentState)
    }

    override fun callActivityOnRestoreInstanceState(activity: Activity, savedInstanceState: Bundle) =
        guard(activity, "onRestoreInstanceState") {
            super.callActivityOnRestoreInstanceState(activity, savedInstanceState)
        }

    override fun callActivityOnRestoreInstanceState(
        activity: Activity,
        savedInstanceState: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) = guard(activity, "onRestoreInstanceState+p") {
        super.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState)
    }

    override fun callActivityOnPostCreate(activity: Activity, savedInstanceState: Bundle?) =
        guard(activity, "onPostCreate") {
            super.callActivityOnPostCreate(activity, savedInstanceState)
        }

    override fun callActivityOnPostCreate(
        activity: Activity,
        savedInstanceState: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) = guard(activity, "onPostCreate+p") {
        super.callActivityOnPostCreate(activity, savedInstanceState, persistentState)
    }

    override fun callActivityOnPictureInPictureRequested(activity: Activity) =
        guard(activity, "onPictureInPictureRequested") {
            super.callActivityOnPictureInPictureRequested(activity)
        }

    /**
     * Runs a guest lifecycle callback with containment. Host activities
     * (no wrapper extra) pass straight through. A guest crash is logged,
     * reported and swallowed — the framework keeps a consistent state
     * because [super] was either never invoked (create) or fully
     * delegated through (everything else).
     */
    private inline fun guard(activity: Activity, callback: String, block: () -> Unit) {
        val launch = guestLaunchOf(activity)
        if (launch == null) {
            block()
            return
        }
        try {
            block()
        } catch (t: Throwable) {
            val (runtime, guestClass) = launch
            ClonerLog.e(TAG, "guest $callback crashed — contained: $guestClass", t)
            engine.reportRuntimeError(runtime, t)
        }
    }

    // ------------------------------------------------------------------
    // Guest environment attachment
    // ------------------------------------------------------------------

    /**
     * Swaps context/resources/theme/application for guest activities.
     * Best-effort: any failure leaves the activity running on the host
     * surface and marks the clone degraded rather than crashing.
     */
    private fun prepareGuestEnvironment(activity: Activity, launch: Pair<GuestRuntime, String>) {
        val (runtime, guestClassName) = launch
        try {
            val originalBase = readBaseContext(activity) ?: return
            val virtualContext = VirtualContext(originalBase, runtime)

            // 1. Base context → virtual namespace.
            ContextWrapper::class.java.getDeclaredField("mBase").apply {
                isAccessible = true
                set(activity, virtualContext)
            }

            // 2. ActivityInfo → the GUEST's declaration (BlackBox parity).
            //    Theme, orientation, soft-input and uiOptions now resolve
            //    from what the guest manifest actually declares, and
            //    getActivityInfo() returns guest identity to guest code.
            val guestInfo = runtime.activityInfo(guestClassName)
            if (guestInfo != null) {
                swapDeclaredField(activity, "mActivityInfo", guestInfo)
                applyWindowMetadata(activity, guestInfo)
            }

            // 3. Resources → guest APK (guest R ids resolve through it).
            val guestResources = runtime.guestResources
            if (guestResources != null) {
                val themeWrapperClass = Class.forName("android.content.ContextThemeWrapper")
                runCatching {
                    themeWrapperClass.getDeclaredField("mResources").apply {
                        isAccessible = true
                        set(activity, guestResources)
                    }
                }.onFailure {
                    // Very old/very new frameworks: fall back to the field
                    // declared on the activity's own class hierarchy.
                    swapDeclaredField(activity, "mResources", guestResources)
                }
                runCatching {
                    themeWrapperClass.getDeclaredField("mTheme").apply {
                        isAccessible = true
                        set(activity, null)
                    }
                    themeWrapperClass.getDeclaredField("mThemeResource").apply {
                        isAccessible = true
                        setInt(activity, 0)
                    }
                }

                // 4. Guest activity theme from the guest manifest. The theme
                //    object is rebuilt lazily against the swapped (guest)
                //    resources, so the guest res id resolves correctly.
                val themeRes = guestInfo?.theme
                    ?: runtime.activityInfo(guestClassName)?.theme
                    ?: 0
                if (themeRes != 0) {
                    runCatching { activity.setTheme(themeRes) }
                        .onFailure {
                            ClonerLog.w(TAG, "setTheme failed — applying via applyStyle", it)
                            runCatching { activity.theme.applyStyle(themeRes, true) }
                                .onFailure { f -> ClonerLog.w(TAG, "guest theme apply failed", f) }
                        }
                }
            }

            // 5. Application → the clone's guest Application instance.
            runtime.guestApplication?.let { app ->
                swapDeclaredField(activity, "mApplication", app)
            }

            // 6. Window LayoutInflater → guest-bound. Guest custom views in
            //    XML layouts resolve through the guest class loader (the
            //    window's stock inflater carries the HOST ContextImpl and
            //    would crash every non-framework view class).
            swapWindowInflater(activity, virtualContext)

            ClonerLog.d(TAG, "guest environment attached clone=${runtime.cloneId} activity=$guestClassName")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "guest environment attach failed clone=${runtime.cloneId}", t)
            engine.reportRuntimeError(runtime, t)
        }
    }

    /** Applies guest-declared window metadata (orientation, soft input). */
    private fun applyWindowMetadata(activity: Activity, info: ActivityInfo) {
        runCatching {
            if (info.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = info.screenOrientation
            }
            if (info.softInputMode != 0) {
                activity.window?.setSoftInputMode(info.softInputMode)
            }
        }.onFailure { ClonerLog.w(TAG, "guest window metadata apply failed", it) }
    }

    // ------------------------------------------------------------------
    // Crash containment for onCreate failures
    // ------------------------------------------------------------------

    /**
     * A guest activity crashed inside onCreate. The host process MUST
     * survive (every other clone would die with it). Swap the activity to a
     * readable error screen and finish it on back — the framework then
     * resumes a perfectly normal, fully-initialized activity.
     */
    private fun containCreateCrash(activity: Activity, runtime: GuestRuntime, guestClass: String, crash: Throwable) {
        ClonerLog.e(TAG, "guest onCreate crashed — contained: $guestClass", crash)
        engine.reportRuntimeError(runtime, crash)
        try {
            val reason = crash.message?.take(160) ?: crash.javaClass.simpleName
            val messageText = "This clone crashed while starting.\n\n" +
                "$guestClass\n" +
                "${crash.javaClass.simpleName}: $reason\n\n" +
                "The cloner itself is fine — reopen the clone to try again."
            val view = TextView(activity).apply {
                textSize = 15f
                setTypeface(typeface, Typeface.NORMAL)
                setGravity(Gravity.CENTER)
                val pad = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
                ).toInt()
                setPadding(pad, pad, pad, pad)
                text = messageText
            }
            activity.setContentView(view)
            Toast.makeText(activity, "Clone crashed: ${crash.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "error screen failed — finishing activity", t)
            try {
                activity.finish()
            } catch (_: Throwable) {
                // Nothing more this process can do; keep it alive anyway.
            }
        }
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /** Resolves (runtime, guest class) for a wrapper intent without lifecycle reporting. */
    private fun guestLaunchOf(activity: Activity): Pair<GuestRuntime, String>? {
        val intent = activity.intent ?: return null
        if (!intent.hasExtra(ExtraKeys.GUEST_INTENT)) return null
        return engine.peekGuestLaunch(intent)
    }

    private fun readBaseContext(activity: Activity): Context? = try {
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            .get(activity) as? Context
    } catch (t: Throwable) {
        ClonerLog.e(TAG, "cannot read activity base context", t)
        null
    }

    /**
     * Replaces PhoneWindow.mLayoutInflater with a guest-bound inflater.
     * The activity itself is wired as the private Factory2 so fragment
     * view creation keeps routing correctly. Best-effort by design.
     */
    private fun swapWindowInflater(activity: Activity, guestContext: Context) {
        runCatching {
            val window = Activity::class.java.getDeclaredField("mWindow").apply {
                isAccessible = true
            }.get(activity) ?: return
            val guestInflater = GuestInflaterFactory.create(
                guestContext = guestContext,
                privateFactory = activity as? LayoutInflater.Factory2
            )
            window.javaClass.getDeclaredField("mLayoutInflater").apply {
                isAccessible = true
            }.set(window, guestInflater)
        }.onFailure {
            ClonerLog.w(TAG, "window inflater swap failed — guest custom views may crash", it)
        }
    }

    private fun swapDeclaredField(target: Any, fieldName: String, value: Any?): Boolean {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            runCatching {
                val field = clazz!!.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return true
            }
            clazz = clazz.superclass
        }
        ClonerLog.w(TAG, "field '$fieldName' not found on ${target.javaClass.name}")
        return false
    }

    private companion object {
        const val TAG = "Instrumentation"
    }
}
