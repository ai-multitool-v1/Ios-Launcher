package org.setbd.cloner.data

import org.setbd.cloner.core.CloneLifecycleManager
import org.setbd.cloner.core.VirtualStorageManager
import org.setbd.cloner.data.db.CloneDao
import org.setbd.cloner.data.db.CloneEntity
import org.setbd.cloner.data.db.ClonerDatabase
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository owning the clone registry: create, rename, reorder, duplicate,
 * enable/disable and delete operations, plus mapping between entities and
 * the domain model.
 */
class CloneRepository(
    private val dao: CloneDao,
    private val storage: VirtualStorageManager,
    private val lifecycleManager: CloneLifecycleManager
) {

    val clones: Flow<List<CloneInfo>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getClone(cloneId: Long): CloneInfo? = dao.getById(cloneId)?.toModel()

    suspend fun cloneCountForPackage(packageName: String): Int = dao.countForPackage(packageName)

    /**
     * Registers an imported APK copy as a new clone. The base APK file must
     * already be staged inside the importer's cache; every split APK (App
     * Bundle config splits) is copied from wherever it currently lives —
     * /data/app for installed packages, the staging dir for bundles.
     */
    suspend fun registerClone(
        packageName: String,
        appLabel: String,
        versionName: String,
        apkFile: File,
        iconPng: ByteArray?,
        splitFiles: List<File> = emptyList()
    ): CloneInfo = withContext(Dispatchers.IO) {
        val index = dao.maxSortOrder()?.plus(1) ?: 0
        val storagePath = storage.createCloneDirs().absolutePath
        if (iconPng != null) {
            runCatching { storage.writeOriginalIcon(storagePath, iconPng) }
                .onFailure { ClonerLog.w(TAG, "icon persist failed", it) }
        }
        val storedApks = storage.relocateSplitApks(storagePath, apkFile, splitFiles)
        val movedApk = storedApks.first()
        val storedSplits = storedApks.drop(1)
        val count = dao.countForPackage(packageName)
        val displayName = if (appLabel.isBlank()) packageName else appLabel
        val entity = CloneEntity(
            packageName = packageName,
            appLabel = appLabel,
            displayName = displayName,
            apkPath = movedApk.absolutePath,
            splitApkPaths = ClonerDatabase.encodeSplitPaths(storedSplits.map { it.absolutePath }),
            storagePath = storagePath,
            versionName = versionName,
            creationTime = System.currentTimeMillis(),
            sortOrder = index,
            lifecycleState = CloneLifecycle.CREATED.name
        )
        val id = dao.insert(entity)
        lifecycleManager.report(id, CloneLifecycle.CREATED)
        if (count > 0) {
            entity.copy(cloneId = id).toModel().copy(displayName = "$displayName ${count + 1}")
        } else {
            entity.copy(cloneId = id).toModel()
        }
    }

    suspend fun rename(cloneId: Long, name: String) = withContext(Dispatchers.IO) {
        dao.getById(cloneId)?.let { dao.update(it.copy(displayName = name.trim())) }
    }

    suspend fun setCustomIcon(cloneId: Long, iconPath: String?) = withContext(Dispatchers.IO) {
        dao.getById(cloneId)?.let { dao.update(it.copy(customIconPath = iconPath)) }
    }

    suspend fun setEnabled(cloneId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.getById(cloneId)?.let { dao.update(it.copy(enabled = enabled)) }
    }

    suspend fun reorder(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        orderedIds.forEachIndexed { index, id ->
            dao.getById(id)?.let { dao.update(it.copy(sortOrder = index)) }
        }
    }

    suspend fun markLaunched(cloneId: Long) = withContext(Dispatchers.IO) {
        dao.markLaunched(cloneId, System.currentTimeMillis(), CloneLifecycle.STARTING.name)
    }

    suspend fun updateLifecycle(cloneId: Long, state: CloneLifecycle) = withContext(Dispatchers.IO) {
        dao.updateState(cloneId, state.name)
    }

    suspend fun markDegraded(cloneId: Long, degraded: Boolean) = withContext(Dispatchers.IO) {
        dao.getById(cloneId)?.let { dao.update(it.copy(degraded = degraded)) }
    }

    /**
     * Deletes a clone together with its whole isolated storage namespace.
     */
    suspend fun deleteClone(cloneId: Long) = withContext(Dispatchers.IO) {
        dao.getById(cloneId)?.let { entity ->
            lifecycleManager.remove(cloneId)
            runCatching { storage.wipeClone(entity.storagePath) }
                .onFailure { ClonerLog.w(TAG, "wipe failed for clone $cloneId", it) }
            dao.delete(entity)
        }
    }

    /**
     * Duplicates a clone: new id, fresh storage namespace, FULL APK set
     * (base + splits) copied and the guest data directories cloned so the
     * new instance starts as a copy.
     */
    suspend fun duplicateClone(cloneId: Long): CloneInfo? = withContext(Dispatchers.IO) {
        val source = dao.getById(cloneId) ?: return@withContext null
        val storagePath = storage.createCloneDirs().absolutePath
        val copiedApk = storage.copyApkSet(source.storagePath, storagePath)
        val copiedSplits = storage.apkSet(storagePath)
            .drop(1) // base first, splits after — copyApkSet wrote all, list them
            .map { it.absolutePath }
        runCatching { storage.copyGuestData(File(source.storagePath), File(storagePath)) }
            .onFailure { ClonerLog.w(TAG, "guest data copy incomplete", it) }
        val index = dao.maxSortOrder()?.plus(1) ?: 0
        val entity = source.copy(
            cloneId = 0L,
            apkPath = copiedApk.absolutePath,
            splitApkPaths = ClonerDatabase.encodeSplitPaths(copiedSplits),
            storagePath = storagePath,
            displayName = "${source.displayName} (copy)",
            creationTime = System.currentTimeMillis(),
            lastLaunchTime = 0L,
            sortOrder = index,
            lifecycleState = CloneLifecycle.CREATED.name,
            degraded = false
        )
        val id = dao.insert(entity)
        lifecycleManager.report(id, CloneLifecycle.CREATED)
        entity.copy(cloneId = id).toModel()
    }

    suspend fun storageUsage(storagePath: String): Long = withContext(Dispatchers.IO) {
        storage.usageBytes(File(storagePath))
    }

    suspend fun clearGuestData(cloneId: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getById(cloneId) ?: return@withContext
        storage.clearGuestData(entity.storagePath)
    }

    private fun CloneEntity.toModel() = CloneInfo(
        cloneId = cloneId,
        originalPackageName = packageName,
        appLabel = appLabel,
        displayName = displayName,
        apkPath = apkPath,
        splitApkPaths = ClonerDatabase.decodeSplitPaths(splitApkPaths),
        storagePath = storagePath,
        customIconPath = customIconPath,
        versionName = versionName,
        creationTime = creationTime,
        lastLaunchTime = lastLaunchTime,
        enabled = enabled,
        sortOrder = sortOrder,
        lifecycleState = runCatching { CloneLifecycle.valueOf(lifecycleState) }
            .getOrDefault(CloneLifecycle.CREATED),
        degraded = degraded
    )

    private companion object {
        const val TAG = "CloneRepository"
    }
}
