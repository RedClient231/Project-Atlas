package com.atlas.virtualspace.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.atlas.virtualspace.R
import com.atlas.virtualspace.core.pm.VirtualAppInfo
import com.atlas.virtualspace.ui.theme.AtlasRunningIndicator

/**
 * Reusable card that represents a single virtual app in the home grid.
 *
 * @param app            The virtual app metadata to display.
 * @param isRunning      Whether the app currently has an active process.
 * @param onLaunch       Callback when the user taps the Launch button.
 * @param onShortcut     Callback when "Create Shortcut" is selected.
 * @param onClearData    Callback when "Clear Data" is selected.
 * @param onUninstall    Callback when "Uninstall" is selected.
 * @param onLongPress    Callback when the card is long-pressed (quick-actions).
 * @param modifier       Optional modifier.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    app: VirtualAppInfo,
    isRunning: Boolean,
    onLaunch: () -> Unit,
    onShortcut: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = onLongPress,
            ),
    ) {
        Column(
            modifier = Modifier
                .size(width = 160.dp, height = 190.dp)
                .clip(MaterialTheme.shapes.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // ── Icon + running indicator ────────────────────────────────
            Box(contentAlignment = Alignment.TopEnd) {
                AsyncImage(
                    model = app.apkPath,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Fit,
                    placeholder = painterResource(R.drawable.ic_app_placeholder),
                    error = painterResource(R.drawable.ic_app_placeholder),
                )

                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(AtlasRunningIndicator, CircleShape)
                            .padding(2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── App name ────────────────────────────────────────────────
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // ── Package name ────────────────────────────────────────────
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Launch button + overflow menu ───────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalButton(
                    onClick = onLaunch,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.action_launch),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_launch)) },
                            onClick = {
                                menuExpanded = false
                                onLaunch()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_create_shortcut)) },
                            onClick = {
                                menuExpanded = false
                                onShortcut()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear_data)) },
                            onClick = {
                                menuExpanded = false
                                onClearData()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_uninstall)) },
                            onClick = {
                                menuExpanded = false
                                onUninstall()
                            },
                        )
                    }
                }
            }
        }
    }
}
