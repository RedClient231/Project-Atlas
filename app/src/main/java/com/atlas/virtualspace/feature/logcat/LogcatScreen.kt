package com.atlas.virtualspace.feature.logcat

import android.content.Intent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.virtualspace.R
import com.atlas.virtualspace.data.database.AppLogEntry
import com.atlas.virtualspace.ui.components.LogEntryItem

/**
 * Built-in logcat viewer with filtering, search, and export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    viewModel: LogcatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    val shouldAutoScroll by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 3
        }
    }

    LaunchedEffect(uiState.entries.size) {
        if (shouldAutoScroll && !uiState.isPaused && uiState.entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Filter bar ────────────────────────────────────────────────────
        FilterBar(
            filter = uiState.filter,
            availablePackages = uiState.availablePackages,
            onPackageSelected = { viewModel.setSelectedPackage(it) },
            onLevelChanged = { viewModel.setMinLevel(it) },
            onSearchQueryChanged = { viewModel.setSearchQuery(it) },
        )

        // ── Control bar ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Capture status
            Text(
                text = if (uiState.isCapturing) {
                    if (uiState.isPaused) stringResource(R.string.logcat_paused)
                    else stringResource(R.string.logcat_capturing)
                } else {
                    stringResource(R.string.logcat_stopped)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (uiState.isCapturing && !uiState.isPaused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // Control buttons
            Row {
                if (!uiState.isCapturing) {
                    IconButton(onClick = { viewModel.startCapture() }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.logcat_start),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    // Pause / Resume
                    IconButton(onClick = { viewModel.togglePause() }) {
                        Icon(
                            if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (uiState.isPaused) {
                                stringResource(R.string.logcat_resume)
                            } else {
                                stringResource(R.string.logcat_pause)
                            },
                        )
                    }

                    // Stop
                    IconButton(onClick = { viewModel.stopCapture() }) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.logcat_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Clear
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.logcat_clear),
                    )
                }

                // Export
                IconButton(
                    onClick = {
                        val file = viewModel.exportLogs()
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    context.getString(R.string.logcat_export_chooser),
                                )
                            )
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.logcat_export),
                    )
                }
            }
        }

        // ── Entry count ───────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.logcat_entry_count, uiState.entries.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Log entries list ──────────────────────────────────────────────
        if (uiState.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (uiState.isCapturing) {
                        stringResource(R.string.logcat_waiting)
                    } else {
                        stringResource(R.string.logcat_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(
                    items = uiState.entries,
                    key = { it.id },
                ) { entry ->
                    LogEntryItem(entry = entry)
                }
            }
        }
    }
}

// ─── Filter bar ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filter: LogcatFilter,
    availablePackages: List<String>,
    onPackageSelected: (String?) -> Unit,
    onLevelChanged: (Int) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
) {
    var packageDropdownExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Search
        SearchBar(
            query = filter.searchQuery,
            onQueryChange = onSearchQueryChanged,
            onSearch = { searchActive = false },
            active = searchActive,
            onActiveChange = { searchActive = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.logcat_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        ) { /* search suggestions */ }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Package filter dropdown
            ExposedDropdownMenuBox(
                expanded = packageDropdownExpanded,
                onExpandedChange = { packageDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = filter.selectedPackage ?: stringResource(R.string.logcat_all_apps),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .weight(1f),
                    label = { Text(stringResource(R.string.logcat_filter_package)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = packageDropdownExpanded) },
                    singleLine = true,
                )

                ExposedDropdownMenu(
                    expanded = packageDropdownExpanded,
                    onDismissRequest = { packageDropdownExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logcat_all_apps)) },
                        onClick = {
                            onPackageSelected(null)
                            packageDropdownExpanded = false
                        },
                    )
                    for (pkg in availablePackages) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = pkg,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {
                                onPackageSelected(pkg)
                                packageDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            // Level filter chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LogLevelChip("V", android.util.Log.VERBOSE, filter.minLevel, onLevelChanged)
                LogLevelChip("D", android.util.Log.DEBUG, filter.minLevel, onLevelChanged)
                LogLevelChip("I", android.util.Log.INFO, filter.minLevel, onLevelChanged)
                LogLevelChip("W", android.util.Log.WARN, filter.minLevel, onLevelChanged)
                LogLevelChip("E", android.util.Log.ERROR, filter.minLevel, onLevelChanged)
            }
        }
    }
}

@Composable
private fun LogLevelChip(
    label: String,
    level: Int,
    currentMinLevel: Int,
    onLevelChanged: (Int) -> Unit,
) {
    FilterChip(
        selected = currentMinLevel <= level,
        onClick = {
            onLevelChanged(if (currentMinLevel <= level) android.util.Log.VERBOSE else level)
        },
        label = { Text(label, fontFamily = FontFamily.Monospace) },
    )
}
