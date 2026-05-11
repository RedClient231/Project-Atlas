package com.atlas.virtualspace.core.engine

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import timber.log.Timber

/**
 * Tracks metadata and state for a single virtual app process.
 *
 * Designed as an immutable data class – state transitions produce new
 * instances via [copy].  This is thread-safe by virtue of being
 * read-only after construction.
 *
 * @property pid          PID of the virtual process; 0 until the process starts.
 * @property packageName  Package name of the virtual app.
 * @property processName  Process name (usually same as [packageName]).
 * @property uid          Virtual UID assigned by the engine.
 * @property startTime    Epoch millis when the process was created.
 * @property is64Bit      Whether the process runs in 64-bit mode.
 * @property state        Current lifecycle state.
 * @property memoryInfo   Latest memory usage snapshot.
 * @property threadCount  Number of threads in the process.
 */
data class ProcessRecord(
    val pid: Int,
    val packageName: String,
    val processName: String,
    val uid: Int,
    val startTime: Long,
    val is64Bit: Boolean,
    val state: ProcessState,
    val memoryInfo: MemoryInfo,
    val threadCount: Int,
) : Parcelable {

    /** Returns `true` if the process is in a running or starting state. */
    fun isAlive(): Boolean = state == ProcessState.RUNNING || state == ProcessState.STARTING

    /**
     * Refreshes memory info for this process from the system.
     * Returns a new [ProcessRecord] with updated [memoryInfo].
     */
    fun refreshMemoryInfo(context: Context): ProcessRecord {
        val newInfo = if (pid > 0) {
            MemoryInfo.fromProcess(context, pid)
        } else {
            memoryInfo
        }
        return copy(memoryInfo = newInfo)
    }

    /**
     * Refreshes thread count by reading `/proc/[pid]/status`.
     * Returns a new [ProcessRecord] with updated [threadCount].
     */
    fun refreshThreadCount(): ProcessRecord {
        if (pid <= 0) return this
        return try {
            val statusLines = java.io.File("/proc/$pid/status").readLines()
            val threads = statusLines
                .firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: threadCount
            copy(threadCount = threads)
        } catch (e: Exception) {
            Timber.d(e, "Failed to read thread count for pid %d", pid)
            this
        }
    }

    // ─── Parcelable ────────────────────────────────────────────

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(pid)
        parcel.writeString(packageName)
        parcel.writeString(processName)
        parcel.writeInt(uid)
        parcel.writeLong(startTime)
        parcel.writeInt(if (is64Bit) 1 else 0)
        parcel.writeInt(state.ordinal)
        parcel.writeParcelable(memoryInfo, flags)
        parcel.writeInt(threadCount)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProcessRecord> {
        override fun createFromParcel(parcel: Parcel): ProcessRecord {
            val pid = parcel.readInt()
            val packageName = parcel.readString() ?: ""
            val processName = parcel.readString() ?: ""
            val uid = parcel.readInt()
            val startTime = parcel.readLong()
            val is64Bit = parcel.readInt() != 0
            val state = ProcessState.entries[parcel.readInt()]
            val memoryInfo = parcel.readParcelable(MemoryInfo::class.java.classLoader)
                ?: MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L)
            val threadCount = parcel.readInt()
            return ProcessRecord(
                pid = pid,
                packageName = packageName,
                processName = processName,
                uid = uid,
                startTime = startTime,
                is64Bit = is64Bit,
                state = state,
                memoryInfo = memoryInfo,
                threadCount = threadCount,
            )
        }

        override fun newArray(size: Int): Array<ProcessRecord?> = arrayOfNulls(size)
    }

    override fun toString(): String =
        "ProcessRecord(packageName=$packageName, uid=$uid, pid=$pid, state=$state)"
}
