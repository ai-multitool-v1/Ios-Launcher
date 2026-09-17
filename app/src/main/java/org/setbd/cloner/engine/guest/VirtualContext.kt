package org.setbd.cloner.engine.guest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.content.res.Resources
import android.view.Display
import org.setbd.cloner.util.ClonerLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ContextWrapper handed to guest code.
 *
 * Every location a guest may reasonably discover its own identity or private
 * storage through is redirected into the owning clone's namespace:
 *
 *  - package name / application info → the guest package
 *  - files, cache, databases, external dirs → <clone>/files|cache|databases
 *  - shared preferences → namespaced under the clone id (isolated per clone;
 *    stored inside the host prefs dir because ContextImpl owns that directory)
 *  - class loader / resources / assets → guest APK
 *  - application context → this wrapper (keeps in-guest navigation inside
 *    the virtual namespace)
 *
 * Known POC limitations (documented in docs/COMPATIBILITY.md): direct writes
 * to absolute paths and ContextImpl-owned service objects are NOT redirected,
 * services/receivers/providers of the guest are not virtualized, and methods
 * that cannot be honestly served log an explicit warning instead of faking
 * success.
 */
class VirtualContext(
    base: Context,
    private val runtime: GuestRuntime
) : ContextWrapper(base) {

    private val prefsPrefix = "clone${runtime.cloneId}__"

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    override fun getPackageName(): String = runtime.packageName

    override fun getApplicationInfo() = runtime.guestApplicationInfo

    override fun getPackageManager() = runtime.virtualPackageManager

    override fun getClassLoader(): ClassLoader = runtime.classLoader

    override fun getResources() = runtime.guestResources ?: super.getResources()

    override fun getAssets() = runtime.guestResources?.assets ?: super.getAssets()

    override fun getApplicationContext(): Context = this

    override fun createPackageContext(packageName: String, flags: Int): Context {
        if (packageName == runtime.packageName) return this
        return super.createPackageContext(packageName, flags)
    }

    // ------------------------------------------------------------------
    // Storage redirection
    // ------------------------------------------------------------------

    override fun getFilesDir(): File = runtime.filesDir

    override fun getNoBackupFilesDir(): File = File(runtime.filesDir, "no_backup").apply { mkdirs() }

    override fun getCacheDir(): File = runtime.cacheDir

    override fun getCodeCacheDir(): File = runtime.odexDir

    override fun getExternalFilesDir(type: String?): File? {
        val root = File(runtime.externalRoot, "files").apply { mkdirs() }
        return if (type == null) root else File(root, type).apply { mkdirs() }
    }

    override fun getExternalCacheDir(): File? =
        File(runtime.externalRoot, "cache").apply { mkdirs() }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        val primary = getExternalFilesDir(type) ?: return arrayOf()
        return arrayOf(primary)
    }

    override fun getExternalCacheDirs(): Array<File> {
        val primary = getExternalCacheDir() ?: return arrayOf()
        return arrayOf(primary)
    }

    override fun getObbDir(): File = File(runtime.externalRoot, "obb").apply { mkdirs() }

    override fun getObbDirs(): Array<File> = arrayOf(getObbDir())

    override fun getDataDir(): File = runtime.storageRoot

    override fun getFileStreamPath(name: String): File = File(runtime.filesDir, name)

    /** Reads guest files from the clone's own files directory. */
    override fun openFileInput(name: String): FileInputStream {
        val file = File(runtime.filesDir, name)
        return FileInputStream(file)
    }

    /** Writes guest files into the clone's own files directory (append-aware). */
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val file = File(runtime.filesDir, name)
        file.parentFile?.mkdirs()
        return FileOutputStream(file, mode and Context.MODE_APPEND != 0)
    }

    override fun deleteFile(name: String): Boolean = File(runtime.filesDir, name).delete()

    override fun fileList(): Array<String> =
        runtime.filesDir.list() ?: arrayOf()

    override fun getDir(name: String, mode: Int): File = File(runtime.filesDir, name).apply { mkdirs() }

    override fun getDatabasePath(name: String): File = File(runtime.databasesDir, name)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
        runtime.databasesDir.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        runtime.databasesDir.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()

    /**
     * Preferences are namespaced per clone ("clone3__user_prefs"). They live
     * in the host prefs directory (ContextImpl owns it), but the namespace is
     * strictly clone-private so two clones never see each other's keys.
     */
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        ClonerLog.d(TAG, "prefs '$name' → namespaced '${prefsPrefix}$name'")
        return super.getSharedPreferences(prefsPrefix + name, mode)
    }

    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String): Boolean {
        ClonerLog.w(TAG, "moveSharedPreferencesFrom unsupported for guest '$name'")
        return false
    }

    override fun moveDatabaseFrom(sourceContext: Context, name: String): Boolean {
        ClonerLog.w(TAG, "moveDatabaseFrom unsupported for guest '$name'")
        return false
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        super.deleteSharedPreferences(prefsPrefix + name)

    // ------------------------------------------------------------------
    // Unsupported system-bound operations (honest, logged, no fake success)
    // ------------------------------------------------------------------

    override fun startService(service: Intent): ComponentName? {
        recordUnsupported("startService ${service.component}")
        return null
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        recordUnsupported("startForegroundService ${service.component}")
        return null
    }

    override fun stopService(name: Intent): Boolean {
        recordUnsupported("stopService ${name.component}")
        return false
    }

    override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
        recordUnsupported("bindService ${service.component}")
        return false
    }

    override fun unbindService(conn: android.content.ServiceConnection) {
        // No-op: bindService never succeeded.
    }

    override fun startInstrumentation(
        className: ComponentName,
        profileFile: String?,
        arguments: android.os.Bundle?
    ): Boolean {
        recordUnsupported("startInstrumentation")
        return false
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        // Registered receivers work naturally inside the host process.
        return super.registerReceiver(receiver, filter)
    }

    override fun sendBroadcast(intent: Intent) {
        recordUnsupported("sendBroadcast ${intent.component ?: intent.action}")
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        recordUnsupported("sendBroadcast ${intent.component ?: intent.action}")
    }

    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?) {
        recordUnsupported("sendOrderedBroadcast")
    }

    override fun sendStickyBroadcast(intent: Intent) {
        recordUnsupported("sendStickyBroadcast")
    }

    override fun removeStickyBroadcast(intent: Intent) {
        // No-op.
    }

    override fun getContentResolver(): ContentResolver {
        // Guest-owned providers are not installed with the system; queries to
        // them will throw. Resolvers for OTHER apps still work via the host.
        ClonerLog.w(TAG, "content resolver used by guest — own providers not virtualized")
        return super.getContentResolver()
    }

    // ------------------------------------------------------------------
    // Window / display plumbing stays host-side
    // ------------------------------------------------------------------

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            // Guest-bound inflater: view classes named in guest layouts
            // (custom views) resolve through the guest class loader.
            return runtime.guestLayoutInflater(this)
        }
        return super.getSystemService(name)
    }

    override fun getDisplay(): Display? = super.getDisplay()

    override fun getMainLooper() = super.getMainLooper()

    override fun getTheme(): Resources.Theme = super.getTheme()

    override fun setTheme(resid: Int) {
        // App-context theme changes are ignored; activity themes are handled
        // by the instrumentation hook with real guest resource ids.
    }

    private fun recordUnsupported(operation: String) {
        runtime.recordUnsupportedUse(operation)
        ClonerLog.w(TAG, "unsupported in POC: $operation (clone=${runtime.cloneId})")
    }

    private companion object {
        const val TAG = "VirtualContext"
    }
}
