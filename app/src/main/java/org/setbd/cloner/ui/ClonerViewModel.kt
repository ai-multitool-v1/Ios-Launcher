package org.setbd.cloner.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.setbd.cloner.ClonerApp
import org.setbd.cloner.core.ApkImporter
import org.setbd.cloner.core.LaunchResult
import org.setbd.cloner.data.SettingsStore
import org.setbd.cloner.data.ThemeMode
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.update.UpdateManager

/**
 * Single shared ViewModel for the launcher UI.
 */
class ClonerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ClonerApp).container

    val clones: StateFlow<List<CloneInfo>> = container.repository.clones
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> = container.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    val fallbackLaunch: StateFlow<Boolean> = container.settings.fallbackLaunch
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoUpdateCheck: StateFlow<Boolean> = container.settings.autoUpdateCheck
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val telegramUrl: StateFlow<String> = container.settings.telegramUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.BrandingDefaults.TELEGRAM_URL)

    // -- Import state ------------------------------------------------------

    sealed class ImportState {
        data object Idle : ImportState()
        data object Busy : ImportState()
        data class Success(val clone: CloneInfo) : ImportState()
        data class Failure(val message: String) : ImportState()
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // -- Update state ------------------------------------------------------

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data object UpToDate : UpdateState()
        data class Available(val update: UpdateManager.UpdateInfo) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastLaunchFeedback = MutableStateFlow<String?>(null)
    val lastLaunchFeedback: StateFlow<String?> = _lastLaunchFeedback.asStateFlow()

    // ------------------------------------------------------------------
    // Clone actions
    // ------------------------------------------------------------------

    fun launchClone(clone: CloneInfo) {
        viewModelScope.launch {
            val result = container.launchCoordinator.launch(clone)
            _lastLaunchFeedback.value = when (result) {
                LaunchResult.Started -> "Launched in container"
                LaunchResult.FallbackStarted -> "Compatibility mode used"
                is LaunchResult.Failed -> "Launch failed: ${result.reason}"
            }
        }
    }

    fun launchFromShortcut(cloneId: Long) {
        viewModelScope.launch {
            container.repository.getClone(cloneId)?.let { launchClone(it) }
        }
    }

    fun renameClone(clone: CloneInfo, name: String) {
        viewModelScope.launch {
            container.repository.rename(clone.cloneId, name)
            container.repository.getClone(clone.cloneId)?.let { container.shortcuts.syncShortcut(it) }
        }
    }

    fun duplicateClone(clone: CloneInfo) {
        viewModelScope.launch {
            container.repository.duplicateClone(clone.cloneId)
        }
    }

    fun deleteClone(clone: CloneInfo) {
        viewModelScope.launch {
            container.engine.unloadRuntime(clone.cloneId)
            container.repository.deleteClone(clone.cloneId)
            container.shortcuts.removeShortcut(clone.cloneId)
        }
    }

    fun setEnabled(clone: CloneInfo, enabled: Boolean) {
        viewModelScope.launch {
            container.repository.setEnabled(clone.cloneId, enabled)
        }
    }

    fun pinShortcut(clone: CloneInfo) {
        viewModelScope.launch { container.shortcuts.requestPinShortcut(clone) }
    }

    fun clearGuestData(clone: CloneInfo) {
        viewModelScope.launch { container.repository.clearGuestData(clone.cloneId) }
    }

    fun setCustomIcon(clone: CloneInfo, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = container.storage.customIconFile(clone.storagePath)
                target.parentFile?.mkdirs()
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                container.repository.setCustomIcon(clone.cloneId, target.absolutePath)
                container.repository.getClone(clone.cloneId)?.let { container.shortcuts.syncShortcut(it) }
            } catch (t: Throwable) {
                _importState.value = ImportState.Failure("icon change failed: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    fun importInstalledPackage(packageName: String) {
        viewModelScope.launch {
            _importState.value = ImportState.Busy
            val result = container.importer.importInstalledPackage(packageName)
            applyImportResult(result)
        }
    }

    fun importApkFile(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Busy
            val result = container.importer.importApkUri(uri)
            applyImportResult(result)
        }
    }

    private suspend fun applyImportResult(result: ApkImporter.ImportResult) {
        when (result) {
            is ApkImporter.ImportResult.Success -> {
                container.shortcuts.syncShortcut(result.clone)
                _importState.value = ImportState.Success(result.clone)
            }
            is ApkImporter.ImportResult.Error ->
                _importState.value = ImportState.Failure(result.message)
        }
    }

    fun dismissImportState() {
        _importState.value = ImportState.Idle
    }

    suspend fun installedApps(): List<ApkImporter.InstalledApp> =
        withContext(Dispatchers.IO) { container.importer.listInstalledApps() }

    // ------------------------------------------------------------------
    // Icons (grid rendering)
    // ------------------------------------------------------------------

    suspend fun cloneIcon(clone: CloneInfo): Bitmap? =
        withContext(Dispatchers.Default) { container.shortcuts.iconBitmap(clone) }

    // ------------------------------------------------------------------
    // Clone info
    // ------------------------------------------------------------------

    suspend fun cloneInfo(cloneId: Long): CloneInfo? = container.repository.getClone(cloneId)

    suspend fun storageUsage(clone: CloneInfo): Long = container.repository.storageUsage(clone.storagePath)

    fun guestPermissions(clone: CloneInfo): List<String> =
        container.permissions.requestedDangerousPermissions(clone)

    fun missingPermissions(clone: CloneInfo): List<String> =
        container.permissions.missingDangerousPermissions(clone)

    /** Batched host battery still missing (requested once for ALL clones). */
    fun hostPermissionsToRequest(): List<String> =
        container.permissions.hostRequestPermissions()

    fun hostGrantedCount(): Int = container.permissions.hostGrantedCount()

    fun hostBatchSize(): Int = container.permissions.hostBatchSize()

    fun allHostPermissionsGranted(): Boolean = container.permissions.allHostPermissionsGranted()

    fun requestPermissions(activity: android.app.Activity, permissions: List<String>) =
        container.permissions.requestPermissions(activity, permissions)

    fun requestAllHostPermissions(activity: android.app.Activity) =
        container.permissions.requestAllHostPermissions(activity)

    // ------------------------------------------------------------------
    // Settings & updates
    // ------------------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(mode) }
    }

    fun setFallbackLaunch(enabled: Boolean) {
        viewModelScope.launch { container.settings.setFallbackLaunch(enabled) }
    }

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoUpdateCheck(enabled) }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            when (val result = container.updateManager.checkLatest()) {
                is UpdateManager.CheckResult.UpToDate -> _updateState.value = UpdateState.UpToDate
                is UpdateManager.CheckResult.Available -> _updateState.value = UpdateState.Available(result.update)
                is UpdateManager.CheckResult.Error -> _updateState.value = UpdateState.Error(result.message)
            }
        }
    }

    fun downloadUpdate(update: UpdateManager.UpdateInfo) {
        container.updateManager.enqueueDownload(update)
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    fun consumeLaunchFeedback() {
        _lastLaunchFeedback.value = null
    }
}
