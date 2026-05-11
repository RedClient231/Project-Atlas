package com.atlas.virtualspace.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.atlas.virtualspace.core.pm.VirtualAppInfo

/**
 * Room database that persists virtual app registrations and runtime logs.
 *
 * **Schema export:** Room will write the schema JSON to
 * `app/schemas/com.atlas.virtualspace.data.database.AppDatabase/1.json`
 * when `exportSchema = true`. This directory must be listed in the
 * Gradle `room.schemaLocation` option.
 */
@Database(
    entities = [VirtualAppInfo::class, AppLogEntry::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun virtualAppDao(): VirtualAppDao
    abstract fun appLogDao(): AppLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "atlas_virtual_space.db"

        /**
         * Returns the singleton [AppDatabase] instance, creating it on first
         * access.
         *
         * Thread-safety is guaranteed by the double-checked locking pattern
         * with a @Volatile field.
         *
         * @param context Application or activity context (used to locate the
         *                database file on internal storage).
         */
        fun create(context: Context): AppDatabase {
            // Fast path: already initialised
            INSTANCE?.let { return it }

            // Slow path: synchronised creation
            return synchronized(this) {
                INSTANCE?.let { return it }

                val db = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .setJournalMode(JournalMode.AUTOMATIC)
                    .build()

                INSTANCE = db
                db
            }
        }

        /**
         * Forces the singleton to be cleared. Intended for tests only.
         */
        fun destroyInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /**
         * Migration 1 → 2: adds the [VirtualAppInfo.isInstalledOnDevice] column.
         *
         * Existing rows default to 0 (false) — apps already imported before
         * this migration will be asked to install once on next launch, after
         * which the flag is set and never asked again.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE virtual_apps ADD COLUMN isInstalledOnDevice INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
