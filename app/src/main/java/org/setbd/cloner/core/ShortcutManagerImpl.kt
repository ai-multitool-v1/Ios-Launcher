package org.setbd.cloner.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.setbd.cloner.MainActivity
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.engine.guest.GuestResourcesLoader
import org.setbd.cloner.util.BitmapUtils
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates and maintains one distinct launcher shortcut per clone.
 *
 * Shortcut ids follow `clone_<id>`, labels carry the clone display name, and
 * the intent routes through the host activity with the clone id, so a
 * shortcut ALWAYS launches the virtual instance — never the original app.
 */
class ShortcutManagerImpl(
    private val context: Context,
    private val repository: CloneRepository
) {

    suspend fun syncShortcut(clone: CloneInfo) = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            !dynamicSupported()
        ) {
            return@withContext
        }
        try {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(clone.cloneId))
                .setShortLabel(clone.displayName)
                .setLongLabel("${clone.displayName} — ${clone.originalPackageName}")
                .setIcon(iconFor(clone))
                .setIntent(launchIntent(clone.cloneId))
                .build()
            val pushed = ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            ClonerLog.i(TAG, "dynamic shortcut clone=${clone.cloneId} pushed=$pushed")
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "shortcut sync failed for clone=${clone.cloneId}", t)
        }
    }

    suspend fun requestPinShortcut(clone: CloneInfo) = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            ClonerLog.w(TAG, "pin shortcuts unsupported on this launcher")
            return@withContext
        }
        try {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(clone.cloneId))
                .setShortLabel(clone.displayName)
                .setIcon(iconFor(clone))
                .setIntent(launchIntent(clone.cloneId))
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "pin shortcut failed for clone=${clone.cloneId}", t)
        }
    }

    fun removeShortcut(cloneId: Long) {
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId(cloneId))) }
            .onFailure { ClonerLog.w(TAG, "shortcut removal failed clone=$cloneId", it) }
    }

    /** Composes the badged clone icon bitmap (grid rendering + shortcuts). */
    suspend fun iconBitmap(clone: CloneInfo): Bitmap = withContext(Dispatchers.Default) {
        val number = clone.cloneId.toInt().coerceAtLeast(1)
        BitmapUtils.composeCloneIcon(context, loadBaseBitmap(clone), number)
    }

    suspend fun iconFor(clone: CloneInfo): IconCompat = withContext(Dispatchers.IO) {
        val composed = iconBitmap(clone)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            IconCompat.createWithAdaptiveBitmap(composed)
        } else {
            IconCompat.createWithBitmap(composed)
        }
    }

    private suspend fun loadBaseBitmap(clone: CloneInfo): Bitmap? {
        // Priority: user-chosen icon → captured original icon → guest APK icon.
        clone.customIconPath?.let { path ->
            BitmapUtils.decodeFile(File(path))?.let { return it }
        }
        BitmapUtils.decodeFile(File(clone.storagePath, "icons/original.png"))?.let { return it }
        val pm = context.packageManager
        val installedIcon = runCatching {
            BitmapUtils.drawableToBitmap(pm.getApplicationInfo(clone.originalPackageName, 0).loadIcon(pm))
        }.getOrNull()
        if (installedIcon != null) return installedIcon
        return runCatching {
            val archiveInfo = pm.getPackageArchiveInfo(clone.apkPath, 0)
                ?: return@runCatching null
            val appInfo = archiveInfo.applicationInfo ?: return@runCatching null
            val drawable = GuestResourcesLoader.loadIconDrawable(clone.apkPath, appInfo)
                ?: return@runCatching null
            BitmapUtils.drawableToBitmap(drawable)
        }.getOrNull()
    }

    suspend fun legacyIcon(clone: CloneInfo): Icon? {
        val iconCompat = iconFor(clone)
        return iconCompat.toIcon()
    }

    fun lifecycleAwareSync(clone: CloneInfo) {
        // Disabled clones lose their shortcut; enabled clones get it back.
        if (!clone.enabled) {
            removeShortcut(clone.cloneId)
        }
    }

    private fun dynamicSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1

    private fun shortcutId(cloneId: Long) = "clone_$cloneId"

    private fun launchIntent(cloneId: Long): Intent =
        Intent(ACTION_LAUNCH_CLONE)
            .setClass(context, MainActivity::class.java)
            .putExtra(EXTRA_CLONE_ID, cloneId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    companion object {
        const val ACTION_LAUNCH_CLONE = "org.setbd.cloner.ACTION_LAUNCH_CLONE"
        const val EXTRA_CLONE_ID = "org.setbd.cloner.extra.CLONE_ID"
        private const val TAG = "Shortcuts"
    }
}
