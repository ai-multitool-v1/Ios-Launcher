package org.setbd.cloner.di

import android.content.Context
import org.setbd.cloner.core.ApkImporter
import org.setbd.cloner.core.CloneLifecycleManager
import org.setbd.cloner.core.LaunchCoordinator
import org.setbd.cloner.core.PermissionMediator
import org.setbd.cloner.core.ShortcutManagerImpl
import org.setbd.cloner.core.VirtualStorageManager
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.SettingsStore
import org.setbd.cloner.data.db.ClonerDatabase
import org.setbd.cloner.engine.VirtualEngine
import org.setbd.cloner.update.UpdateManager

/**
 * Manual dependency container (the Google-recommended lightweight pattern —
 * no codegen, one place to see every collaborator).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val storage: VirtualStorageManager = VirtualStorageManager(appContext)

    val lifecycleManager: CloneLifecycleManager = CloneLifecycleManager()

    val database: ClonerDatabase = ClonerDatabase.get(appContext)

    val repository: CloneRepository = CloneRepository(database.cloneDao(), storage, lifecycleManager)

    val settings: SettingsStore = SettingsStore(appContext)

    val engine: VirtualEngine = VirtualEngine.getInstance()

    val importer: ApkImporter = ApkImporter(appContext, repository)

    val shortcuts: ShortcutManagerImpl = ShortcutManagerImpl(appContext, repository)

    val launchCoordinator: LaunchCoordinator =
        LaunchCoordinator(appContext, engine, repository, lifecycleManager, settings)

    val permissions: PermissionMediator = PermissionMediator(appContext)

    val updateManager: UpdateManager = UpdateManager(appContext)

    /**
     * Wires the container engine. Must run before the first clone launch —
     * called from [org.setbd.cloner.ClonerApp.onCreate].
     */
    fun initializeEngine() {
        engine.initialize(appContext, storage, repository, lifecycleManager)
    }
}
