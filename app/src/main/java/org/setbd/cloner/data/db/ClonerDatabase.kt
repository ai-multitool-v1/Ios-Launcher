package org.setbd.cloner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CloneEntity::class],
    version = 3,
    exportSchema = false
)
abstract class ClonerDatabase : RoomDatabase() {

    abstract fun cloneDao(): CloneDao

    companion object {
        @Volatile
        private var instance: ClonerDatabase? = null

        /** v1 → v2: split APK support (App Bundle packages). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clones ADD COLUMN split_apk_paths TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v2 → v3: import-time launcher capture (robust engine entry). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clones ADD COLUMN launcher_class TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun get(context: Context): ClonerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClonerDatabase::class.java,
                    "setbd_cloner.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        /** Encodes/decodes the "|" separated split path column. */
        fun encodeSplitPaths(paths: List<String>): String = paths.joinToString("|")

        fun decodeSplitPaths(encoded: String): List<String> =
            if (encoded.isBlank()) emptyList() else encoded.split('|').filter { it.isNotBlank() }
    }
}
