package com.atlas.virtualspace.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atlas.virtualspace.data.database.AppLogEntry
import com.atlas.virtualspace.ui.theme.LogDebug
import com.atlas.virtualspace.ui.theme.LogError
import com.atlas.virtualspace.ui.theme.LogInfo
import com.atlas.virtualspace.ui.theme.LogVerbose
import com.atlas.virtualspace.ui.theme.LogWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single log entry row for the logcat viewer.
 *
 * @param entry    The log entry data.
 * @param modifier Optional modifier.
 */
@Composable
fun LogEntryItem(
    entry: AppLogEntry,
    modifier: Modifier = Modifier,
) {
    val levelColor = when (entry.level) {
        android.util.Log.VERBOSE -> LogVerbose
        android.util.Log.DEBUG -> LogDebug
        android.util.Log.INFO -> LogInfo
        android.util.Log.WARN -> LogWarn
        android.util.Log.ERROR -> LogError
        else -> LogError
    }

    val levelLabel = when (entry.level) {
        android.util.Log.VERBOSE -> "V"
        android.util.Log.DEBUG -> "D"
        android.util.Log.INFO -> "I"
        android.util.Log.WARN -> "W"
        android.util.Log.ERROR -> "E"
        android.util.Log.ASSERT -> "A"
        else -> "?"
    }

    val timeFormatter = androidx.compose.runtime.remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val timeText = timeFormatter.format(Date(entry.timestamp))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Timestamp
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        // Level badge
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(levelColor.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = levelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Tag
        Text(
            text = entry.tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0f, fill = false),
        )

        // Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
        )
    }
}
