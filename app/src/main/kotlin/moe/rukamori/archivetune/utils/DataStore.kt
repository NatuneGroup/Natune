/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AppleMusicSettingsMigratedKey
import moe.rukamori.archivetune.constants.AppleMusicExperienceKey
import moe.rukamori.archivetune.constants.AutomixEnabledKey
import moe.rukamori.archivetune.constants.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.constants.DirectUsbDriverKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CustomFontUriKey
import moe.rukamori.archivetune.constants.FontPreferenceKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.MaxImageCacheSizeKey
import moe.rukamori.archivetune.constants.MaxSongCacheSizeKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.constants.UiScaleFactorKey
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.UpdateChannelKey
import moe.rukamori.archivetune.constants.UseSystemFontKey
import moe.rukamori.archivetune.constants.UsbExclusiveAudioKey
import moe.rukamori.archivetune.constants.YouTubeMusicRegionKey
import moe.rukamori.archivetune.constants.canaryChannelMigration
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.shared.domain.settings.migrateAppleMusicSettings
import moe.rukamori.archivetune.shared.domain.settings.normalizeSettingsConflicts
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { _ ->
        listOf(
            HistoryDurationMigration,
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[AppleMusicExperienceKey] == true && currentData[AppleMusicSettingsMigratedKey] != true

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.editSharedPreferences { migrateAppleMusicSettings() }

                override suspend fun cleanUp() {}
            },
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData != currentData.editSharedPreferences { normalizeSettingsConflicts() }

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.editSharedPreferences { normalizeSettingsConflicts() }

                override suspend fun cleanUp() {}
            },
            // Rewrite the legacy alias for the pre-release channel. Only "DAILY_NIGHTLY" triggers
            // it: an already-stored "NIGHTLY" must not match, or the migration re-runs on every
            // DataStore init for every nightly reader.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[UpdateChannelKey] == "DAILY_NIGHTLY"

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[UpdateChannelKey] = UpdateChannel.NIGHTLY.name
                    }

                override suspend fun cleanUp() {}
            },
            // The Canary channel is gone. A stored "CANARY" becomes "NIGHTLY" — the feed those
            // installs follow from now on — and the keys only that channel wrote (the About
            // easter-egg unlock flag and the canary release cache) are dropped. The decision is
            // [canaryChannelMigration]; with neither the stored value nor the legacy keys present
            // it is a no-op, so the migration runs at most once.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    canaryChannelMigration(
                        storedChannel = currentData[UpdateChannelKey],
                        presentLegacyKeyNames =
                            canaryLegacyKeys.filter { currentData.contains(it) }.map { it.name }.toSet(),
                    ).needed

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        if (currentData[UpdateChannelKey] == "CANARY") {
                            this[UpdateChannelKey] = UpdateChannel.NIGHTLY.name
                        }
                        canaryLegacyKeys.forEach { remove(it) }
                    }

                override suspend fun cleanUp() {}
            },
            // SimpMusic's lyrics renderer was a standalone boolean read only by the SimpMusic player
            // style's lyrics card; as a LyricsMode it now applies to the lyrics page under every
            // style, so a stored `true` has to be carried across.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[LEGACY_SIMPMUSIC_LYRICS_KEY] == true

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[LyricsModeKey] = LyricsMode.SIMPMUSIC.name
                        remove(LEGACY_SIMPMUSIC_LYRICS_KEY)
                    }

                override suspend fun cleanUp() {}
            },
            // Stored ARCHIVETUNE_EXTRACTOR values resolved to ANDROID_MUSIC (with login) or WEB_REMIX
            // (without); the option is gone, so rewrite stale values to WEB_REMIX and keep existing
            // users off an unknown enum value.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[PlayerStreamClientKey] in
                        setOf("ARCHIVETUNE_EXTRACTOR", "HI_RES_LOSSLESS")

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[PlayerStreamClientKey] = PlayerStreamClient.WEB_REMIX.name
                    }

                override suspend fun cleanUp() {}
            },
            // Source orders written before Spotify, Amazon and QQ became first-class entries in
            // AudioSourceConfig.DEFAULT_ORDER don't list them; the decision (pure, idempotent,
            // unit-tested) inserts the missing ones directly above YouTube and drops unknown
            // names. A blank order stays blank so it keeps tracking the defaults.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                    val raw = currentData[AudioSourceOrderKey] ?: return false
                    return AudioSourceConfig.migrateStoredOrder(raw) != raw
                }

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[AudioSourceOrderKey] = AudioSourceConfig.migrateStoredOrder(this[AudioSourceOrderKey])
                    }

                override suspend fun cleanUp() {}
            },
        )
    },
)

