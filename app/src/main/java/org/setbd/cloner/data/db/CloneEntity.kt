package org.setbd.cloner.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted clone record. Survives host restarts so the launcher can restore
 * icons, names, order and enabled state.
 */
@Entity(tableName = "clones")
data class CloneEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "clone_id") val cloneId: Long = 0L,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "apk_path") val apkPath: String,
    @ColumnInfo(name = "storage_path") val storagePath: String,
    @ColumnInfo(name = "custom_icon_path") val customIconPath: String? = null,
    @ColumnInfo(name = "version_name") val versionName: String = "",
    @ColumnInfo(name = "creation_time") val creationTime: Long = 0L,
    @ColumnInfo(name = "last_launch_time") val lastLaunchTime: Long = 0L,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "lifecycle_state") val lifecycleState: String = "CREATED",
    @ColumnInfo(name = "degraded") val degraded: Boolean = false
)
