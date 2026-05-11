package com.atlas.virtualspace.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atlas.virtualspace.core.engine.VirtualEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Broadcast receiver that starts the virtual engine after the device
 * finishes booting, but only if the user has enabled auto-start in
 * settings.
 *
 * Registered in AndroidManifest.xml for `BOOT_COMPLETED` and
 * `LOCKED_BOOT_COMPLETED` broadcasts.
 *
 * Reads the auto-start preference directly from DataStore without
 * Hilt injection, because BroadcastReceiver's goAsync() + Hilt
 * injection can be fragile on some OEMs.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private val Context.atlasDataStore by preferencesDataStore(
        name = "atlas_settings",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        Timber.i("Boot broadcast received: %s", intent.action)

        // Use goAsync() to extend the receiver's lifecycle for async work
        val pendingResult = goAsync()

        scope.launch {
            try {
                val autoStartKey = booleanPreferencesKey("auto_start_on_boot")
                val autoStart = context.atlasDataStore.data
                    .map { prefs -> prefs[autoStartKey] ?: false }
                    .first()

                if (!autoStart) {
                    Timber.d("Auto-start disabled — skipping engine init on boot")
                    return@launch
                }

                Timber.i("Auto-start enabled — initializing virtual engine on boot")
                val result = VirtualEngine.initialize(context)
                if (result.isFailure) {
                    Timber.e(
                        result.exceptionOrNull(),
                        "Failed to initialize virtual engine on boot"
                    )
                } else {
                    Timber.i("Virtual engine initialized successfully on boot")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during boot receiver processing")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
