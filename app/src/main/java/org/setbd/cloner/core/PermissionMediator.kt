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
 *  1. reads the permissions the guest APK requests (public manifest data),
 *  2. intersects them with the dangerous set relevant to the running Android
 *     version,
 *  3. lets the UI request the still-missing ones at HOST level — one tap,
 *     standard system dialog, no permission-bypass of any kind.
 *
 * Normal permissions (INTERNET, NFC, …) are granted to the host at install
 * time and need no mediation.
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

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Fires the standard runtime permission dialog for the given list. */
    fun requestPermissions(activity: android.app.Activity, permissions: List<String>) {
        if (permissions.isEmpty()) return
        ClonerLog.i(TAG, "requesting host-level permissions for guest: $permissions")
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_CODE)
    }

    private fun requestedPermissions(clone: CloneInfo): Array<String>? {
        return runCatching {
            context.packageManager
                .getPackageArchiveInfo(clone.apkPath, PackageManager.GET_PERMISSIONS)
                ?.requestedPermissions
        }.getOrNull()
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
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.GET_ACCOUNTS,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
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
        }
    }
}