/** The retired `simpMusicLyrics` boolean, kept only so the migration above can read it. */
private val LEGACY_SIMPMUSIC_LYRICS_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("simpMusicLyrics")

// The keys only the removed Canary channel wrote, by literal name — the typed declarations went
// away with the channel (canonical list: constants.CanaryLegacyKeyNames).
private val canaryLegacyKeys: List<Preferences.Key<*>> =
    listOf(
        booleanPreferencesKey("canaryChannelUnlocked"),
        stringPreferencesKey("canary_releases_etag"),
        stringPreferencesKey("canary_releases_json"),
        longPreferencesKey("canary_releases_last_checked_at"),
        stringPreferencesKey("canary_releases_fingerprint"),
    )

object PreferenceStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prefs = MutableStateFlow<Preferences?>(null)

    @Volatile private var started = false

    /** Last-known values for the reads that happen before the first disk snapshot; see [start]. */
    @Volatile private var startupMirror: StartupPreferenceMirror? = null

    /** The mirror read, published as the snapshot only while the real one is still loading. */
    @Volatile private var startupFallback: Preferences? = null

    /** False in a process that never called [start]: the `:crash` process returns before it. */
    val isStarted: Boolean get() = started

    /**
     * Completes when the first snapshot has landed, so a caller that cannot tolerate a default can
     * wait for the real value.
     *
     * [start] only *launches* the collection. Until its first emission arrives, [get] returns null
     * (or the startup mirror, once a main-thread [blockUntilLoaded] published it) and every caller
     * silently takes its own default — and a caller that runs once at startup and caches the answer
     * keeps that default for the life of the process. `newImageLoader` did exactly this: on a cold
     * start it could read a null cache size and pin Coil to the built-in default, ignoring the size
     * the reader had chosen, until the app was killed.
     */
    private val firstLoad = CompletableDeferred<Unit>()

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val applicationContext = context.applicationContext
            startupMirror =
                StartupPreferenceMirror(
                    applicationContext.getSharedPreferences(STARTUP_MIRROR_PREFERENCES_NAME, Context.MODE_PRIVATE),
                )
            scope.launch {
                context.dataStore.data.collect { preferences ->
                    _prefs.value = preferences
                    startupFallback = null
                    firstLoad.complete(Unit)
                    // The collector is the catch-all writer: every store (including the many direct
                    // `dataStore.edit` calls) reaches it. `launchEdit` also mirrors its own confirmed
                    // result so a restart asked for right after the edit cannot lose it.
                    runCatching { startupMirror?.store(preferences) }
                }
            }
        }
    }

    /** Suspends until the first snapshot lands. Returns immediately once it has. */
    suspend fun awaitFirstLoad() = firstLoad.await()

    /**
     * Makes real values readable for the handful of entry points that are not suspending and must
     * not read a default — factories whose result is cached for the whole process, chiefly.
     *
     * An already-loaded snapshot always wins. On the main thread the last-known mirror is used
     * instead of parking the looper on DataStore; the bounded wait survives only for the first cold
     * start after the mirror was introduced, or after a failed/cleared mirror write, where the
     * alternative is pinning defaults for the life of the process. Background callers keep the
     * wait: blocking a worker thread is harmless and gets the real value rather than a mirror.
     */
    fun blockUntilLoaded(timeoutMs: Long = FIRST_LOAD_TIMEOUT_MS) {
        val loaded = firstLoad.isCompleted
        val storeStarted = started
        val mainThread = !loaded && storeStarted && isMainThread()
        val mirrorAvailable = mainThread && startupMirror?.hasSnapshot() == true
        when (startupLoadAction(loaded, storeStarted, mainThread, mirrorAvailable)) {
            StartupLoadAction.USE_LOADED -> Unit
            StartupLoadAction.USE_MIRROR -> publishStartupFallback()
            StartupLoadAction.WAIT -> runBlocking { withTimeoutOrNull(timeoutMs) { firstLoad.await() } }
        }
    }

    private fun publishStartupFallback() {
        if (_prefs.value != null || startupFallback != null) return
        synchronized(this) {
            if (_prefs.value != null || startupFallback != null) return
            startupFallback = runCatching { startupMirror?.load() }.getOrNull()
        }
    }

    private const val FIRST_LOAD_TIMEOUT_MS = 1_500L

    fun <T> get(key: Preferences.Key<T>): T? {
        val loaded = _prefs.value
        return if (loaded != null) loaded[key] else startupFallback?.get(key)
    }

    fun snapshot(): Preferences? = _prefs.value ?: startupFallback

    fun launchEdit(
        dataStore: DataStore<Preferences>,
        block: MutablePreferences.() -> Unit,
    ) {
        scope.launch {
            val updated =
                dataStore.edit { prefs ->
                    prefs.block()
                }
            // The collector mirrors every emission too, but a confirmed edit followed immediately by
            // the restart prompt's "Restart now" can outrun that collector; mirroring the returned
            // snapshot here keeps the next process from latching the value the edit replaced.
            runCatching { startupMirror?.store(updated) }
        }
    }
}

