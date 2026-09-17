package org.setbd.cloner.engine.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import org.setbd.cloner.engine.VirtualEngine
import org.setbd.cloner.engine.guest.VirtualContext
import org.setbd.cloner.util.ClonerLog

/**
 * Replaces ActivityThread.mInstrumentation with a container-aware subclass.
 *
 *  - newActivity(): when the system launches one of our STUB activities with
 *    a wrapped guest intent, the real guest activity class is instantiated
 *    from the owning clone's class loader instead.
 *
 *  - callActivityOnCreate(): before the guest onCreate runs, the activity's
 *    base context is swapped for a [VirtualContext] (per-clone storage +
 *    fake package identity), its Resources are swapped for the guest APK's
 *    (so guest R ids resolve), its Application is swapped for the guest
 *    Application instance, and the guest activity theme is applied.
 *
 * Everything else keeps the framework's default behavior — the hook never
 * touches system activities or the host's own UI.
 */
class ClonerInstrumentation : Instrumentation() {

    private val engine: VirtualEngine get() = VirtualEngine.getInstance()

    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        val launch = intent?.let { engine.resolveStubLaunch(it) }
        if (launch == null) {
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
            // Fall back to the stub class, which finishes itself safely.
            super.newActivity(cl, className, intent)
        }
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        prepareGuestEnvironment(activity)
        super.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?, persistentState: android.os.PersistableBundle?) {
        prepareGuestEnvironment(activity)
        super.callActivityOnCreate(activity, icicle, persistentState)
    }

    override fun callActivityOnDestroy(activity: Activity) {
        val wasGuest = engine.isGuestActivity(activity)
        super.callActivityOnDestroy(activity)
        if (wasGuest) {
            engine.onGuestActivityDestroyed(activity)
        }
    }

    /**
     * Swaps context/resources/theme/application for guest activities.
     * Best-effort: any failure leaves the activity running on the host
     * surface and marks the clone degraded rather than crashing.
     */
    private fun prepareGuestEnvironment(activity: Activity) {
        val launch = activity.intent?.let { engine.resolveStubLaunch(it) } ?: return
        val (runtime, guestClassName) = launch
        try {
            val originalBase = readBaseContext(activity) ?: return
            val virtualContext = VirtualContext(originalBase, runtime)

            // 1. Base context → virtual namespace.
            ContextWrapper::class.java.getDeclaredField("mBase").apply {
                isAccessible = true
                set(activity, virtualContext)
            }

            // 2. Resources → guest APK (guest R ids resolve through it).
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

                // 3. Guest activity theme from the guest manifest.
                val themeRes = runtime.activityInfo(guestClassName)?.theme ?: 0
                if (themeRes != 0) {
                    runCatching { activity.setTheme(themeRes) }
                        .onFailure { ClonerLog.w(TAG, "guest theme apply failed", it) }
                }
            }

            // 4. Application → the clone's guest Application instance.
            runtime.guestApplication?.let { app ->
                swapDeclaredField(activity, "mApplication", app)
            }

            ClonerLog.d(TAG, "guest environment attached clone=${runtime.cloneId} activity=$guestClassName")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "guest environment attach failed clone=${runtime.cloneId}", t)
            engine.reportRuntimeError(runtime, t)
        }
    }

    private fun readBaseContext(activity: Activity): Context? = try {
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            .get(activity) as? Context
    } catch (t: Throwable) {
        ClonerLog.e(TAG, "cannot read activity base context", t)
        null
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
