package com.atlas.virtualspace.feature.logcat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atlas.virtualspace.data.database.AppDatabase
import com.atlas.virtualspace.data.database.AppLogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─── UI state models ──────────────────────────────────────────────────────────

data class LogcatFilter(
    val selectedPackage: String? = null,
    val minLevel: Int = android.util.Log.VERBOSE,
    val searchQuery: String = "",
)

data class LogcatUiState(
    val entries: List<AppLogEntry> = emptyList(),
    val filter: LogcatFilter = LogcatFilter(),
    val isCapturing: Boolean = false,
    val isPaused: Boolean = false,
    val availablePackages: List<String> = emptyList(),
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@dagger.hilt.android.lifecycle.HiltViewModel
class LogcatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
) : ViewModel() {

    private val logDao = database.appLogDao()

    // ── Filter state ─────────────────────────────────────────────────────

    private val _filter = MutableStateFlow(LogcatFilter())
    val filter: StateFlow<LogcatFilter> = _filter.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // ── Reactive entries from database ───────────────────────────────────

    private val dbEntries = logDao.getRecent(500)

    val uiState: StateFlow<LogcatUiState> = combine(
        dbEntries,
        _filter,
        _isCapturing,
        _isPaused,
    ) { entries, filter, capturing, paused ->
        val filtered = entries.filter { entry ->
            // Package filter
            (filter.selectedPackage == null || entry.packageName == filter.selectedPackage) &&
            // Level filter
            entry.level >= filter.minLevel &&
            // Search filter
            (filter.searchQuery.isBlank() ||
                    entry.message.contains(filter.searchQuery, ignoreCase = true) ||
                    entry.tag.contains(filter.searchQuery, ignoreCase = true))
        }

        val packages = entries.map { it.packageName }.distinct().sorted()

        LogcatUiState(
            entries = filtered,
            filter = filter,
            isCapturing = capturing,
            isPaused = paused,
            availablePackages = packages,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LogcatUiState(),
    )

    // ── Public API ───────────────────────────────────────────────────────

    fun startCapture() {
        LogcatService.start(appContext)
        _isCapturing.value = true
        _isPaused.value = false
    }

    fun stopCapture() {
        LogcatService.stop(appContext)
        _isCapturing.value = false
    }

    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                logDao.deleteOlderThan(Long.MAX_VALUE)
            }
        }
    }

    fun exportLogs(): File? {
        return try {
            val entries = uiState.value.entries
            if (entries.isEmpty()) return null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(
                appContext.getExternalFilesDir(null),
                "atlas_logcat_$timestamp.txt",
            )

            FileOutputStream(exportFile).bufferedWriter().use { writer ->
                writer.write("=== Atlas Logcat Export ===\n")
                writer.write(
                    "Exported: ${
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    }\n"
                )
                writer.write("Entries: ${entries.size}\n")
                writer.write("================================\n\n")

                val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                for (entry in entries.reversed()) { // Oldest first in export
                    val levelChar = when (entry.level) {
                        android.util.Log.VERBOSE -> "V"
                        android.util.Log.DEBUG -> "D"
                        android.util.Log.INFO -> "I"
                        android.util.Log.WARN -> "W"
                        android.util.Log.ERROR -> "E"
                        android.util.Log.ASSERT -> "A"
                        else -> "?"
                    }
                    val time = timeFmt.format(Date(entry.timestamp))
                    writer.write("$time ${entry.packageName} $levelChar/${entry.tag}: ${entry.message}\n")
                }
            }

            Timber.i("Exported %d log entries to %s", entries.size, exportFile.absolutePath)
            exportFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to export logs")
            null
        }
    }

    // ── Filter setters ───────────────────────────────────────────────────

    fun setSelectedPackage(packageName: String?) {
        _filter.value = _filter.value.copy(selectedPackage = packageName)
    }

    fun setMinLevel(level: Int) {
        _filter.value = _filter.value.copy(minLevel = level)
    }

    fun setSearchQuery(query: String) {
        _filter.value = _filter.value.copy(searchQuery = query)
    }
}
