package org.setbd.cloner.engine.guest

/**
 * Redundant manifest resolution for guest APKs.
 *
 * The engine used to depend on a SINGLE manifest source (the binary XML
 * parsed through a hidden-API AssetManager). When that path failed on a
 * device — OEM hidden-API enforcement, unusual manifest, parser hiccup —
 * the whole launch died with "container cannot run this apk". This merger
 * removes the single point of failure by combining THREE independent
 * sources, in priority order:
 *
 *  1. **XML manifest** — [ManifestParser] walk of the guest APK's binary
 *     manifest ( richest: intent-filter launcher detection, per-activity
 *     themes and launch modes, application class).
 *  2. **Archive metadata** — [ArchiveMeta] built from
 *     `PackageManager.getPackageArchiveInfo`, which is an independent
 *     framework parser (works even when the custom AssetManager path is
 *     blocked). Provides the application class and every declared activity
 *     with its theme and launch mode (no intent-filter info).
 *  3. **Stored launcher** — the real launcher component captured at IMPORT
 *     time (`getLaunchIntentForPackage` for installed apps, XML parse for
 *     file imports). Even when BOTH manifest sources fail, a stored launcher
 *     is enough to start the guest with sensible defaults.
 *
 * The merge result is null ONLY when no source produced anything runnable
 * (zero activities everywhere and no stored launcher) — in that case there
 * is genuinely nothing to run.
 */
object ManifestMerger {

    /** One activity as reported by the framework archive parser. */
    data class ArchiveActivity(
        val className: String,
        val themeRes: Int,
        val launchMode: String
    )

    /** Guest metadata from `getPackageArchiveInfo`. */
    data class ArchiveMeta(
        val applicationClass: String?,
        val labelRes: Int,
        val iconRes: Int,
        val applicationThemeRes: Int,
        val activities: List<ArchiveActivity>
    )

    /**
     * Fully resolved manifest used by [GuestRuntime]. [launcherClassName] is
     * the launch entry (MAIN/LAUNCHER or stored or first declared) and is
     * null only when there are no activities at all.
     */
    data class MergedManifest(
        val applicationClassName: String?,
        val labelRes: Int,
        val iconRes: Int,
        val applicationThemeRes: Int,
        val activities: List<ManifestParser.GuestActivity>
    ) {
        val launcherActivity: ManifestParser.GuestActivity?
            get() = activities.firstOrNull { it.isLauncher }

        /** First declared activity — final fallback entry. */
        val firstActivity: ManifestParser.GuestActivity?
            get() = activities.firstOrNull()
    }

    /**
     * Merges the three sources. Never throws. Returns null only when the
     * result cannot contain a runnable entry.
     */
    fun merge(
        xml: ManifestParser.ManifestData?,
        archive: ArchiveMeta?,
        storedLauncherClass: String?
    ): MergedManifest? {
        // -- Identity ------------------------------------------------------
        val applicationClass = xml?.applicationClassName
            ?: archive?.applicationClass
        val labelRes = xml?.labelRes ?: archive?.labelRes ?: 0
        val iconRes = xml?.iconRes ?: archive?.iconRes ?: 0
        val applicationThemeRes = xml?.applicationThemeRes
            ?: archive?.applicationThemeRes ?: 0

        // -- Activities: XML entries win, archive fills the gaps -----------
        val activities = LinkedHashMap<String, ManifestParser.GuestActivity>()
        archive?.activities?.forEach { a ->
            if (a.className.isNotBlank()) {
                activities[a.className] = ManifestParser.GuestActivity(
                    className = a.className,
                    themeRes = a.themeRes,
                    launchMode = a.launchMode.ifBlank { "standard" },
                    isLauncher = false
                )
            }
        }
        xml?.activities?.forEach { a ->
            if (a.className.isNotBlank()) {
                activities[a.className] = a
            }
        }

        // -- Launcher resolution chain -------------------------------------
        val stored = storedLauncherClass?.takeIf { it.isNotBlank() }
        if (stored != null && activities.containsKey(stored)) {
            // Upgrade the stored launcher's flag in place.
            activities[stored] = activities.getValue(stored).copy(isLauncher = true)
        }

        val resolvedLauncher = xml?.launcherActivity?.className
            ?: stored
            ?: xml?.firstActivity?.className
            ?: archive?.activities?.firstOrNull()?.className

        if (activities.isEmpty() && resolvedLauncher == null) {
            return null // nothing runnable from any source
        }

        // Synthetic entry when even the archive parser failed but a stored
        // launcher exists — the activity class still loads, with defaults.
        if (activities.isEmpty() && resolvedLauncher != null) {
            activities[resolvedLauncher] = ManifestParser.GuestActivity(
                className = resolvedLauncher,
                themeRes = 0,
                launchMode = "standard",
                isLauncher = true
            )
        } else if (resolvedLauncher != null && !activities.containsKey(resolvedLauncher)) {
            // Launcher named but not declared as an activity (rare alias
            // edge) — add a synthetic entry so it can be hosted.
            activities[resolvedLauncher] = ManifestParser.GuestActivity(
                className = resolvedLauncher,
                themeRes = 0,
                launchMode = "standard",
                isLauncher = true
            )
        }

        return MergedManifest(
            applicationClassName = applicationClass,
            labelRes = labelRes,
            iconRes = iconRes,
            applicationThemeRes = applicationThemeRes,
            activities = activities.values.toList()
        )
    }
}
