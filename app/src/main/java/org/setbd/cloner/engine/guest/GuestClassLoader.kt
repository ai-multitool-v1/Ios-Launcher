package org.setbd.cloner.engine.guest

import dalvik.system.PathClassLoader

/**
 * Child-first class loader for guest APKs.
 *
 * Platform namespaces (android.*, java.*, javax.*, dalvik.*, sun.*,
 * com.android.*, org.w3c.dom.*, org.xml.sax.*, org.json.*) always resolve
 * through the parent so guest code shares the process framework. Everything
 * else resolves from the guest APK first, which guarantees each clone binds
 * to the support-library copies bundled inside its own APK instead of the
 * host's — a requirement for running two different versions of the same
 * library in one process.
 */
class GuestClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader
) : PathClassLoader(dexPath, librarySearchPath ?: "", parent) {

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        if (name == null) throw ClassNotFoundException("null class name")
        if (isPlatformClass(name)) {
            return parent.loadClass(name)
        }
        return try {
            findClass(name)
        } catch (e: ClassNotFoundException) {
            parent.loadClass(name)
        }
    }

    private fun isPlatformClass(name: String): Boolean {
        return name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("android.") ||
            name.startsWith("com.android.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("sun.") ||
            name.startsWith("org.w3c.dom.") ||
            name.startsWith("org.xml.sax.") ||
            name.startsWith("org.json.")
    }
}
