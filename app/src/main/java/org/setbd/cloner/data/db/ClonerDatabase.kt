package org.setbd.cloner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CloneEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ClonerDatabase : RoomDatabase() {

    abstract fun cloneDao(): CloneDao

    companion object {
        @Volatile
        private var instance: ClonerDatabase? = null

        fun get(context: Context): ClonerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClonerDatabase::class.java,
                    "setbd_cloner.db"
                ).build().also { instance = it }
            }
    }
}
