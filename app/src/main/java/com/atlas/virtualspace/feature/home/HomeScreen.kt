package com.atlas.virtualspace.feature.home

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.virtualspace.R
import com.atlas.virtualspace.ui.components.AppCard

/**
 * Home screen displaying installed virtual apps in a grid.
 *
 * Features:
 * - Search bar at top
 * - Memory usage summary
 * - Grid of app cards
 * - Pull-to-refresh
 * - FAB to install a new app
 * - Empty-state illustration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToInstall: () -> Unit,
    onNavigateToAppDetail: (String) -> Unit,
    highlightPackage: String? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSearchBarExpanded by remember { mutableStateOf(false) }

    // Show snackbar when error message changes
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbarMessage()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refreshApps() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                // ── Search bar ────────────────────────────────────────────
                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onSearch = { isSearchBarExpanded = false },
                    active = isSearchBarExpanded,
                    onActiveChange = { isSearchBarExpanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                ) {
                    // Search suggestions (empty – just filters the grid live)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Memory usage summary ──────────────────────────────────
                MemoryUsageCard(
                    usedMb = uiState.memoryInfo.usedMb,
                    totalMb = uiState.memoryInfo.totalMb,
                    usagePercent = uiState.memoryInfo.usagePercent(),
                    runningAppCount = uiState.runningPackages.size,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── App grid or empty state ───────────────────────────────
                if (uiState.apps.isEmpty()) {
                    EmptyState(
                        hasSearch = uiState.searchQuery.isNotBlank(),
                        onInstallClick = onNavigateToInstall,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(
                            items = uiState.apps,
                            key = { it.packageName },
                        ) { app ->
                            AppCard(
                                app = app,
                                isRunning = app.packageName in uiState.runningPackages,
                                onLaunch = { viewModel.launchApp(app.packageName) },
                                onShortcut = { viewModel.createShortcut(app.packageName) },
                                onClearData = { viewModel.clearData(app.packageName) },
                                onUninstall = { viewModel.uninstallApp(app.packageName) },
                                onLongPress = { onNavigateToAppDetail(app.packageName) },
                            )
                        }
                    }
                }
            }

            // ── FAB ───────────────────────────────────────────────────────
            FloatingActionButton(
                onClick = onNavigateToInstall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.home_fab_install),
                )
            }
        }
    }
}

// ─── Memory usage card ────────────────────────────────────────────────────────

@Composable
private fun MemoryUsageCard(
    usedMb: Long,
    totalMb: Long,
    usagePercent: Int,
    runningAppCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.home_memory_summary,
                        usedMb,
                        totalMb,
                        usagePercent,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { usagePercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = when {
                        usagePercent > 85 -> MaterialTheme.colorScheme.error
                        usagePercent > 60 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$runningAppCount",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.home_running),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    hasSearch: Boolean,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (hasSearch) {
                    stringResource(R.string.home_empty_search)
                } else {
                    stringResource(R.string.home_empty_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (!hasSearch) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                androidx.compose.material3.FilledTonalButton(onClick = onInstallClick) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_empty_install_btn))
                }
            }
        }
    }
}
