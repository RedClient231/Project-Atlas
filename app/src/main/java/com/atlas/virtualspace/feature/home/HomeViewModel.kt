package com.atlas.virtualspace.feature.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atlas.virtualspace.core.engine.MemoryInfo
import com.atlas.virtualspace.core.engine.VirtualEngine
import com.atlas.virtualspace.core.pm.VirtualAppInfo
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import com.atlas.virtualspace.data.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ─── UI state models ──────────────────────────────────────────────────────────

data class HomeUiState(
    val apps: List<VirtualAppInfo> = emptyList(),
    val runningPackages: Set<String> = emptySet(),
    val memoryInfo: MemoryInfo = MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val snackbarMessage: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@dagger.hilt.android.lifecycle.HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
) : ViewModel() {

    private val dao = database.virtualAppDao()

    // ── Reactive sources ─────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    /** Snackbar message for launch/uninstall feedback. */
    private val _snackbarMessage = MutableStateFlow<String?>(null)

    /** All installed virtual apps, reactively updated via Room. */
    private val allApps = dao.getAll()
        .map { list ->
            list.sortedWith(
                compareByDescending<VirtualAppInfo> { it.lastLaunchTime }
                    .thenBy { it.appName }
            )
        }

    /** Set of currently-running package names. */
    private val _runningPackages = MutableStateFlow<Set<String>>(emptySet())

    /** System memory info. */
    private val _memoryInfo = MutableStateFlow(MemoryInfo.fromSystem(appContext))

    // ── Combined UI state ────────────────────────────────────────────────

    val uiState: StateFlow<HomeUiState> = combine(
        allApps,
        _searchQuery,
        _runningPackages,
        _memoryInfo,
        _isRefreshing,
        _snackbarMessage,
    ) { apps, query, running, memory, refreshing, snackbar ->
        val filtered = if (query.isBlank()) apps else apps.filter { app ->
            app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
        }
        HomeUiState(
            apps = filtered,
            runningPackages = running,
            memoryInfo = memory,
            searchQuery = query,
            isRefreshing = refreshing,
            snackbarMessage = snackbar,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    // ── Public API ───────────────────────────────────────────────────────

    fun refreshApps() {
        _isRefreshing.value = true
        refreshRunningState()
        _memoryInfo.value = MemoryInfo.fromSystem(appContext)
        _isRefreshing.value = false
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualPackageManager.launchApp(packageName)
            }
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Failed to launch %s", packageName)
                _snackbarMessage.value = "Failed to launch: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            } else {
                refreshRunningState()
            }
        }
    }

    fun uninstallApp(packageName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualEngine.uninstallApp(packageName)
            }
            if (result.isSuccess) {
                _snackbarMessage.value = "App uninstalled successfully"
            } else {
                Timber.e(result.exceptionOrNull(), "Failed to uninstall %s", packageName)
                _snackbarMessage.value = "Failed to uninstall: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
        }
    }

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    fun clearData(packageName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualPackageManager.clearAppData(packageName)
            }
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Failed to clear data for %s", packageName)
            }
        }
    }

    fun createShortcut(packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                try {
                    val appInfo = VirtualPackageManager.getAppInfo(packageName) ?: return@withContext
                    val launchActivity = appInfo.launchActivity ?: return@withContext

                    // The shortcut should launch VirtualStubActivity with extras
                    // identifying the target package and activity.
                    val shortcutIntent = Intent(appContext, com.atlas.virtualspace.core.engine.VirtualStubActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        putExtra(com.atlas.virtualspace.core.engine.VirtualStubActivity.EXTRA_PACKAGE_NAME, packageName)
                        putExtra(com.atlas.virtualspace.core.engine.VirtualStubActivity.EXTRA_ACTIVITY_CLASS, launchActivity)
                        putExtra(com.atlas.virtualspace.core.engine.VirtualStubActivity.EXTRA_VIRTUAL_LAUNCH, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }

                    val shortcut = android.content.pm.ShortcutInfo.Builder(
                        appContext,
                        "atlas_$packageName"
                    )
                        .setShortLabel(appInfo.appName)
                        .setLongLabel("${appInfo.appName} (Atlas)")
                        .setIntent(shortcutIntent)
                        .build()

                    val shortcutManager = appContext.getSystemService(
                        android.content.pm.ShortcutManager::class.java
                    )
                    shortcutManager?.requestPinShortcut(shortcut, null)
                    Timber.i("Shortcut requested for %s", packageName)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create shortcut for %s", packageName)
                }
            }
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun refreshRunningState() {
        val running = VirtualEngine.getRunningProcesses().map { it.packageName }.toSet()
        _runningPackages.value = running
    }
}
