package org.setbd.cloner.engine

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.setbd.cloner.util.ClonerLog

/**
 * Unlocks non-SDK (greylist) access on Android 9+.
 *
 * The container engine must touch a handful of internal framework entry
 * points (ActivityThread, instrumentation slot, AssetManager#addAssetPath).
 * Since Android 9 these are restricted; `VMRuntime.setHiddenApiExemptions("L")`
 * exempts the whole heap-class namespace using the documented LSPosed
 * HiddenApiBypass library. On Android < 9 there is nothing to unlock.
 *
 * This only affects THIS process (the host) — it does not modify the system,
 * weaken SELinux, or touch other apps.
 */
object HiddenApiUnlock {

    private const val TAG = "HiddenApiUnlock"

    @Volatile
    var unlocked: Boolean = false
        private set

    fun unlock() {
        if (unlocked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            unlocked = true
            return
        }
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntimeClass
                .getDeclaredMethod("getRuntime")
                .invoke(null)
            HiddenApiBypass.invoke(
                vmRuntimeClass,
                runtime,
                "setHiddenApiExemptions",
                "L"
            )
            unlocked = true
            ClonerLog.i(TAG, "hidden API exemptions applied")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "hidden API unlock failed — container may be degraded", t)
        }
    }
}
