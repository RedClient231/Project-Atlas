package com.atlas.virtualspace.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.atlas.virtualspace.core.pm.VirtualAppInfo
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the [VirtualAppInfo] table.
 *
 * Query methods return [Flow]s for reactive UI updates. Single-shot
 * lookups are suspend functions for use in one-shot repository calls.
 */
@Dao
interface VirtualAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: VirtualAppInfo)

    @Update
    suspend fun update(app: VirtualAppInfo)

    @Delete
    suspend fun delete(app: VirtualAppInfo)

    /**
     * Returns all installed virtual apps sorted alphabetically by name.
     *
     * The [Flow] re-emits whenever any row is inserted, updated, or deleted.
     */
    @Query("SELECT * FROM virtual_apps ORDER BY appName ASC")
    fun getAll(): Flow<List<VirtualAppInfo>>

    /**
     * Looks up a single app by its package name.
     *
     * @return The matching [VirtualAppInfo], or null if not installed.
     */
    @Query("SELECT * FROM virtual_apps WHERE packageName = :pkg")
    suspend fun getByPackage(pkg: String): VirtualAppInfo?

    /**
     * Deletes the row for [pkg]. This is more efficient than loading the
     * entity first and calling [delete].
     */
    @Query("DELETE FROM virtual_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    /**
     * Atomically increments [VirtualAppInfo.launchCount] and updates
     * [VirtualAppInfo.lastLaunchTime] without overwriting other columns.
     *
     * @param time  The new last-launch timestamp (epoch millis).
     */
    @Query("UPDATE virtual_apps SET lastLaunchTime = :time, launchCount = launchCount + 1 WHERE packageName = :pkg")
    suspend fun updateLaunchStats(pkg: String, time: Long)

    /**
     * Marks the app as installed on the real device.
     *
     * Called by [VirtualStubActivity] after:
     * - A Shizuku silent install succeeds, OR
     * - The user accepts the system package installer dialog.
     *
     * Once this is set to true, subsequent Launch taps skip the install
     * dialog and go straight to [tryDirectLaunch].
     */
    @Query("UPDATE virtual_apps SET isInstalledOnDevice = 1 WHERE packageName = :pkg")
    suspend fun markInstalledOnDevice(pkg: String)

    /**
     * Returns the total number of installed virtual apps.
     */
    @Query("SELECT COUNT(*) FROM virtual_apps")
    suspend fun getCount(): Int
}
