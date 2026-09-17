package org.setbd.cloner.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CloneDao {

    @Query("SELECT * FROM clones ORDER BY sort_order ASC, clone_id ASC")
    fun observeAll(): Flow<List<CloneEntity>>

    @Query("SELECT * FROM clones ORDER BY sort_order ASC, clone_id ASC")
    suspend fun getAll(): List<CloneEntity>

    @Query("SELECT * FROM clones WHERE clone_id = :cloneId LIMIT 1")
    suspend fun getById(cloneId: Long): CloneEntity?

    @Query("SELECT COUNT(*) FROM clones WHERE package_name = :packageName")
    suspend fun countForPackage(packageName: String): Int

    @Query("SELECT MAX(sort_order) FROM clones")
    suspend fun maxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CloneEntity): Long

    @Update
    suspend fun update(entity: CloneEntity)

    @Delete
    suspend fun delete(entity: CloneEntity)

    @Query("DELETE FROM clones WHERE clone_id = :cloneId")
    suspend fun deleteById(cloneId: Long)

    @Query("UPDATE clones SET last_launch_time = :time, lifecycle_state = :state WHERE clone_id = :cloneId")
    suspend fun markLaunched(cloneId: Long, time: Long, state: String)

    @Query("UPDATE clones SET lifecycle_state = :state WHERE clone_id = :cloneId")
    suspend fun updateState(cloneId: Long, state: String)
}
