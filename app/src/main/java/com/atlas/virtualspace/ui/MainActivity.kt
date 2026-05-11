package com.atlas.virtualspace.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.atlas.virtualspace.ui.theme.AtlasTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Main entry point for the Atlas UI.
 *
 * Annotated with [AndroidEntryPoint] so Hilt performs member injection
 * before [onCreate] runs. The activity delegates all rendering to
 * Jetpack Compose via [AtlasNavHost].
 *
 * Responsibilities:
 * - Installs the Android 12+ splash screen API
 * - Requests runtime permissions required by the virtual engine
 * - Handles incoming APK / XAPK file-open intents
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Package name extracted from an incoming VIEW intent, or null. */
    private var pendingInstallUri by mutableStateOf<Uri?>(null)

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)

        // Handle any intent that opened this activity (e.g. file manager → APK)
        handleIncomingIntent(intent)

        setContent {
            AtlasTheme {
                AtlasApp(
                    pendingInstallUri = pendingInstallUri,
                    onPendingUriConsumed = { pendingInstallUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ─── Intent handling ───────────────────────────────────────────────────────

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return

        val uri = intent.data ?: return
        val mimeType = intent.type

        val isApk = mimeType == "application/vnd.android.package-archive"
                || uri.lastPathSegment?.endsWith(".apk") == true
        val isXapk = mimeType == "application/xapk"
                || uri.lastPathSegment?.endsWith(".xapk", ignoreCase = true) == true

        if (isApk || isXapk) {
            Timber.i("Received file-open intent: %s (%s)", uri, mimeType)
            pendingInstallUri = uri
        }
    }

    // ─── Permissions ───────────────────────────────────────────────────────────

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

// ─── Top-level composable that wires permissions + navigation ─────────────────

@Composable
private fun AtlasApp(
    pendingInstallUri: Uri?,
    onPendingUriConsumed: () -> Unit,
) {
    val context = LocalContext.current

    // Permission state
    var allPermissionsGranted by androidx.compose.runtime.mutableStateOf(
        checkAllPermissions(context)
    )

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        allPermissionsGranted = grants.values.all { it }
        if (!allPermissionsGranted) {
            Toast.makeText(
                context,
                "Some features may not work without the required permissions",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Request on first composition
    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            permissionLauncher.launch(MainActivity.REQUIRED_PERMISSIONS)
        }
    }

    // Navigate to install screen when a file-open intent arrives
    LaunchedEffect(pendingInstallUri) {
        if (pendingInstallUri != null) {
            onPendingUriConsumed()
        }
    }

    AtlasNavHost(
        pendingInstallUri = pendingInstallUri,
        onPendingUriConsumed = onPendingUriConsumed,
    )
}

private fun checkAllPermissions(context: android.content.Context): Boolean {
    return MainActivity.REQUIRED_PERMISSIONS.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}
