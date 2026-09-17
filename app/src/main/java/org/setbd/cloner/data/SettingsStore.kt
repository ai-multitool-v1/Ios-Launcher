package org.setbd.cloner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "setbd_cloner_settings")

/** Appearance mode of the launcher UI. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * Local settings persisted with DataStore.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FALLBACK_LAUNCH = booleanPreferencesKey("fallback_launch")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        val TELEGRAM_URL = stringPreferencesKey("telegram_url")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK)
    }

    /**
     * OPT-IN compatibility behavior: when the container engine cannot
     * virtualize a guest, open the original installed app instead of
     * showing an explicit error. Disabled by default — the app must never
     * silently swap itself for the original.
     */
    val fallbackLaunch: Flow<Boolean> = context.dataStore.data.map { it[Keys.FALLBACK_LAUNCH] ?: false }

    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPDATE_CHECK] ?: true }

    val skippedVersion: Flow<String> = context.dataStore.data.map { it[Keys.SKIPPED_VERSION] ?: "" }

    val firstRunDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.FIRST_RUN_DONE] ?: false }

    /** Editable Telegram contact shown on splash and in Settings → About. */
    val telegramUrl: Flow<String> = context.dataStore.data.map { it[Keys.TELEGRAM_URL] ?: BrandingDefaults.TELEGRAM_URL }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setFallbackLaunch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FALLBACK_LAUNCH] = enabled }
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSkippedVersion(version: String) {
        context.dataStore.edit { it[Keys.SKIPPED_VERSION] = version }
    }

    suspend fun setFirstRunDone() {
        context.dataStore.edit { it[Keys.FIRST_RUN_DONE] = true }
    }

    suspend fun setTelegramUrl(url: String) {
        context.dataStore.edit { it[Keys.TELEGRAM_URL] = url.trim() }
    }

    suspend fun currentFallbackLaunch(): Boolean = fallbackLaunch.first()

    /** Editable branding defaults (kept out of resources so they ship in one place). */
    object BrandingDefaults {
        const val DEVELOPER = "SETBD"
        const val TELEGRAM_URL = "https://t.me/setbd_dev"
        const val TELEGRAM_LABEL = "@setbd_dev"
    }
}
