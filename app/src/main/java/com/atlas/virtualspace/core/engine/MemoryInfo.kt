package com.atlas.virtualspace.core.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Represents memory information for a virtual process or the engine itself.
 *
 * Provides total, used, and available memory in megabytes, along with
 * a utility to compute usage percentage. Instances are typically constructed
 * via the [fromSystem] factory which reads real memory data from the
 * Android [ActivityManager.MemoryInfo].
 */
data class MemoryInfo(
    val totalMb: Long,
    val usedMb: Long,
    val availableMb: Long
) {

    /**
     * Returns memory usage as a percentage [0..100].
     * Returns 0 when [totalMb] is 0 to avoid division by zero.
     */
    fun usagePercent(): Int {
        if (totalMb <= 0L) return 0
        val percent = (usedMb * 100.0 / totalMb).toInt()
        return percent.coerceIn(0, 100)
    }

    companion object {

        private const val BYTES_PER_MB: Long = 1024L * 1024L

        /**
         * Creates a [MemoryInfo] from the system's [ActivityManager.MemoryInfo].
         *
         * On API 34+ [ActivityManager.MemoryInfo.totalMem] is available; on older
         * APIs it is also available (added in API 16). We compute:
         * - **totalMb** – total runtime memory available to the process
         * - **availableMb** – memory that the system can free if needed
         * - **usedMb** – total minus available
         */
        fun fromSystem(context: Context): MemoryInfo {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L)

            val sysInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(sysInfo)

            val totalMb = sysInfo.totalMem / BYTES_PER_MB
            val availableMb = sysInfo.availMem / BYTES_PER_MB
            val usedMb = totalMb - availableMb

            return MemoryInfo(
                totalMb = totalMb,
                usedMb = usedMb.coerceAtLeast(0L),
                availableMb = availableMb
            )
        }

        /**
         * Creates a [MemoryInfo] from per-process memory statistics read from
         * `/proc/[pid]/status` and the `Debug` API.
         *
         * On API 28+ we use [android.os.Debug.MemoryInfo] via
         * [ActivityManager.getProcessMemoryInfo]; on older APIs we fall back
         * to reading VmRSS from procfs.
         */
        fun fromProcess(context: Context, pid: Int): MemoryInfo {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L)

            return try {
                val processMemInfos = activityManager.getProcessMemoryInfo(intArrayOf(pid))
                if (processMemInfos.isNullOrEmpty()) {
                    return readFromProcFs(pid)
                }

                val memInfo = processMemInfos[0]
                val usedKb = memInfo.totalPss.toLong()
                val usedMb = usedKb / 1024L

                val sysInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(sysInfo)
                val totalMb = sysInfo.totalMem / BYTES_PER_MB
                val availableMb = (sysInfo.availMem / BYTES_PER_MB).coerceAtLeast(0L)

                MemoryInfo(
                    totalMb = totalMb,
                    usedMb = usedMb.coerceAtLeast(0L),
                    availableMb = availableMb
                )
            } catch (_: Exception) {
                readFromProcFs(pid)
            }
        }

        /**
         * Reads VmRSS from `/proc/[pid]/status` as a fallback.
         */
        private fun readFromProcFs(pid: Int): MemoryInfo {
            return try {
                val statusLines = java.io.File("/proc/$pid/status").readLines()
                val vmRssKb = statusLines
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull()
                    ?: 0L

                val usedMb = vmRssKb / 1024L
                // Without system context we cannot determine total; return what we know.
                MemoryInfo(
                    totalMb = usedMb.coerceAtLeast(1L),
                    usedMb = usedMb,
                    availableMb = 0L
                )
            } catch (_: Exception) {
                MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L)
            }
        }
    }
}
