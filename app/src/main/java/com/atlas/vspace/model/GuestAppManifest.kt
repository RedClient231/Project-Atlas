package com.atlas.vspace.model

/**
 * Parsed metadata about a guest APK, cached in memory after first parse.
 *
 * This is the information [HCallback] and [InstrumentationShim] need to
 * rewrite intents and construct the real guest Activity.
 */
data class GuestAppManifest(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val mainActivity: String,
    val apkPath: String,
    val splitApkPaths: List<String>,
    val nativeLibDir: String?,
    /** Target SDK as declared by the guest's manifest. */
    val targetSdk: Int,
    /** Theme resource id if declared on the main activity (0 otherwise). */
    val mainActivityTheme: Int = 0,
)
