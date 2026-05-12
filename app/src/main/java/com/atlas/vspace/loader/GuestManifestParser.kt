package com.atlas.vspace.loader

import com.atlas.vspace.model.GuestAppManifest
import net.dongliu.apk.parser.ApkFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import timber.log.Timber
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * Parses just enough of a guest APK's `AndroidManifest.xml` to build a
 * [GuestAppManifest].
 *
 * Uses `apk-parser` to decode the binary XML into text, then walks the XML
 * with SAX to find:
 * - `package`, `android:versionName`, `android:versionCode`
 * - The activity with `android.intent.action.MAIN` + `android.intent.category.LAUNCHER`
 * - That activity's theme (if declared)
 * - `android:targetSdkVersion`
 */
object GuestManifestParser {

    fun parse(
        apkPath: String,
        splitApkPaths: List<String> = emptyList(),
        nativeLibDir: String? = null,
    ): GuestAppManifest? {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Timber.w("[ManifestParser] APK missing: %s", apkPath)
            return null
        }

        return try {
            ApkFile(apkFile).use { apk ->
                val meta = apk.apkMeta ?: return null
                val xml = apk.manifestXml ?: return null

                val walker = LauncherActivityWalker(meta.packageName ?: "")
                SAXParserFactory.newInstance().newSAXParser()
                    .parse(xml.byteInputStream(Charsets.UTF_8), walker)

                val mainActivity = walker.launcherActivityName
                    ?: return null.also {
                        Timber.w("[ManifestParser] No launcher activity in %s", apkPath)
                    }

                GuestAppManifest(
                    packageName = meta.packageName ?: "",
                    appName = meta.label ?: (meta.packageName ?: "").substringAfterLast('.'),
                    versionName = meta.versionName ?: "unknown",
                    versionCode = meta.versionCode?.toLong() ?: 0L,
                    mainActivity = mainActivity,
                    apkPath = apkPath,
                    splitApkPaths = splitApkPaths,
                    nativeLibDir = nativeLibDir,
                    targetSdk = meta.targetSdkVersion?.toIntOrNull() ?: 21,
                    mainActivityTheme = walker.launcherActivityTheme,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "[ManifestParser] Failed to parse %s", apkPath)
            null
        }
    }

    /**
     * SAX handler that walks an `AndroidManifest.xml` text representation
     * and extracts the launcher activity component name + theme.
     */
    private class LauncherActivityWalker(private val packageName: String) : DefaultHandler() {

        var launcherActivityName: String? = null
            private set
        var launcherActivityTheme: Int = 0
            private set

        private var currentActivityName: String? = null
        private var currentActivityTheme: Int = 0
        private var inIntentFilter = false
        private var seenMain = false
        private var seenLauncher = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?,
        ) {
            val tag = (qName ?: localName)?.lowercase() ?: return
            when (tag) {
                "activity", "activity-alias" -> {
                    currentActivityName = attributes?.getValue("android:name")
                        ?: attributes?.getValue("name")
                    val rawTheme = attributes?.getValue("android:theme")
                        ?: attributes?.getValue("theme")
                    currentActivityTheme = parseThemeRef(rawTheme)
                    inIntentFilter = false
                    seenMain = false
                    seenLauncher = false
                }
                "intent-filter" -> inIntentFilter = true
                "action" -> {
                    if (inIntentFilter) {
                        val name = attributes?.getValue("android:name")
                            ?: attributes?.getValue("name")
                        if (name == "android.intent.action.MAIN") seenMain = true
                    }
                }
                "category" -> {
                    if (inIntentFilter) {
                        val name = attributes?.getValue("android:name")
                            ?: attributes?.getValue("name")
                        if (name == "android.intent.category.LAUNCHER") seenLauncher = true
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = (qName ?: localName)?.lowercase() ?: return
            when (tag) {
                "intent-filter" -> {
                    if (seenMain && seenLauncher && currentActivityName != null && launcherActivityName == null) {
                        launcherActivityName = resolveActivity(currentActivityName!!, packageName)
                        launcherActivityTheme = currentActivityTheme
                    }
                    inIntentFilter = false
                    seenMain = false
                    seenLauncher = false
                }
                "activity", "activity-alias" -> {
                    currentActivityName = null
                    currentActivityTheme = 0
                }
            }
        }

        private fun resolveActivity(name: String, pkg: String) = when {
            name.startsWith(".") -> "$pkg$name"
            !name.contains(".") -> "$pkg.$name"
            else -> name
        }

        /**
         * `apk-parser` renders theme refs as either "@0x7f0b0001" or the
         * resource id as a decimal string. We only need the numeric id —
         * if we can't parse it we return 0, which means "use default theme".
         */
        private fun parseThemeRef(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0
            val cleaned = raw.removePrefix("@")
            if (cleaned.startsWith("0x")) {
                return cleaned.substring(2).toIntOrNull(16) ?: 0
            }
            return cleaned.toIntOrNull() ?: 0
        }
    }
}
