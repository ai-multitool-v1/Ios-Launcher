package org.setbd.cloner.engine.hook

import android.content.Intent
import org.setbd.cloner.engine.VirtualEngine
import org.setbd.cloner.util.ClonerLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Client-side interception of the system activity-manager binder proxies,
 * *inside the host process only*.
 *
 * Every Android activity launch flows through
 * `IActivityTaskManager`/`IActivityManager` binder proxies held by the
 * current process. When GUEST code calls startActivity (in-app navigation),
 * the intent carries the guest component, which the system cannot resolve —
 * this hook rewrites it into the mapped stub launch, transparently and only
 * for intents that belong to a running clone. Intents of the host, of other
 * apps and of the system are passed through byte-for-byte.
 *
 * This is process-local: the system_server, other apps and binder semantics
 * are untouched.
 */
object ActivityManagerHook {

    private const val TAG = "ActivityManagerHook"

    @Volatile
    var installed: Boolean = false
        private set

    fun install() {
        if (installed) return
        var ok = false
        ok = hookSingleton("android.app.ActivityManager", "IActivityManagerSingleton") || ok
        ok = hookSingleton("android.app.ActivityTaskManager", "IActivityTaskManagerSingleton") || ok
        installed = ok
        ClonerLog.i(TAG, "activity manager hook installed=$ok")
    }

    private fun hookSingleton(holderClass: String, fieldName: String): Boolean {
        return try {
            val holder = Class.forName(holderClass)
            val singletonField = holder.getDeclaredField(fieldName)
            singletonField.isAccessible = true
            val singleton = singletonField.get(null) ?: return false

            val mInstanceField = singleton.javaClass.getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            val original = mInstanceField.get(singleton) ?: return false
            if (Proxy.isProxyClass(original.javaClass)) return true // already hooked

            val iface = original.javaClass.interfaces.firstOrNull {
                it.name.endsWith("IActivityManager") || it.name.endsWith("IActivityTaskManager")
            } ?: Class.forName(
                if (fieldName.contains("Task")) "android.app.IActivityTaskManager" else "android.app.IActivityManager"
            )

            val proxied = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                GuestLaunchHandler(original)
            )
            mInstanceField.set(singleton, proxied)
            ClonerLog.i(TAG, "hooked $holderClass.$fieldName")
            true
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "hook $holderClass.$fieldName unavailable: ${t.message}")
            false
        }
    }

    private class GuestLaunchHandler(private val original: Any) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val rewritten = if (args != null && method.name.startsWith("startActivity")) {
                var changed = false
                for (i in args.indices) {
                    val arg = args[i]
                    if (arg is Intent) {
                        val mapped = VirtualEngine.getInstance().remapIfGuestIntent(arg)
                        if (mapped != null) {
                            args[i] = mapped
                            changed = true
                        }
                    }
                }
                changed
            } else {
                false
            }
            if (rewritten) {
                ClonerLog.d(TAG, "guest startActivity rewritten via ${method.name}")
            }
            return try {
                @Suppress("UNCHECKED_CAST")
                method.invoke(original, *(args ?: emptyArray<Any?>()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
