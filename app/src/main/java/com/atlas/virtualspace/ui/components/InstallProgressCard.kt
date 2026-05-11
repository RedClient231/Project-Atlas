package com.atlas.virtualspace.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.atlas.virtualspace.R
import com.atlas.virtualspace.ui.theme.AtlasError
import com.atlas.virtualspace.ui.theme.AtlasSuccess

/**
 * Represents the current stage of an installation.
 */
enum class InstallStage {
    PARSING,
    EXTRACTING,
    INSTALLING,
    COMPLETE,
    ERROR,
}

/**
 * Progress card for an in-progress installation.
 *
 * @param appName     Display name of the app being installed (may be null during parsing).
 * @param apkPath     Path to the APK file (used as the Coil model for the icon).
 * @param stage       Current installation stage.
 * @param progress    Progress fraction [0f..1f]; -1f for indeterminate.
 * @param errorMessage  Human-readable error text when [stage] is [InstallStage.ERROR].
 * @param onRetry     Callback when the user taps "Retry" in the error state.
 * @param onDismiss   Callback when the user dismisses the card in the complete state.
 * @param modifier    Optional modifier.
 */
@Composable
fun InstallProgressCard(
    appName: String?,
    apkPath: String?,
    stage: InstallStage,
    progress: Float,
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor by animateColorAsState(
        targetValue = when (stage) {
            InstallStage.COMPLETE -> AtlasSuccess
            InstallStage.ERROR -> AtlasError
            else -> MaterialTheme.colorScheme.primary
        },
        label = "progressColor",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // App icon or spinner
                if (apkPath != null && stage != InstallStage.PARSING) {
                    AsyncImage(
                        model = apkPath,
                        contentDescription = appName,
                        modifier = Modifier.size(48.dp),
                        placeholder = painterResource(R.drawable.ic_app_placeholder),
                        error = painterResource(R.drawable.ic_app_placeholder),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName ?: stringResource(R.string.install_parsing),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stageLabel(stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = progressColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress indicator
            if (stage == InstallStage.ERROR) {
                // Error state
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = AtlasError,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            } else if (stage == InstallStage.COMPLETE) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            } else {
                LinearProgressIndicator(
                    progress = { if (progress >= 0f) progress else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun stageLabel(stage: InstallStage): String = when (stage) {
    InstallStage.PARSING -> stringResource(R.string.install_stage_parsing)
    InstallStage.EXTRACTING -> stringResource(R.string.install_stage_extracting)
    InstallStage.INSTALLING -> stringResource(R.string.install_stage_installing)
    InstallStage.COMPLETE -> stringResource(R.string.install_stage_complete)
    InstallStage.ERROR -> stringResource(R.string.install_stage_error)
}
