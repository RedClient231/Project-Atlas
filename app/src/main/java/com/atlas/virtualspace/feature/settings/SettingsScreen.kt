package com.atlas.virtualspace.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.virtualspace.BuildConfig
import com.atlas.virtualspace.R
import com.atlas.virtualspace.feature.settings.SettingKeys
import kotlinx.coroutines.launch

/**
 * Settings page with sections: Engine, Storage, Advanced, About.
 * All settings are persisted via DataStore.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // ENGINE
        // ═══════════════════════════════════════════════════════════════════
        SettingsSectionHeader(
            icon = Icons.Default.Build,
            title = stringResource(R.string.settings_engine),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // GameGuardian compatibility
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_gg_compat),
                    subtitle = stringResource(R.string.settings_gg_compat_desc),
                    checked = uiState.gameGuardianCompat,
                    onCheckedChange = { checked ->
                        scope.launch { viewModel.updateSetting(SettingKeys.GAME_GUARDIAN_COMPAT, checked) }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Max concurrent apps slider
                Text(
                    text = stringResource(R.string.settings_max_concurrent, uiState.maxConcurrentApps),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_max_concurrent_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = uiState.maxConcurrentApps.toFloat(),
                    onValueChange = { scope.launch { viewModel.updateSetting(SettingKeys.MAX_CONCURRENT_APPS, it.toInt()) } },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Heap size multiplier slider
                Text(
                    text = stringResource(R.string.settings_heap_multiplier, uiState.heapSizeMultiplier),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_heap_multiplier_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = uiState.heapSizeMultiplier.toFloat(),
                    onValueChange = { scope.launch { viewModel.updateSetting(SettingKeys.HEAP_SIZE_MULTIPLIER, it.toInt()) } },
                    valueRange = 1f..4f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // STORAGE
        // ═══════════════════════════════════════════════════════════════════
        SettingsSectionHeader(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.settings_storage),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Virtual FS usage
                val storageInfo = uiState.storageInfo
                val usedGb = storageInfo.usedBytes / (1024.0 * 1024.0 * 1024.0)
                val totalGb = storageInfo.totalBytes / (1024.0 * 1024.0 * 1024.0)

                Text(
                    text = stringResource(
                        R.string.settings_storage_usage,
                        String.format("%.1f", usedGb),
                        String.format("%.1f", totalGb),
                        storageInfo.usagePercent(),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { storageInfo.usagePercent() / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = when {
                        storageInfo.usagePercent() > 85 -> MaterialTheme.colorScheme.error
                        storageInfo.usagePercent() > 60 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.settings_storage_apps, storageInfo.appCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Clear all app data
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clear_all_data),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.settings_clear_all_data_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uiState.isClearingData) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(onClick = { viewModel.clearAllData() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_clear))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Export all APKs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_export_apks),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.settings_export_apks_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uiState.isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(onClick = { viewModel.exportAllApks() }) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_export))
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // ADVANCED
        // ═══════════════════════════════════════════════════════════════════
        SettingsSectionHeader(
            icon = Icons.Default.Code,
            title = stringResource(R.string.settings_advanced),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Native hooks
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_native_hooks),
                    subtitle = stringResource(R.string.settings_native_hooks_desc),
                    checked = uiState.enableNativeHooks,
                    onCheckedChange = { checked ->
                        scope.launch { viewModel.updateSetting(SettingKeys.ENABLE_NATIVE_HOOKS, checked) }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 64-bit support
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_64bit),
                    subtitle = stringResource(R.string.settings_64bit_desc),
                    checked = uiState.enable64BitSupport,
                    onCheckedChange = { checked ->
                        scope.launch { viewModel.updateSetting(SettingKeys.ENABLE_64BIT_SUPPORT, checked) }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Shizuku status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_shizuku),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = when (uiState.shizukuStatus) {
                                "running" -> stringResource(R.string.settings_shizuku_running)
                                "not_installed" -> stringResource(R.string.settings_shizuku_not_installed)
                                else -> stringResource(R.string.settings_shizuku_unknown)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (uiState.shizukuStatus) {
                                "running" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // ABOUT
        // ═══════════════════════════════════════════════════════════════════
        SettingsSectionHeader(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Version
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_version),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Open source licenses
                OutlinedButton(
                    onClick = {
                        // Launch OSS licenses activity
                        // In production this would use the google-services OSS plugin
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_licenses))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GitHub link
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/atlas-virtual-space/project-atlas"),
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_github))
                }
            }
        }

        // Bottom spacing for nav bar
        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ─── Reusable section header ─────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ─── Reusable switch row ─────────────────────────────────────────────────────

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
