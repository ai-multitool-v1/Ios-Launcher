package org.setbd.cloner.data.model

/**
 * Domain model for a single clone (a virtualized instance of an installed app
 * or an imported APK).
 */
data class CloneInfo(
    /** Unique numeric clone id (1, 2, 3 …) assigned by the registry. */
    val cloneId: Long,
    /** Package name of the source application, e.g. com.example.app. */
    val originalPackageName: String,
    /** Original application label as parsed from the APK. */
    val appLabel: String,
    /** User assigned name (defaults to a generated "App N" style label). */
    val displayName: String,
    /** Path of the cloned base APK copy stored inside the clone's private area. */
    val apkPath: String,
    /**
     * Paths of additional split APK copies (App Bundle configs: abi/density/
     * language splits) stored next to the base APK. Empty for monolithic APKs.
     * All splits are loaded together with the base — joined dex paths,
     * joined asset paths and joined native-lib search paths.
     */
    val splitApkPaths: List<String> = emptyList(),
    /** Root of the per-clone isolated storage namespace. */
    val storagePath: String,
    /** Optional path of a user supplied custom icon (PNG) inside storagePath. */
    val customIconPath: String? = null,
    val versionName: String = "",
    val creationTime: Long = 0L,
    val lastLaunchTime: Long = 0L,
    val enabled: Boolean = true,
    /** Position of the clone on the home grid. */
    val sortOrder: Int = 0,
    /** Last known lifecycle state (persisted, restored on restart). */
    val lifecycleState: CloneLifecycle = CloneLifecycle.CREATED,
    /** True when the guest Application failed to initialize and the clone runs degraded. */
    val degraded: Boolean = false
) {
    val hasCustomIcon: Boolean get() = !customIconPath.isNullOrBlank()
}

/**
 * Lifecycle states tracked for every clone.
 */
enum class CloneLifecycle {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR;

    val isRunningLike: Boolean
        get() = this == RUNNING || this == STARTING || this == STOPPING
}