internal enum class StartupLoadAction { USE_LOADED, USE_MIRROR, WAIT }

/**
 * The one-read decision behind [PreferenceStore.blockUntilLoaded]. Loaded state is preferred over
 * everything; the mirror covers the main thread; a bounded wait is left for a first cold start
 * without a mirror and for background callers, where waiting cannot stall a frame.
 */
internal fun startupLoadAction(
    loaded: Boolean,
    started: Boolean,
    mainThread: Boolean,
    mirrorAvailable: Boolean,
): StartupLoadAction =
    when {
        loaded -> StartupLoadAction.USE_LOADED
        !started -> StartupLoadAction.USE_LOADED
        mainThread && mirrorAvailable -> StartupLoadAction.USE_MIRROR
        else -> StartupLoadAction.WAIT
    }

private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

private const val STARTUP_MIRROR_PREFERENCES_NAME = "preference_startup_mirror"
private const val STARTUP_MIRROR_MARKER = "#written"

/**
 * The values a process-lifetime consumer reads before DataStore's first snapshot: Coil's disk-cache
 * size, the player's cache bound, the bit-perfect sinks, the restart-scoped UI settings, the region
 * the restart prompt compares against and the offload flag the live USB route checks. A new
 * synchronous read on the startup path must add its key here, or it will start on a default.
 *
 * The type tag makes a mirrored value self-describing, so a corrupt or hand-edited entry is skipped
 * instead of surfacing as the wrong type for a key.
 */
internal val startupPreferenceTags: Map<String, Char> =
    mapOf(
        MaxImageCacheSizeKey.name to 'i',
        SmartTrimmerKey.name to 'b',
        MaxSongCacheSizeKey.name to 'i',
        BitPerfectUsbOutputKey.name to 'b',
        DirectUsbDriverKey.name to 'b',
        CrossfadeEnabledKey.name to 'b',
        AutomixEnabledKey.name to 'b',
        UsbExclusiveAudioKey.name to 'b',
        AudioOffload.name to 'b',
        UiScaleFactorKey.name to 'f',
        FontPreferenceKey.name to 's',
        UseSystemFontKey.name to 'b',
        CustomFontUriKey.name to 's',
        TabletModeEnabledKey.name to 'b',
        LiquidGlassEnabledKey.name to 'b',
        LiquidGlassNavBarEnabledKey.name to 'b',
        NavigationBarFrostedBlurKey.name to 'b',
        NavigationBarTintFrostedBlurKey.name to 'b',
        YouTubeMusicRegionKey.name to 's',
    )

/**
 * A small synchronous copy of [startupPreferenceTags], written off the collector and read on a
 * main-thread [PreferenceStore.blockUntilLoaded]. It sits in the same app-private storage as the
 * DataStore file; only a handful of scalar/string values are copied, and DataStore's own first read
 * (not this file) is what the bounded wait exists for: it can take up to 1.5 s on slow storage and
 * migrations.
 */
