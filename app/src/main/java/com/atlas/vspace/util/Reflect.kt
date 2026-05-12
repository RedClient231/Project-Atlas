package com.atlas.vspace.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Thin reflection helpers used across the virtual-space engine.
 *
 * Every system-level hook we install (ServiceManager cache, ActivityThread,
 * Instrumentation, H.mCallback) requires reading or writing private fields
 * on framework classes. This object centralises those primitives so each
 * hook site stays readable.
 *
 * All methods swallow reflection exceptions and return null on failure.
 * Callers are expected to treat null as "hook target not available on this
 * API level" and gracefully degrade.
 */
internal object Reflect {

    fun fieldOrNull(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val f = current.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    fun methodOrNull(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val m = current.getDeclaredMethod(name, *params)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    fun <T> readField(instance: Any?, clazz: Class<*>, name: String): T? {
        val f = fieldOrNull(clazz, name) ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            f.get(instance) as? T
        } catch (_: IllegalAccessException) {
            null
        }
    }

    fun writeField(instance: Any?, clazz: Class<*>, name: String, value: Any?): Boolean {
        val f = fieldOrNull(clazz, name) ?: return false
        return try {
            f.set(instance, value)
            true
        } catch (_: IllegalAccessException) {
            false
        }
    }

    fun classOrNull(fqcn: String): Class<*>? = try {
        Class.forName(fqcn)
    } catch (_: ClassNotFoundException) {
        null
    }
}
