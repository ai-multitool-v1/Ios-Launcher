package org.setbd.cloner.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.util.ClonerLog

/**
 * Permission mediation between guests and the host.
 *
 * A guest physically runs inside the host process, so runtime permissions
 * resolve against the HOST's grants. This mediator:
 *
 *  1. exposes the full host battery ([hostRequestPermissions]) — requested
 *     ONCE in a batched system dialog so every clone works without extra
 *     prompts (the guest's own permission requests then succeed instantly
 *     because the host already holds the grant);
 *  2. reads the permissions a specific guest APK requests and reports which
 *     of the dangerous ones are still missing on the host;
 *  3. fires standard runtime dialogs — no permission-bypass of any kind.
 *
 * Normal permissions (INTERNET, NFC, VIBRATE, …) are granted to the host at
 * install time and need no mediation. Background location is deliberately
 * excluded from the batch (Android requires it to be granted in a separate
 * step after fine location) and is therefore surfaced per-clone.
 */
class PermissionMediator(private val context: Context) {

    /** Dangerous permissions the guest requests but the host lacks. */
    fun missingDangerousPermissions(clone: CloneInfo): List<String> {
        val requested = requestedPermissions(clone) ?: return emptyList()
        return requested.filter { it in DANGEROUS && !isGranted(it) }
    }

    /** All dangerous permissions the guest requests (granted or not). */
    fun requestedDangerousPermissions(clone: CloneInfo): List<String> {
        val requested = requestedPermissions(clone) ?: return emptyList()
        return requested.filter { it in DANGEROUS }
    }

    /**
     * The batched set the HOST asks for at first run: every grantable
     * dangerous permission the container battery declares. Guests inherit
     * these grants through the host process.
     */
    fun hostRequestPermissions(): List<String> = HOST_BATCH.filter { !isGranted(it) }

    /** How many of the batched permissions are already granted (for UI). */
    fun hostGrantedCount(): Int = HOST_BATCH.count { isGranted(it) }

    /** Total size of the batched battery (for UI progress). */
    fun hostBatchSize(): Int = HOST_BATCH.size

    /** True when every batchable permission is granted. */
    fun allHostPermissionsGranted(): Boolean = HOST_BATCH.all { isGranted(it) }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Fires the standard runtime permission dialog for the given list. */
    fun requestPermissions(activity: android.app.Activity, permissions: List<String>) {
        if (permissions.isEmpty()) return
        ClonerLog.i(TAG, "requesting host-level permissions: $permissions")
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_CODE)
    }

    /** Convenience: request everything from [hostRequestPermissions] at once. */
    fun requestAllHostPermissions(activity: android.app.Activity) {
        requestPermissions(activity, hostRequestPermissions())
    }

    private fun requestedPermissions(clone: CloneInfo): Array<String>? {
        // Base APK + splits both carry <uses-permission> entries (usually the
        // base holds them, but read every file to be sure).
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val base = runCatching {
            pm.getPackageArchiveInfo(clone.apkPath, flags)?.requestedPermissions
        }.getOrNull()
        if (base != null) return base
        for (split in clone.splitApkPaths) {
            val fromSplit = runCatching {
                pm.getPackageArchiveInfo(split, flags)?.requestedPermissions
            }.getOrNull()
            if (fromSplit != null) return fromSplit
        }
        return null
    }

    companion object {
        const val REQUEST_CODE = 4211
        private const val TAG = "PermissionMediator"

        /** Dangerous permission set per modern Android versions. */
        private val DANGEROUS: Set<String> = buildSet {
            addAll(
                listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.GET_ACCOUNTS,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.ADD_VOICEMAIL,
                    Manifest.permission.USE_SIP,
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_MMS,
                    Manifest.permission.RECEIVE_WAP_PUSH,
                    Manifest.permission.ACTIVITY_RECOGNITION
                )
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addAll(
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                add(Manifest.permission.UWB_RANGING)
            }
        }

        /**
         * The batched first-run request. Background location is intentionally
         * absent (Android requires a separate grant flow); SMS and call-log
         * groups are included because the host APK is distributed outside the
         * Play policy pipeline and guests genuinely use them.
         */
        private val HOST_BATCH: List<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.GET_ACCOUNTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
    }
}
