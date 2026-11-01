package moe.rukamori.archivetune.shared.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.BackupPreferencePolicy
import moe.rukamori.archivetune.shared.data.persistence.PORTABLE_STATE_SCHEMA_VERSION
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.DoublePreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.FloatPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.IntPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.LongPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKind
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.StringSetPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.normalizeRestoredSettings

/**
 * The portable backup archive every native host exchanges. Android archives carry the schema-36 `song.db`; the
 * portable hosts keep their library in the portable state database, so they write their own ZIP:
 *
 * - `backup.json`: the manifest (format name and version, platform, portable schema version, creation time).
 * - `portable-state.db`: a VACUUM INTO copy of the library, queue and history, present with LIBRARY.
 * - `settings.json`: typed logical preferences, filtered by [BackupPreferencePolicy] for ACCOUNT and SETTINGS.
 *
 * Every entry name, size and count is bounded before anything is written, and an archive is inspected again before a
 * restore. Vault references never enter the archive: account values are exported by their logical names and go back
 * through the vault on restore. The format name stays `natune.desktop.backup` because the desktop host wrote it
 * first; both platforms read each other's archives.
 */
object PortableBackupFormat {
    const val FORMAT = "natune.desktop.backup"
    const val FORMAT_VERSION = 1
    const val PLATFORM_DESKTOP = "DESKTOP"
    const val PLATFORM_IOS = "IOS"
    const val MANIFEST = "backup.json"
    const val DATABASE = "portable-state.db"
    const val SETTINGS = "settings.json"

    const val MAX_MANIFEST_BYTES = 64L * 1024
    const val MAX_SETTINGS_BYTES = 10L * 1024 * 1024
    const val MAX_DATABASE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 600L * 1024 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_PREFERENCES = 10_000

    private val PLATFORMS = setOf(PLATFORM_DESKTOP, PLATFORM_IOS)

    private val json = Json { ignoreUnknownKeys = true }

