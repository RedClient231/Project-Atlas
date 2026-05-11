package com.atlas.virtualspace.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.util.Log

/**
 * Represents a single log entry captured from a virtual app's runtime.
 *
 * Log entries are persisted in the Room database so that the user can
 * review them later (e.g. in the in-app logcat viewer) even after the
 * virtual process has terminated.
 */
@Entity(tableName = "app_logs")
data class AppLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Package name of the virtual app that produced this log. */
    val packageName: String,
    /**
     * Log level, one of [Log.VERBOSE], [Log.DEBUG], [Log.INFO],
     * [Log.WARN], [Log.ERROR], or [Log.ASSERT].
     */
    val level: Int,
    /** Tag string as supplied to the android.util.Log call. */
    val tag: String,
    /** The log message body. */
    val message: String,
    /** Wall-clock millis since epoch when the log was captured. */
    val timestamp: Long,
    /** Name of the thread that produced the log. */
    val threadName: String
)
