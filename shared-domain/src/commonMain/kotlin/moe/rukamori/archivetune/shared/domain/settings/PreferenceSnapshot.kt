/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

/**
 * An immutable read of persisted preferences. A settings transition reads one snapshot and writes through a
 * [PreferenceEditor], so the whole change is computed from one consistent view and then applied as one edit.
 */
class PreferenceSnapshot internal constructor(
    values: Map<PreferenceKey<*>, Any>,
) {
    private val values = values.mapValues { (key, value) -> immutablePreferenceValue(key, value) }
    operator fun <T : Any> get(key: PreferenceKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return (values[key]?.let { immutablePreferenceValue(key, it) }) as T?
    }

    operator fun <T : Any> get(
        key: PreferenceKey<T>,
        defaultValue: T,
    ): T = get(key) ?: defaultValue

    val keys: Set<PreferenceKey<*>> get() = values.keys.toSet()

    fun toEditor(): PreferenceEditor = PreferenceEditor(values.toMutableMap())

    companion object {
        val EMPTY: PreferenceSnapshot = PreferenceSnapshot(emptyMap())

        fun of(vararg entries: Pair<PreferenceKey<*>, Any>): PreferenceSnapshot = PreferenceSnapshot(entries.toMap())
    }
}

/**
 * The write side of a settings transition. Setting a key to null removes it, the same as DataStore's `remove`, so an
 * absent key and a removed key read the same way.
 */
class PreferenceEditor internal constructor(
    private val values: MutableMap<PreferenceKey<*>, Any>,
) {
    operator fun <T : Any> get(key: PreferenceKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return (values[key]?.let { immutablePreferenceValue(key, it) }) as T?
    }

    operator fun <T : Any> get(
        key: PreferenceKey<T>,
        defaultValue: T,
    ): T = get(key) ?: defaultValue

    operator fun <T : Any> set(
        key: PreferenceKey<T>,
        value: T?,
    ) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = immutablePreferenceValue(key, value)
        }
    }

    fun snapshot(): PreferenceSnapshot = PreferenceSnapshot(values.toMap())
}

/** Keep caller-owned mutable sets out of snapshots, including the untyped backup import boundary. */
private fun immutablePreferenceValue(key: PreferenceKey<*>, value: Any): Any {
    val valid = when (key.kind) {
        PreferenceKind.BOOLEAN -> value is Boolean
        PreferenceKind.STRING -> value is String
        PreferenceKind.FLOAT -> value is Float
        PreferenceKind.INT -> value is Int
        PreferenceKind.LONG -> value is Long
        PreferenceKind.DOUBLE -> value is Double
        PreferenceKind.STRING_SET -> value is Set<*> && value.all { it is String }
    }
    require(valid) { "Preference value does not match its key kind: ${key.kind}" }
    return if (value is Set<*>) value.toSet() else value
}