private class StartupPreferenceMirror(private val preferences: SharedPreferences) {
    fun hasSnapshot(): Boolean = preferences.contains(STARTUP_MIRROR_MARKER)

    fun load(): Preferences =
        decodeStartupPreferences(
            preferences.all
                .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
                .toMap(),
        )

    fun store(snapshot: Preferences) {
        val editor = preferences.edit().clear()
        encodeStartupPreferences(snapshot).forEach { (key, value) -> editor.putString(key, value) }
        editor.putString(STARTUP_MIRROR_MARKER, "1")
        editor.commit()
    }
}

internal fun encodeStartupPreferences(preferences: Preferences): Map<String, String> {
    val encoded = linkedMapOf<String, String>()
    for ((key, value) in preferences.asMap()) {
        val tag = startupPreferenceTags[key.name] ?: continue
        val raw = encodeStartupValue(tag, value) ?: continue
        encoded["$tag:${key.name}"] = raw
    }
    return encoded
}

private fun encodeStartupValue(tag: Char, value: Any): String? =
    when (tag) {
        'b' -> (value as? Boolean)?.toString()
        'i' -> (value as? Int)?.toString()
        // Raw bits keep a float exact; a decimal round trip can move 0.85f by one ulp.
        'f' -> (value as? Float)?.toRawBits()?.toString()
        's' -> value as? String
        else -> null
    }

internal fun decodeStartupPreferences(encoded: Map<String, String>): Preferences {
    val decoded = mutablePreferencesOf()
    for ((rawKey, rawValue) in encoded) {
        val separator = rawKey.indexOf(':')
        if (separator != 1 || rawKey.length < 3) continue
        val tag = rawKey[0]
        val name = rawKey.substring(separator + 1)
        if (startupPreferenceTags[name] != tag) continue
        when (tag) {
            'b' -> rawValue.toBooleanStrictOrNull()?.let { decoded[booleanPreferencesKey(name)] = it }
            'i' -> rawValue.toIntOrNull()?.let { decoded[intPreferencesKey(name)] = it }
            'f' -> rawValue.toIntOrNull()?.let { decoded[floatPreferencesKey(name)] = Float.fromBits(it) }
            's' -> decoded[stringPreferencesKey(name)] = rawValue
        }
    }
    return decoded.toPreferences()
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            null
        } else {
            blockingRead(key)
        }

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            defaultValue
        } else {
            blockingRead(key) ?: defaultValue
        }

/**
 * The off-main-thread fallback both [get] overloads share. It waits on the single shared initial
 * load instead of enqueuing an independent read per call: on a fresh launch dozens of these fire
 * before the snapshot lands, and each cold `data.first()` queued behind the others, serialising
 * callers into multi-second startup jank. A process that never started [PreferenceStore] reads the
 * store directly, because its snapshot is never coming. Bounded, so a blocking get never hangs.
 */
private fun <T> DataStore<Preferences>.blockingRead(key: Preferences.Key<T>): T? =
    runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(1500) {
            if (PreferenceStore.isStarted) {
                PreferenceStore.awaitFirstLoad()
                PreferenceStore.get(key)
            } else {
                data.first()[key]
            }
        }
    }

suspend fun <T> DataStore<Preferences>.getAsync(key: Preferences.Key<T>): T? = data.first()[key]

suspend fun <T> DataStore<Preferences>.getAsync(
    key: Preferences.Key<T>,
    defaultValue: T,
): T = data.first()[key] ?: defaultValue

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    // Seeded from the in-memory snapshot, not from [defaultValue]. DataStore's flow is
    // asynchronous, so seeding with the default paints one frame of the default before the stored
    // value arrives — visible as a flash of the wrong screen whenever a preference chooses WHICH ui
    // to show (the Home tab briefly rendering the YouTube page before the stored Spotify one).
    // [PreferenceStore] keeps a hot copy of the whole Preferences object for exactly this, so the
    // first frame is already correct; the fallback still applies before the store has loaded.
    val initial = remember { PreferenceStore.get(key) ?: defaultValue }

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = initial)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    // See [rememberPreference] — same first-frame seeding, same reason.
    val initial = remember { PreferenceStore.get(key).toEnum(defaultValue = defaultValue) }

    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = initial)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value.name
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
