package com.atlas.virtualspace.feature.install

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.virtualspace.R
import com.atlas.virtualspace.ui.components.InstallProgressCard
import com.atlas.virtualspace.ui.components.InstallStage

/**
 * Install screen with two tabs: "From Device" and "From File".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallScreen(
    pendingInstallUri: Uri?,
    onPendingUriConsumed: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: InstallViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabIndex by remember { mutableStateOf(0) }
    var searchActive by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.installFromFile(it) }
    }

    // Handle pending install URI from intent
    if (pendingInstallUri != null) {
        androidx.compose.runtime.LaunchedEffect(pendingInstallUri) {
            viewModel.installFromFile(pendingInstallUri)
            onPendingUriConsumed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Tab row ───────────────────────────────────────────────────────
        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text(stringResource(R.string.install_tab_device)) },
                icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text(stringResource(R.string.install_tab_file)) },
                icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Install progress card ─────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.installState is InstallState.InProgress ||
                    uiState.installState is InstallState.Complete ||
                    uiState.installState is InstallState.Error,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            when (val state = uiState.installState) {
                is InstallState.InProgress -> {
                    InstallProgressCard(
                        appName = state.progress.appName,
                        apkPath = state.progress.apkPath,
                        stage = state.progress.stage,
                        progress = state.progress.progress,
                        errorMessage = state.progress.errorMessage,
                        onRetry = { viewModel.resetInstallState() },
                        onDismiss = { viewModel.resetInstallState() },
                    )
                }
                is InstallState.Complete -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.install_success, state.packageName),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.resetInstallState() }) {
                                Text(stringResource(R.string.action_done))
                            }
                        }
                    }
                }
                is InstallState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { viewModel.resetInstallState() }) {
                                    Text(stringResource(R.string.action_dismiss))
                                }
                            }
                        }
                    }
                }
                InstallState.Idle -> { /* nothing */ }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Tab content ───────────────────────────────────────────────────
        when (selectedTabIndex) {
            0 -> FromDeviceTab(
                apps = uiState.filteredDeviceApps,
                searchQuery = uiState.searchQuery,
                hideSystemApps = uiState.hideSystemApps,
                isLoading = uiState.isLoadingDeviceApps,
                onSearchQueryChanged = { viewModel.updateSearchQuery(it) },
                onHideSystemAppsChanged = { viewModel.setHideSystemApps(it) },
                onInstallClick = { viewModel.installFromDevice(it) },
                searchActive = searchActive,
                onSearchActiveChange = { searchActive = it },
            )
            1 -> FromFileTab(
                onPickFile = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/xapk",
                            "*/*",
                        )
                    )
                },
            )
        }
    }
}

// ─── From Device tab ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FromDeviceTab(
    apps: List<DeviceApp>,
    searchQuery: String,
    hideSystemApps: Boolean,
    isLoading: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onHideSystemAppsChanged: (Boolean) -> Unit,
    onInstallClick: (String) -> Unit,
    searchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        SearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChanged,
            onSearch = { onSearchActiveChange(false) },
            active = searchActive,
            onActiveChange = onSearchActiveChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text(stringResource(R.string.install_search_device_apps)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        ) { /* suggestions */ }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = hideSystemApps,
                onClick = { onHideSystemAppsChanged(!hideSystemApps) },
                label = { Text(stringResource(R.string.install_hide_system)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.install_app_count, apps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 80.dp,
                ),
            ) {
                items(items = apps, key = { it.packageName }) { app ->
                    DeviceAppRow(
                        app = app,
                        onCloneClick = { onInstallClick(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceAppRow(
    app: DeviceApp,
    onCloneClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.isSystemApp) {
                    Text(
                        text = stringResource(R.string.install_system_app),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Button(onClick = onCloneClick) {
                Text(stringResource(R.string.install_clone))
            }
        }
    }
}

// ─── From File tab ────────────────────────────────────────────────────────────

@Composable
private fun FromFileTab(
    onPickFile: () -> Unit,
) {
    var isDragOver by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Drag & drop zone
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragOver) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.install_drag_drop),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.install_supported_formats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onPickFile) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.install_pick_file))
                    }
                }
            }
        }
    }
}
