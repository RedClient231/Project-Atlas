package com.atlas.virtualspace.core.hook

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import com.atlas.virtualspace.core.engine.VirtualEngine

/**
 * Stub virtual implementations for system service hooks.
 *
 * These objects are invoked by [SystemServiceHooks] when hooked system
 * service calls are redirected from virtual apps. They delegate to
 * [VirtualEngine] for actual virtual state where possible, and fall back
 * to safe defaults otherwise.
 *
 * All methods are designed to be non-crashing — every call is wrapped
 * in runCatching so a failure here never propagates into the hook callback
 * (which would crash the virtual app).
 */

// ─── VirtualPackageManagerService ────────────────────────────────────────────

internal object VirtualPackageManagerService {
    private const val TAG = "Atlas:VirtPMS"

    fun getPackageInfo(packageName: String, args: Array<out Any?>): PackageInfo? {
        return runCatching {
            val appInfo = VirtualEngine.installedApps.value
                .firstOrNull { it.packageName == packageName } ?: return null

            PackageInfo().apply {
                this.packageName = appInfo.packageName
                versionName = appInfo.versionName
                versionCode = appInfo.versionCode.toInt()
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = appInfo.packageName
                    enabled = appInfo.isEnabled
                }
            }
        }.onFailure { e -> Log.w(TAG, "getPackageInfo failed for $packageName", e) }
            .getOrNull()
    }

    fun getApplicationInfo(packageName: String, args: Array<out Any?>): ApplicationInfo? {
        return runCatching {
            val appInfo = VirtualEngine.installedApps.value
                .firstOrNull { it.packageName == packageName } ?: return null

            ApplicationInfo().apply {
                this.packageName = appInfo.packageName
                enabled = appInfo.isEnabled
                sourceDir = appInfo.apkPath
                publicSourceDir = appInfo.apkPath
                nativeLibraryDir = appInfo.nativeLibPath
            }
        }.onFailure { e -> Log.w(TAG, "getApplicationInfo failed for $packageName", e) }
            .getOrNull()
    }

    fun getInstalledPackages(args: Array<out Any?>): List<PackageInfo> {
        return runCatching {
            VirtualEngine.installedApps.value.map { appInfo ->
                PackageInfo().apply {
                    packageName = appInfo.packageName
                    versionName = appInfo.versionName
                    versionCode = appInfo.versionCode.toInt()
                }
            }
        }.onFailure { e -> Log.w(TAG, "getInstalledPackages failed", e) }
            .getOrDefault(emptyList())
    }

    fun getInstalledApplications(args: Array<out Any?>): List<ApplicationInfo> {
        return runCatching {
            VirtualEngine.installedApps.value.map { appInfo ->
                ApplicationInfo().apply {
                    packageName = appInfo.packageName
                    enabled = appInfo.isEnabled
                    sourceDir = appInfo.apkPath
                }
            }
        }.onFailure { e -> Log.w(TAG, "getInstalledApplications failed", e) }
            .getOrDefault(emptyList())
    }

    fun getComponentEnabledSetting(componentName: String): Int {
        // Default: component follows parent app's enabled state
        return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    fun setComponentEnabledSetting(componentName: String, newState: Int) {
        Log.d(TAG, "setComponentEnabledSetting($componentName, $newState) — no-op in virtual PM")
    }

    fun getPackageUid(packageName: String): Int {
        return runCatching {
            VirtualEngine.getRunningProcesses()
                .firstOrNull { it.packageName == packageName }
                ?.uid ?: -1
        }.getOrDefault(-1)
    }
}

// ─── VirtualWindowManagerService ─────────────────────────────────────────────

internal object VirtualWindowManagerService {
    private const val TAG = "Atlas:VirtWMS"

    fun getDefaultDisplayRotation(): Int = 0  // Surface.ROTATION_0

    fun getDisplayDecorationSupport(): Any? = null  // No decoration support stub

    fun getInitialDisplaySize(): Any? = null  // Let real WM handle display size
}

// ─── VirtualAlarmService ──────────────────────────────────────────────────────

internal object VirtualAlarmService {
    private const val TAG = "Atlas:VirtAlarm"

    /** In-memory store of virtual alarms. Key = callerPackage, value = list of alarm descriptors. */
    private val virtualAlarms = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    fun scheduleAlarm(callerPackage: String, methodName: String, args: Array<out Any?>) {
        Log.d(TAG, "Virtual alarm scheduled: $methodName for $callerPackage")
        virtualAlarms.getOrPut(callerPackage) { mutableListOf() }
            .add("$methodName@${System.currentTimeMillis()}")
    }

    fun cancelAlarm(callerPackage: String, args: Array<out Any?>) {
        Log.d(TAG, "Virtual alarm cancelled for $callerPackage")
        virtualAlarms[callerPackage]?.clear()
    }

    fun getVirtualAlarmsForPackage(packageName: String): List<String> {
        return virtualAlarms[packageName]?.toList() ?: emptyList()
    }
}
