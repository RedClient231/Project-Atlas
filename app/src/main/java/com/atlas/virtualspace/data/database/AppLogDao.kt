package com.atlas.virtualspace.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the [AppLogEntry] table.
 *
 * All read operations that power the UI return [Flow]s so the logcat
 * view updates in real-time. Write operations are suspend functions
 * intended for use from a background coroutine.
 */
@Dao
interface AppLogDao {

    /**
     * Inserts a single log entry.
     *
     * Callers should batch inserts (e.g. via a channel or buffer) to avoid
     * excessive write amplification when a virtual app produces high-volume
     * log output.
     */
    @Insert
    suspend fun insert(entry: AppLogEntry)

    /**
     * Returns all log entries for [pkg], newest first.
     *
     * The [Flow] emits a new list whenever rows are inserted or deleted.
     */
    @Query("SELECT * FROM app_logs WHERE packageName = :pkg ORDER BY timestamp DESC")
    fun getByPackage(pkg: String): Flow<List<AppLogEntry>>

    /**
     * Returns all log entries with level >= [minLevel], newest first.
     *
     * Useful for a filtered "errors only" view.
     *
     * Level constants:
     * - [android.util.Log.VERBOSE] = 2
     * - [android.util.Log.DEBUG]   = 3
     * - [android.util.Log.INFO]    = 4
     * - [android.util.Log.WARN]    = 5
     * - [android.util.Log.ERROR]   = 6
     * - [android.util.Log.ASSERT]  = 7
     */
    @Query("SELECT * FROM app_logs WHERE level >= :minLevel ORDER BY timestamp DESC")
    fun getByMinLevel(minLevel: Int): Flow<List<AppLogEntry>>

    /**
     * Returns the most recent [limit] log entries across all packages.
     *
     * Defaults to 500 entries to keep the UI responsive on devices with
     * limited memory.
     */
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 500): Flow<List<AppLogEntry>>

    /**
     * Deletes all log entries for [pkg].
     *
     * Typically called when a virtual app is uninstalled.
     */
    @Query("DELETE FROM app_logs WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    /**
     * Deletes all log entries older than [cutoff] (epoch millis).
     *
     * Used by a periodic cleanup job to prevent unbounded database growth.
     */
    @Query("DELETE FROM app_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
