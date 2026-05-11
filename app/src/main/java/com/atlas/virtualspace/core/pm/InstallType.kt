package com.atlas.virtualspace.core.pm

/**
 * Represents the method by which a virtual app was installed.
 *
 * APK   – Direct installation from a single .apk file.
 * XAPK  – Installation from an .xapk bundle (ZIP containing base + split APKs and optional OBB).
 * CLONE – Cloned from an app already installed on the device.
 * IMPORT – Imported from an external file picked by the user (e.g. via SAF or file manager).
 */
enum class InstallType(val displayName: String) {
    APK("APK"),
    XAPK("XAPK"),
    CLONE("Clone"),
    IMPORT("Import")
}
