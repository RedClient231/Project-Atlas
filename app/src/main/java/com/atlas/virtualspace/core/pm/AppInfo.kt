package com.atlas.virtualspace.core.pm

import android.graphics.drawable.Drawable

/**
 * Internal representation of parsed APK metadata.
 * This is **not** a database entity — it acts as an intermediate DTO between
 * the APK parser and the persistent [VirtualAppInfo] entity.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val is64Bit: Boolean,
    val isGame: Boolean,
    val launchActivity: String?,
    val permissions: List<String>,
    val nativeLibs: List<String>,
    val splitConfigs: List<String>,
    val obbFiles: List<String>,
    val icon: Drawable?
)
