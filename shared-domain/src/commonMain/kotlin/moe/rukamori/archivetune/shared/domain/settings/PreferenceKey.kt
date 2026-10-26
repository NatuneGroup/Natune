package moe.rukamori.archivetune.shared.domain.settings

/** The value kinds a persisted preference can hold. DataStore stores each kind under its own key type. */
enum class PreferenceKind {
    BOOLEAN,
    STRING,
    FLOAT,
    INT,
    LONG,
    DOUBLE,
    STRING_SET,
}

/**
 * A persisted preference key: the on-disk [name] and the [kind] of value it stores.
 *
 * DataStore identifies a key by name and type together, and legacy migrations rely on that, so equality uses both.
 * This type carries no Android dependency; the platform adapter maps it onto its own key type.
 */
sealed class PreferenceKey<T : Any>(
    val name: String,
    val kind: PreferenceKind,
) {
    override fun equals(other: Any?): Boolean = other is PreferenceKey<*> && other.kind == kind && other.name == name

    override fun hashCode(): Int = 31 * name.hashCode() + kind.hashCode()

    override fun toString(): String = "${kind.name}($name)"
}

class BooleanPreferenceKey(
    name: String,
) : PreferenceKey<Boolean>(name, PreferenceKind.BOOLEAN)

class StringPreferenceKey(
    name: String,
) : PreferenceKey<String>(name, PreferenceKind.STRING)

class FloatPreferenceKey(
    name: String,
) : PreferenceKey<Float>(name, PreferenceKind.FLOAT)

class IntPreferenceKey(
    name: String,
) : PreferenceKey<Int>(name, PreferenceKind.INT)

class LongPreferenceKey(
    name: String,
) : PreferenceKey<Long>(name, PreferenceKind.LONG)

class DoublePreferenceKey(
    name: String,
) : PreferenceKey<Double>(name, PreferenceKind.DOUBLE)

class StringSetPreferenceKey(
    name: String,
) : PreferenceKey<Set<String>>(name, PreferenceKind.STRING_SET)
