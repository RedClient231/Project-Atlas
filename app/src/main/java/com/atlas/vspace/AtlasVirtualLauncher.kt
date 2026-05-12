package com.atlas.vspace

import android.content.Context
import com.atlas.vspace.core.ActivityRouter
import com.atlas.vspace.core.SlotManager
import com.atlas.vspace.loader.GuestManifestParser
import com.atlas.vspace.model.GuestAppManifest
import com.atlas.vspace.model.VirtualProcessSlot
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Public entry point for launching a guest app into a virtual stub process.
 *
 * This lives in the Atlas host process only. UI code calls
 * [launch] with a registered guest package name. The launcher:
 *
 *  1. Reads or parses the guest's manifest
 *  2. Acquires a stub process slot from [SlotManager]
 *  3. Writes a slot hint file (so the stub process knows which guest it hosts)
 *  4. Builds a redirecting Intent via [ActivityRouter]
 *  5. Starts that Intent — Android forks the `:pN` stub process, which
 *     will read the hint file in [AtlasStubApp.onCreate] and boot itself.
 *
 * The launcher is stateless — all runtime state lives in [SlotManager] and
 * the on-disk slot hint files. That means even if the Atlas host process
 * is killed and restarted, running stub processes can keep running as long
 * as their hint files remain.
 */
class AtlasVirtualLauncher(
    private val hostContext: Context,
    private val slotManager: SlotManager,
) {

    /**
     * Launches [guestPackage] into a virtual stub process.
     *
     * @return [Result.success] with the allocated slot when startActivity was
     *   dispatched; [Result.failure] if the manifest is unknown or the slot
     *   hint could not be written.
     */
    fun launch(guestPackage: String): Result<VirtualProcessSlot> {
        val manifest = loadManifest(guestPackage)
            ?: return Result.failure(
                IllegalStateException("No registry entry for $guestPackage — re-import the APK")
            )

        val slot = slotManager.acquire(guestPackage)

        val hintWritten = writeSlotHint(slot.index, guestPackage)
        if (!hintWritten) {
            slotManager.release(guestPackage)
            return Result.failure(
                IllegalStateException("Could not write slot hint for ${slot.processName}")
            )
        }

        // Ensure the registry file exists where StubBootstrap will look for it
        persistRegistry(manifest)

        val intent = ActivityRouter.buildLaunchIntent(hostContext, slot, manifest)

        try {
            hostContext.startActivity(intent)
            Timber.i(
                "[AtlasVirtualLauncher] Dispatched %s into %s",
                guestPackage, slot.processName
            )
            return Result.success(slot)
        } catch (t: Throwable) {
            Timber.e(t, "[AtlasVirtualLauncher] startActivity failed for %s", guestPackage)
            slotManager.release(guestPackage)
            clearSlotHint(slot.index)
            return Result.failure(t)
        }
    }

    /**
     * Registers (or re-registers) a guest APK so [StubBootstrap] can find it
     * when the stub process boots.
     *
     * Call this immediately after copying the APK into virtual storage.
     */
    fun registerGuest(
        apkPath: String,
        splitApkPaths: List<String> = emptyList(),
        nativeLibDir: String? = null,
    ): Result<GuestAppManifest> {
        val manifest = GuestManifestParser.parse(apkPath, splitApkPaths, nativeLibDir)
            ?: return Result.failure(
                IllegalArgumentException("Could not parse APK at $apkPath")
            )

        return try {
            persistRegistry(manifest)
            Timber.i("[AtlasVirtualLauncher] Registered %s", manifest.packageName)
            Result.success(manifest)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun unregisterGuest(packageName: String) {
        registryFile(packageName).takeIf { it.exists() }?.delete()
    }

    // ─── Private ────────────────────────────────────────────────────────

    private fun loadManifest(guestPackage: String): GuestAppManifest? {
        val f = registryFile(guestPackage)
        if (!f.exists()) return null
        return try {
            val obj = JSONObject(f.readText())
            GuestAppManifest(
                packageName = obj.getString("packageName"),
                appName = obj.optString("appName", obj.getString("packageName")),
                versionName = obj.optString("versionName", "unknown"),
                versionCode = obj.optLong("versionCode", 0L),
                mainActivity = obj.getString("mainActivity"),
                apkPath = obj.getString("apkPath"),
                splitApkPaths = obj.optJSONArray("splits")?.let {
                    List(it.length()) { i -> it.getString(i) }
                } ?: emptyList(),
                nativeLibDir = obj.optString("nativeLibDir").takeIf { it.isNotBlank() },
                targetSdk = obj.optInt("targetSdk", 21),
                mainActivityTheme = obj.optInt("theme", 0),
            )
        } catch (t: Throwable) {
            Timber.e(t, "[AtlasVirtualLauncher] Failed to read registry for %s", guestPackage)
            null
        }
    }

    private fun persistRegistry(manifest: GuestAppManifest) {
        val file = registryFile(manifest.packageName)
        file.parentFile?.mkdirs()
        val obj = JSONObject().apply {
            put("packageName", manifest.packageName)
            put("appName", manifest.appName)
            put("versionName", manifest.versionName)
            put("versionCode", manifest.versionCode)
            put("mainActivity", manifest.mainActivity)
            put("apkPath", manifest.apkPath)
            put("splits", JSONArray(manifest.splitApkPaths))
            manifest.nativeLibDir?.let { put("nativeLibDir", it) }
            put("targetSdk", manifest.targetSdk)
            put("theme", manifest.mainActivityTheme)
        }
        file.writeText(obj.toString())
    }

    private fun writeSlotHint(slotIndex: Int, guestPackage: String): Boolean {
        val f = slotHintFile(slotIndex)
        f.parentFile?.mkdirs()
        return try {
            f.writeText(guestPackage)
            true
        } catch (t: Throwable) {
            Timber.e(t, "[AtlasVirtualLauncher] Could not write slot hint")
            false
        }
    }

    private fun clearSlotHint(slotIndex: Int) {
        slotHintFile(slotIndex).takeIf { it.exists() }?.delete()
    }

    private fun registryFile(pkg: String): File {
        val dataParent = hostContext.filesDir.parentFile ?: hostContext.filesDir
        return File(dataParent, "vspace_registry/$pkg.json")
    }

    private fun slotHintFile(slotIndex: Int): File {
        val dataParent = hostContext.filesDir.parentFile ?: hostContext.filesDir
        return File(dataParent, "vspace_slots/slot_$slotIndex.hint")
    }
}