    fun manifest(platform: String, createdAtMillis: Long): ByteArray {
        require(platform in PLATFORMS) { "Unknown backup platform" }
        return buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("platform", platform)
            put("databaseVersion", PORTABLE_STATE_SCHEMA_VERSION)
            put("createdAtMillis", createdAtMillis)
        }.toString().encodeToByteArray()
    }

    /** Checks the manifest. A file from another app or platform is not a backup; a damaged or newer one is corrupted. */
    fun checkManifest(bytes: ByteArray) {
        val manifest = try {
            json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        if (manifest.string("format") != FORMAT || manifest.string("platform") !in PLATFORMS) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        }
        val version = (manifest["formatVersion"] as? JsonPrimitive)?.intOrNull
        val database = (manifest["databaseVersion"] as? JsonPrimitive)?.intOrNull
        if (version == null || version !in 1..FORMAT_VERSION || database == null || database !in 1..PORTABLE_STATE_SCHEMA_VERSION) {
            throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        }
    }

    fun encodeSettings(snapshot: PreferenceSnapshot): ByteArray = buildJsonObject {
        put("formatVersion", FORMAT_VERSION)
        put("entries", buildJsonArray {
            snapshot.keys.sortedWith(compareBy({ it.name }, { it.kind.name })).forEach { key ->
                add(buildJsonObject {
                    put("name", key.name)
                    put("kind", key.kind.name)
                    put("value", valueOf(snapshot, key))
                })
            }
        })
    }.toString().encodeToByteArray()

    /** Decodes typed preferences. Any unknown kind, mismatched value or duplicate key makes the whole file corrupted. */
    fun decodeSettings(bytes: ByteArray): PreferenceSnapshot {
        try {
            val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
            val entries = root["entries"] as JsonArray
            require(entries.size <= MAX_PREFERENCES)
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            val seen = HashSet<PreferenceKey<*>>()
            entries.forEach { element ->
                val entry = element as JsonObject
                val name = requireNotNull(entry.string("name")).also { require(it.isNotEmpty() && it.length <= 1024) }
                val kind = PreferenceKind.valueOf(requireNotNull(entry.string("kind")))
                val value = requireNotNull(entry["value"])
                val key: PreferenceKey<*> = when (kind) {
                    PreferenceKind.BOOLEAN -> BooleanPreferenceKey(name)
                    PreferenceKind.STRING -> StringPreferenceKey(name)
                    PreferenceKind.FLOAT -> FloatPreferenceKey(name)
                    PreferenceKind.INT -> IntPreferenceKey(name)
                    PreferenceKind.LONG -> LongPreferenceKey(name)
                    PreferenceKind.DOUBLE -> DoublePreferenceKey(name)
                    PreferenceKind.STRING_SET -> StringSetPreferenceKey(name)
                }
                require(seen.add(key)) { "Duplicate preference" }
                @Suppress("UNCHECKED_CAST")
                editor[key as PreferenceKey<Any>] = parseValue(kind, value)
            }
            return editor.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        }
    }

    /** The marker a staged restore carries: the sections the next start applies. */
    fun encodePendingMarker(categories: Set<BackupDataCategory>): ByteArray = buildJsonObject {
        put("categories", JsonArray(categories.map { JsonPrimitive(it.name) }))
    }.toString().encodeToByteArray()

    /** The staged categories a pending marker names, or null when the file does not parse. */
    fun decodePendingMarker(bytes: ByteArray): Set<BackupDataCategory>? = try {
        val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
        (root["categories"] as JsonArray).map { BackupDataCategory.valueOf((it as JsonPrimitive).content) }.toSet()
    } catch (_: Exception) {
        null
    }

    private fun valueOf(snapshot: PreferenceSnapshot, key: PreferenceKey<*>): JsonElement = when (key) {
        is BooleanPreferenceKey -> JsonPrimitive(snapshot[key])
        is StringPreferenceKey -> JsonPrimitive(snapshot[key])
        is IntPreferenceKey -> JsonPrimitive(snapshot[key])
        is LongPreferenceKey -> JsonPrimitive(snapshot[key])
        // Raw bits keep NaN, infinities and the exact value, which a decimal text would not.
        is FloatPreferenceKey -> JsonPrimitive(snapshot[key]!!.toRawBits())
        is DoublePreferenceKey -> JsonPrimitive(snapshot[key]!!.toRawBits())
        is StringSetPreferenceKey -> JsonArray(snapshot[key].orEmpty().sorted().map { JsonPrimitive(it) })
    }

    private fun parseValue(kind: PreferenceKind, value: JsonElement): Any {
        if (kind == PreferenceKind.STRING_SET) {
            val items = (value as JsonArray).map { item ->
                val primitive = item as JsonPrimitive
                require(primitive.isString)
                primitive.content
            }
            require(items.size <= MAX_PREFERENCES)
            return items.toSet()
        }
        val primitive = value as JsonPrimitive
        return when (kind) {
            PreferenceKind.BOOLEAN -> requireNotNull(primitive.booleanOrNull).also { require(!primitive.isString) }
            PreferenceKind.STRING -> primitive.content.also { require(primitive.isString) }
            PreferenceKind.INT -> requireNotNull(primitive.intOrNull).also { require(!primitive.isString) }
            PreferenceKind.LONG -> requireNotNull(primitive.longOrNull).also { require(!primitive.isString) }
            PreferenceKind.FLOAT -> Float.fromBits(requireNotNull(primitive.intOrNull).also { require(!primitive.isString) })
            PreferenceKind.DOUBLE -> Double.fromBits(requireNotNull(primitive.longOrNull).also { require(!primitive.isString) })
            PreferenceKind.STRING_SET -> error("handled above")
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** The preferences of [snapshot] that the ACCOUNT and SETTINGS choices in [selected] export, or null for neither. */
fun settingsSubset(snapshot: PreferenceSnapshot, selected: Set<BackupDataCategory>): PreferenceSnapshot? {
    val categories = selected.mapNotNull { category ->
        when (category) {
            BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
            BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
            BackupDataCategory.LIBRARY -> null
        }
    }.toSet()
    if (categories.isEmpty()) return null
    val editor = snapshot.toEditor()
    snapshot.keys.filterNot { BackupPreferencePolicy.includes(it.name, categories) }.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        editor[key as PreferenceKey<Any>] = null
    }
    return editor.snapshot()
}

/** The sections one checked archive can restore: LIBRARY with a database, ACCOUNT and SETTINGS by preference names. */
fun backupCategoriesOf(hasDatabase: Boolean, settings: PreferenceSnapshot?): Set<BackupDataCategory> = buildSet {
    if (hasDatabase) add(BackupDataCategory.LIBRARY)
    settings?.keys?.forEach { key ->
        when {
            BackupPreferencePolicy.includes(key.name, setOf(BackupCategory.ACCOUNT)) -> add(BackupDataCategory.ACCOUNT)
            BackupPreferencePolicy.includes(key.name, setOf(BackupCategory.SETTINGS)) -> add(BackupDataCategory.SETTINGS)
        }
    }
}

/**
 * Merges the restored ACCOUNT and SETTINGS preferences into [current], keeping every preference the archive does not
 * own (device-only keys, vault metadata and the sections that were not chosen), then normalizes the result.
 */
fun mergedRestoredSettings(
    current: PreferenceSnapshot,
    restored: PreferenceSnapshot,
    owned: Set<BackupCategory>,
): PreferenceSnapshot {
    val editor = current.toEditor()
    current.keys.filter { BackupPreferencePolicy.includes(it.name, owned) }.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        editor[key as PreferenceKey<Any>] = null
    }
    restored.keys.filter { BackupPreferencePolicy.includes(it.name, owned) }.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        editor[key as PreferenceKey<Any>] = restored[key as PreferenceKey<Any>]
    }
    editor.normalizeRestoredSettings(
        restored.keys.filter { BackupPreferencePolicy.includes(it.name, owned) }.map { it.name }.toSet(),
    )
    return editor.snapshot()
}
