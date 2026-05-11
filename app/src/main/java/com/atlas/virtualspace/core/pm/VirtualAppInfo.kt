package com.atlas.virtualspace.core.pm

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.atlas.virtualspace.data.database.Converters

/**
 * Persistent representation of a virtual app stored in the Room database.
 *
 * Every installed virtual app occupies exactly one row. The [packageName] is the
 * natural primary key because the virtual space enforces a single-install-per-package
 * policy (re-installs replace the previous entry via [OnConflictStrategy.REPLACE]).
 */
@Entity(tableName = "virtual_apps")
@TypeConverters(Converters::class)
data class VirtualAppInfo(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    /** Absolute path inside the virtual filesystem where the APK is stored. */
    val apkPath: String,
    /** Paths for split APKs (empty for monolithic installs). */
    val splitApkPaths: List<String>,
    /** Absolute path to the OBB directory, or null if none. */
    val obbPath: String?,
    /** Absolute path to the extracted native-lib directory, or null if none. */
    val nativeLibPath: String?,
    val installTime: Long,
    val updateTime: Long,
    val targetSdkVersion: Int,
    val minSdkVersion: Int,
    val is64Bit: Boolean,
    val isGame: Boolean,
    val isEnabled: Boolean = true,
    /** Fully-qualified component name of the default launch activity, or null. */
    val launchActivity: String?,
    val permissions: List<String>,
    val installType: InstallType,
    val dataUsageBytes: Long = 0,
    val lastLaunchTime: Long = 0,
    val launchCount: Int = 0,
    /**
     * True once the APK has been successfully installed on the real device
     * (either silently via Shizuku or by the user accepting the system installer dialog).
     *
     * After this is true, subsequent taps on "Launch" skip the install dialog
     * entirely and go straight to [tryDirectLaunch] in VirtualStubActivity,
     * so the user never sees the "Do you want to install?" prompt again.
     *
     * Default is false (newly imported APKs are not on the real device yet).
     */
    val isInstalledOnDevice: Boolean = false
)
