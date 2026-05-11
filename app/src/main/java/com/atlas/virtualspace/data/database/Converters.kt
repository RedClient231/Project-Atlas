package com.atlas.virtualspace.data.database

import androidx.room.TypeConverter
import com.atlas.virtualspace.core.pm.InstallType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room [TypeConverter]s that bridge between complex Kotlin types and the
 * primitive column types that SQLite can persist.
 *
 * All serialisation uses [Gson] for consistency and to avoid manual JSON
 * string manipulation.
 */
class Converters {

    private val gson = Gson()

    // ───────────────── List<String> ↔ JSON string ─────────────────────────────

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }

    // ───────────────── InstallType ↔ String ───────────────────────────────────

    @TypeConverter
    fun fromInstallType(value: InstallType): String {
        return value.name
    }

    @TypeConverter
    fun toInstallType(value: String): InstallType {
        return try {
            InstallType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            // Backward compatibility: default to APK for unknown values
            InstallType.APK
        }
    }
}
