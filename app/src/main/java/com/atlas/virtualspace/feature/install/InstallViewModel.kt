package com.atlas.virtualspace.feature.install

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atlas.virtualspace.core.engine.VirtualEngine
import com.atlas.virtualspace.core.pm.InstallType
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import com.atlas.virtualspace.diagnostics.AtlasLogcatReporter
import com.atlas.virtualspace.ui.components.InstallStage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

// ─── UI state models ──────────────────────────────────────────────────────────

data class DeviceApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

data class InstallProgress(
    val appName: String? = null,
    val apkPath: String? = null,
    val stage: InstallStage = InstallStage.PARSING,
    val progress: Float = 0f,
    val errorMessage: String? = null,
)

sealed class InstallState {
    data object Idle : InstallState()
    data class InProgress(val progress: InstallProgress) : InstallState()
    data class Complete(val packageName: String) : InstallState()
    data class Error(val message: String) : InstallState()
}

data class InstallUiState(
    val deviceApps: List<DeviceApp> = emptyList(),
    val filteredDeviceApps: List<DeviceApp> = emptyList(),
    val installState: InstallState = InstallState.Idle,
    val searchQuery: String = "",
    val hideSystemApps: Boolean = true,
    val isLoadingDeviceApps: Boolean = false,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@dagger.hilt.android.lifecycle.HiltViewModel
class InstallViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstallUiState())
    val uiState: StateFlow<InstallUiState> = _uiState.asStateFlow()

    private val packageManager = appContext.packageManager

    init {
        loadDeviceApps()
    }

    // ── Load device-installed apps ────────────────────────────────────────

    fun loadDeviceApps() {
        _uiState.value = _uiState.value.copy(isLoadingDeviceApps = true)
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val installed = packageManager.getInstalledApplications(
                        PackageManager.GET_META_DATA
                    )
                    installed.mapNotNull { appInfo ->
                        val name = try {
                            packageManager.getApplicationLabel(appInfo).toString()
                        } catch (_: Exception) {
                            return@mapNotNull null
                        }
                        DeviceApp(
                            packageName = appInfo.packageName,
                            appName = name,
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }.sortedBy { it.appName.lowercase() }
                }
                _uiState.value = _uiState.value.copy(
                    deviceApps = apps,
                    filteredDeviceApps = filterApps(
                        apps,
                        _uiState.value.searchQuery,
                        _uiState.value.hideSystemApps,
                    ),
                    isLoadingDeviceApps = false,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load device apps")
                _uiState.value = _uiState.value.copy(isLoadingDeviceApps = false)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            searchQuery = query,
            filteredDeviceApps = filterApps(state.deviceApps, query, state.hideSystemApps),
        )
    }

    fun setHideSystemApps(hide: Boolean) {
        val state = _uiState.value
        _uiState.value = state.copy(
            hideSystemApps = hide,
            filteredDeviceApps = filterApps(state.deviceApps, state.searchQuery, hide),
        )
    }

    // ── Install from device (clone) ───────────────────────────────────────

    fun installFromDevice(packageName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                installState = InstallState.InProgress(
                    InstallProgress(
                        appName = packageName,
                        stage = InstallStage.PARSING,
                        progress = 0f,
                    )
                )
            )

            try {
                withContext(Dispatchers.IO) {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val sourceDir = appInfo.sourceDir
                    val apkFile = File(sourceDir)

                    updateProgress(InstallStage.EXTRACTING, 0.33f, packageName)

                    val result = VirtualEngine.installApp(apkFile, InstallType.CLONE)

                    result.onSuccess { virtualApp ->
                        updateProgress(InstallStage.COMPLETE, 1f, virtualApp.appName)
                        _uiState.value = _uiState.value.copy(
                            installState = InstallState.Complete(virtualApp.packageName),
                        )
                    }.onFailure { error ->
                        Timber.e(error, "Clone install failed for %s", packageName)
                        _uiState.value = _uiState.value.copy(
                            installState = InstallState.Error(
                                error.message ?: "Clone failed"
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Clone install failed for %s", packageName)
                _uiState.value = _uiState.value.copy(
                    installState = InstallState.Error(e.message ?: "Clone failed"),
                )
            }
        }
    }

    // ── Install from file (APK or XAPK) ──────────────────────────────────

    fun installFromFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                installState = InstallState.InProgress(
                    InstallProgress(stage = InstallStage.PARSING, progress = 0f)
                )
            )

            try {
                withContext(Dispatchers.IO) {
                    // Copy URI content to a temp file
                    val tempFile = copyUriToTempFile(uri)
                    val isXapk = detectXapk(tempFile)

                    updateProgress(InstallStage.EXTRACTING, 0.25f, null)

                    val installType = if (isXapk) InstallType.XAPK else InstallType.APK
                    val result = VirtualEngine.installApp(tempFile, installType)

                    result.onSuccess { virtualApp ->
                        updateProgress(InstallStage.COMPLETE, 1f, virtualApp.appName)
                        _uiState.value = _uiState.value.copy(
                            installState = InstallState.Complete(virtualApp.packageName),
                        )
                    }.onFailure { error ->
                        Timber.e(error, "File install failed for %s", uri)
                        AtlasLogcatReporter.reportError("Install", "File install failed: ${error.message}", error)
                        _uiState.value = _uiState.value.copy(
                            installState = InstallState.Error(
                                error.message ?: "Installation failed"
                            ),
                        )
                    }

                    // Clean up temp file
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to process file from URI: %s", uri)
                _uiState.value = _uiState.value.copy(
                    installState = InstallState.Error(e.message ?: "Failed to read file"),
                )
            }
        }
    }

    // ── Retry last failed install ─────────────────────────────────────────

    fun resetInstallState() {
        _uiState.value = _uiState.value.copy(installState = InstallState.Idle)
    }

    // ── XAPK detection ────────────────────────────────────────────────────

    fun detectXapk(file: File): Boolean {
        if (file.name.endsWith(".xapk", ignoreCase = true)) return true
        // Check for manifest.json inside the ZIP (XAPK signature)
        // Use ZipFile instead of ZipInputStream for more reliable reading
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("manifest.json") != null
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun updateProgress(stage: InstallStage, progress: Float, appName: String?) {
        val current = _uiState.value.installState
        if (current is InstallState.InProgress) {
            _uiState.value = _uiState.value.copy(
                installState = InstallState.InProgress(
                    current.progress.copy(
                        stage = stage,
                        progress = progress,
                        appName = appName ?: current.progress.appName,
                    )
                )
            )
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val inputStream = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI: $uri")

        val fileName = getFileName(uri) ?: "install_${System.currentTimeMillis()}.apk"
        val tempFile = File(appContext.cacheDir, fileName)

        FileOutputStream(tempFile).use { output ->
            inputStream.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }

        return tempFile
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = appContext.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )
                if (nameIndex >= 0) return it.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    private fun filterApps(
        apps: List<DeviceApp>,
        query: String,
        hideSystem: Boolean,
    ): List<DeviceApp> {
        var filtered = apps
        if (hideSystem) {
            filtered = filtered.filter { !it.isSystemApp }
        }
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        return filtered
    }
}
