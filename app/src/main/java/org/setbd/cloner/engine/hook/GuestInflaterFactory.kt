package org.setbd.cloner.engine.hook

import android.content.Context
import android.view.LayoutInflater
import org.setbd.cloner.util.ClonerLog

/**
 * Builds [LayoutInflater] instances bound to the GUEST context.
 *
 * Why this exists: when the system launches a stub activity it attaches a
 * PhoneWindow whose internal LayoutInflater carries the HOST ContextImpl —
 * view classes named in guest layouts (every real app ships custom views)
 * would then be resolved against the host class loader and crash with
 * ClassNotFoundException mid-inflation. Swapping the window's inflater for
 * one built on the [org.setbd.cloner.engine.guest.VirtualContext] routes
 * every `createView` through `mContext.getClassLoader()` — the guest's
 * class loader — so android.widget.* short names AND guest custom views
 * both inflate correctly.
 */
object GuestInflaterFactory {

    private const val TAG = "GuestInflater"

    /**
     * Creates a guest-bound inflater.
     *
     * @param guestContext the virtual (per-clone) context the inflater sees
     *   as its own — its `getClassLoader()` is the guest class loader.
     * @param factoriesSource optional original inflater whose Factory /
     *   Factory2 chain should be preserved (used for the window swap).
     * @param privateFactory optional Factory2 (the activity implements it)
     *   wired as the framework's private factory so fragment-driven view
     *   creation keeps working inside guest activities.
     */
    fun create(
        guestContext: Context,
        factoriesSource: LayoutInflater? = null,
        privateFactory: LayoutInflater.Factory2? = null
    ): LayoutInflater {
        // Preferred: the framework's PhoneLayoutInflater (adds the
        // android.widget./android.webkit. prefix resolution chain).
        val inflater: LayoutInflater = try {
            Class.forName("android.app.PhoneLayoutInflater")
                .getConstructor(Context::class.java)
                .newInstance(guestContext) as LayoutInflater
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "PhoneLayoutInflater unavailable, using plain clone: ${t.message}")
            object : LayoutInflater(guestContext) {
                override fun cloneInContext(newContext: Context): LayoutInflater = this
            }
        }

        // Preserve any factories the original inflater already carried.
        factoriesSource?.let { source ->
            runCatching {
                source.factory?.let { f -> inflater.factory = f }
            }.onFailure { ClonerLog.w(TAG, "factory copy skipped: ${it.message}") }
            runCatching {
                source.factory2?.let { f -> inflater.factory2 = f }
            }.onFailure { ClonerLog.w(TAG, "factory2 copy skipped: ${it.message}") }
        }

        // Framework-private Factory2 (activity/fragment view routing).
        privateFactory?.let { factory2 ->
            runCatching {
                LayoutInflater::class.java
                    .getDeclaredMethod("setPrivateFactory", LayoutInflater.Factory2::class.java)
                    .apply { isAccessible = true }
                    .invoke(inflater, factory2)
            }.onFailure { ClonerLog.w(TAG, "private factory not wired: ${it.message}") }
        }

        return inflater
    }
}
