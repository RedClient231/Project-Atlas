package com.atlas.virtualspace.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atlas.virtualspace.core.hook.ShizukuIntegration
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ─── DataStore ────────────────────────────────────────────────────────────────

private val Context.atlasDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "atlas_settings",
)

// ─── Setting keys ─────────────────────────────────────────────────────────────

object SettingKeys {
    val GAME_GUARDIAN_COMPAT = booleanPreferencesKey("game_guardian_compat")
    val MAX_CONCURRENT_APPS = intPreferencesKey("max_concurrent_apps")
    val HEAP_SIZE_MULTIPLIER = intPreferencesKey("heap_size_multiplier")
    val ENABLE_NATIVE_HOOKS = booleanPreferencesKey("enable_native_hooks")
    val ENABLE_64BIT_SUPPORT = booleanPreferencesKey("enable_64bit_support")
    val SHIZUKU_STATUS = stringPreferencesKey("shizuku_status")
}

// ─── Storage info model ───────────────────────────────────────────────────────

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val appCount: Int,
) {
    fun usagePercent(): Int =
        if (totalBytes <= 0L) 0
        else ((usedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

// ─── UI state model ───────────────────────────────────────────────────────────

data class SettingsUiState(
    val gameGuardianCompat: Boolean = false,
    val maxConcurrentApps: Int = 5,
    val heapSizeMultiplier: Int = 1,
    val enableNativeHooks: Boolean = true,
    val enable64BitSupport: Boolean = true,
    val shizukuStatus: String = "unknown",
    val storageInfo: StorageInfo = StorageInfo(0L, 0L, 0L, 0),
    val isClearingData: Boolean = false,
    val isExporting: Boolean = false,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@dagger.hilt.android.lifecycle.HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val dataStore = appContext.atlasDataStore

    private val _isClearingData = MutableStateFlow(false)
    private val _isExporting = MutableStateFlow(false)

    /** Reactive UI state derived from DataStore. */
    val uiState: StateFlow<SettingsUiState> = combineSettingsFlows().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // ── Read settings ────────────────────────────────────────────────────

    fun getSetting(key: Preferences.Key<Boolean>, default: Boolean = false): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[key] ?: default }

    fun getSetting(key: Preferences.Key<Int>, default: Int = 0): Flow<Int> =
        dataStore.data.map { prefs -> prefs[key] ?: default }

    fun getSetting(key: Preferences.Key<String>, default: String = ""): Flow<String> =
        dataStore.data.map { prefs -> prefs[key] ?: default }

    // ── Write settings ───────────────────────────────────────────────────

    suspend fun updateSetting(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    suspend fun updateSetting(key: Preferences.Key<Int>, value: Int) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    suspend fun updateSetting(key: Preferences.Key<String>, value: String) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    // ── Storage info ─────────────────────────────────────────────────────

    fun getStorageInfo(): StorageInfo {
        return try {
            val virtualRoot = File(appContext.filesDir, "virtual_root")
            val totalSpace = virtualRoot.totalSpace
            val freeSpace = virtualRoot.freeSpace
            val usedSpace = totalSpace - freeSpace
            val appCount = VirtualPackageManager.getInstalledApps().size

            StorageInfo(
                totalBytes = totalSpace,
                usedBytes = usedSpace,
                availableBytes = freeSpace,
                appCount = appCount,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute storage info")
            StorageInfo(0L, 0L, 0L, 0)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────

    fun clearAllData() {
        _isClearingData.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val apps = VirtualPackageManager.getInstalledApps()
                for (app in apps) {
                    try {
                        VirtualPackageManager.clearAppData(app.packageName)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to clear data for %s", app.packageName)
                    }
                }
            }
            _isClearingData.value = false
        }
    }

    fun exportAllApks() {
        _isExporting.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val exportDir = File(appContext.getExternalFilesDir(null), "exported_apks")
                if (!exportDir.exists()) exportDir.mkdirs()

                val apps = VirtualPackageManager.getInstalledApps()
                for (app in apps) {
                    try {
                        VirtualPackageManager.exportApp(app.packageName, exportDir)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to export %s", app.packageName)
                    }
                }
            }
            _isExporting.value = false
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun combineSettingsFlows(): Flow<SettingsUiState> {
        val ggCompat = dataStore.data.map { it[SettingKeys.GAME_GUARDIAN_COMPAT] ?: false }
        val maxConcurrent = dataStore.data.map { it[SettingKeys.MAX_CONCURRENT_APPS] ?: 5 }
        val heapMult = dataStore.data.map { it[SettingKeys.HEAP_SIZE_MULTIPLIER] ?: 1 }
        val nativeHooks = dataStore.data.map { it[SettingKeys.ENABLE_NATIVE_HOOKS] ?: true }
        val bit64 = dataStore.data.map { it[SettingKeys.ENABLE_64BIT_SUPPORT] ?: true }

        // Shizuku status is queried LIVE from ShizukuIntegration, not from
        // a stale DataStore value. This ensures the status is always accurate.
        val shizukuStatusFlow = kotlinx.coroutines.flow.flowOf(ShizukuIntegration.getShizukuStatus())

        return kotlinx.coroutines.flow.combine(
            ggCompat, maxConcurrent, heapMult, nativeHooks, bit64, shizukuStatusFlow, _isClearingData, _isExporting
        ) { args: Array<Any?> ->
            val storage = getStorageInfo()
            SettingsUiState(
                gameGuardianCompat = args[0] as Boolean,
                maxConcurrentApps = args[1] as Int,
                heapSizeMultiplier = args[2] as Int,
                enableNativeHooks = args[3] as Boolean,
                enable64BitSupport = args[4] as Boolean,
                shizukuStatus = args[5] as String,
                storageInfo = storage,
                isClearingData = args[6] as Boolean,
                isExporting = args[7] as Boolean,
            )
        }
    }
}
