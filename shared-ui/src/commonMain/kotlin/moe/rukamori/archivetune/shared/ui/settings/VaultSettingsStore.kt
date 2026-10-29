package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.update.canaryChannelMigration

/** Sanitized: native error messages may themselves contain a credential or command output. */
class SettingsVaultUnavailable : IllegalStateException("Secure settings storage is unavailable")

/**
 * Own the underlying settings store exclusively. Only opaque references enter it; logical values
 * exist in memory/the scoped native vault. Pass snapshot() to M7's existing category-aware codec,
 * never the underlying file: ACCOUNT export retains logical names, not device vault references.
 *
 * New immutable vault entries are prepared before the atomic metadata edit. A failed edit cannot
 * overwrite the credentials a restart still references. This is not a new crypto or backup format.
 * The host supplies collision-resistant references (for example platform UUIDs) and closes/quiesces
 * consumers before closing this adapter and its separately owned underlying store.
 */
class VaultSettingsStore private constructor(
    private val ordinary: SettingsStore,
    private val vault: SecureCredentialStore,
    private val routes: Map<StringPreferenceKey, CredentialKey>,
    private val newReference: () -> String,
    initial: PreferenceSnapshot,
    private var raw: PreferenceSnapshot,
) : SettingsStore {
    private val mutex = Mutex()
    private var closed = false
    private val state = MutableStateFlow<PreferenceSnapshot?>(initial)
    override val snapshots = state.asStateFlow()
    private val cleanupState = MutableStateFlow(0)
    /** Unreferenced vault cleanup failure count, without credential/error payloads. */
    val cleanupFailures = cleanupState.asStateFlow()

    suspend fun snapshot(): PreferenceSnapshot = mutex.withLock { check(!closed); requireNotNull(state.value) }

    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange = mutex.withLock {
        check(!closed)
        val before = requireNotNull(state.value)
        persist(before, before.toEditor().apply(change).snapshot())
    }

    /** Logical restore/rollback goes through the same vault routing, including removals. */
    suspend fun replaceAll(snapshot: PreferenceSnapshot): SettingsChange = mutex.withLock {
        check(!closed)
        persist(requireNotNull(state.value), snapshot)
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closed = true; state.value = null }
    }

    private suspend fun persist(before: PreferenceSnapshot, after: PreferenceSnapshot): SettingsChange =
        withContext(NonCancellable) {
            require(after.keys.none { it.name.startsWith(REFERENCE_PREFIX) }) { "Vault metadata is not a logical preference" }
            require(after.keys.none { key -> routes.keys.any { it.name == key.name } && key !is StringPreferenceKey })
            val next = after.toEditor()
            val staged = mutableListOf<CredentialKey>()
            val obsolete = mutableListOf<CredentialKey>()
            try {
                routes.forEach { (key, route) ->
                    val metadata = referenceKey(key)
                    val oldReference = raw[metadata]
                    val value = after[key]
                    next[key] = null
                    if (value != null && before[key] == value && oldReference != null) {
                        next[metadata] = oldReference
                    } else {
                        if (value != null) {
                            val reference = newReference()
                            require(reference.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid opaque vault reference" }
                            val entry = route.copy(name = "${route.name}/$reference")
                            require(entry !in staged)
                            secure { check(vault.read(entry) == null); Unit }
                            staged += entry
                            secure { vault.write(entry, value) }
                            next[metadata] = reference
                        }
                        if (oldReference != null) obsolete += route.copy(name = "${route.name}/$oldReference")
                    }
                }
                val nextRaw = next.snapshot()
                if (SettingsChange(raw, nextRaw).changedKeys.isNotEmpty()) {
                    ordinary.edit {
                        check(SettingsChange(raw, snapshot()).changedKeys.isEmpty()) { "Settings owner changed outside vault adapter" }
                        replaceContents(nextRaw)
                    }
                }
                raw = nextRaw
                if (SettingsChange(before, after).changedKeys.isNotEmpty()) state.value = after
            } catch (failure: Throwable) {
                staged.forEach { clean(it) }
                throw failure
            }
            obsolete.forEach { clean(it) }
            SettingsChange(before, after)
        }

    private suspend fun clean(key: CredentialKey) {
        try { vault.remove(key) }
        catch (_: Exception) { cleanupState.value++ }
    }

    companion object {
        private const val REFERENCE_PREFIX = "secure_store_settings_ref_"
        private fun referenceKey(key: StringPreferenceKey) = StringPreferenceKey(REFERENCE_PREFIX + key.name)

        suspend fun open(
            ordinary: SettingsStore,
            vault: SecureCredentialStore,
            newReference: () -> String,
            routes: Map<StringPreferenceKey, CredentialKey> = settingsCredentialRoutes(),
        ): VaultSettingsStore {
            val routes = routes.toMap()
            val raw = requireNotNull(ordinary.snapshots.value) { "Wait for the actual settings read before opening the vault adapter" }
            require(routes.values.distinct().size == routes.size)
            require(routes.keys.none { it.name.startsWith(REFERENCE_PREFIX) })
            val metadata = routes.keys.associateBy { referenceKey(it) }
            require(raw.keys.none { it.name.startsWith(REFERENCE_PREFIX) && it !in metadata })
            val logical = raw.toEditor()
            logical.migrateAppleMusicSettings()
            // The Canary channel is gone: a stored CANARY selection becomes NIGHTLY once, and its retired
            // cache keys drop — the same one-shot DataStore migration the app runs, applied at the funnel
            // every host's settings pass through.
            canaryChannelMigration(raw[UpdateChannelKey], raw.keys.map { it.name }.toSet()).let { migration ->
                if (migration.channelToNightly) logical[UpdateChannelKey] = UpdateChannel.NIGHTLY.name
                migration.dropKeyNames.forEach { name ->
                    raw.keys.firstOrNull { it.name == name }?.let { logical[it] = null }
                }
            }
            metadata.forEach { (referenceKey, key) ->
                val reference = raw[referenceKey] ?: return@forEach
                require(raw[key] == null) { "Ambiguous plaintext and vault reference" }
                require(reference.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val route = routes.getValue(key)
                logical[key] = secure { vault.read(route.copy(name = "${route.name}/$reference")) }
                    ?: throw SettingsVaultUnavailable()
                logical[referenceKey] = null
            }
            val initial = logical.snapshot()
            return VaultSettingsStore(ordinary, vault, routes, newReference, initial, raw).also {
                // Explicitly migrate existing logical credentials before exposing any consumers.
                it.persist(initial, initial)
            }
        }

        private suspend fun <T> secure(operation: suspend () -> T): T = try { operation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SettingsVaultUnavailable() }
    }
}

private val credentialPreferenceNames = settingsCredentialRoutes().keys.map { it.name }.toSet()

/** Enforced by both owned disk adapters: a generic preference editor is never a vault fallback. */
fun requireCredentialFreePersistence(snapshot: PreferenceSnapshot) {
    require(snapshot.keys.none { it.name in credentialPreferenceNames }) {
        "Credential settings require secure storage"
    }
}

@Suppress("UNCHECKED_CAST")
private fun PreferenceEditor.replaceContents(snapshot: PreferenceSnapshot) {
    this.snapshot().keys.forEach { this[it as PreferenceKey<Any>] = null }
    snapshot.keys.forEach { this[it as PreferenceKey<Any>] = snapshot[it] }
}

/** Routing only, not backup selection policy. Catalog/streaming/pool never share a vault scope. */
fun settingsCredentialRoutes(): Map<StringPreferenceKey, CredentialKey> = buildMap {
    fun bind(provider: String, scope: CredentialScope = CredentialScope.PERSONAL, vararg names: String) {
        names.forEach { put(StringPreferenceKey(it), CredentialKey(provider, scope, it)) }
    }
    bind("ai", names = arrayOf("ai_api_key"))
    bind("deepl", names = arrayOf("deeplApiKey"))
    bind("openrouter", names = arrayOf("openRouterApiKey"))
    bind("paxsenix", names = arrayOf("paxsenixApiKey"))
    bind("youtube", names = arrayOf("innerTubeCookie", "innerTubeOAuthToken", "innerTubeOAuthRefreshToken",
        "visitorData", "dataSyncId", "poToken", "poTokenGvs", "poTokenPlayer", "savedAccounts"))
    bind("spotify", CredentialScope.SPOTIFY_CATALOG, "spotify_sp_dc", "spotify_sp_key", "spotify_access_token")
    bind("spotify", CredentialScope.SPOTIFY_STREAMING, "spotifyStreamSpDc", "spotifyStreamSpKey", "spotifyStreamAccessToken")
    bind("tidal", names = arrayOf("tidalAccessToken", "tidalRefreshToken", "tidalCookie"))
    bind("qobuz", names = arrayOf("qobuzTokens"))
    bind("deezer", names = arrayOf("deezerArl"))
    bind("apple", names = arrayOf("appleMusicMediaUserToken", "appleMusicDevToken"))
    bind("amazon", names = arrayOf("amazonTurnstileJwt", "amazonBypassToken"))
    bind("qq", names = arrayOf("qqMusicMusickey", "qqMusicCredential"))
    bind("discord", names = arrayOf("discordToken", "discordRefreshToken"))
    bind("lastfm", names = arrayOf("lastfmSession", "lastfmApiKeyOverride", "lastfmSecretOverride",
        "librefmApiKeyOverride", "librefmSecretOverride", "customScrobbleApiKeyOverride", "customScrobbleSecretOverride"))
    bind("listenbrainz", names = arrayOf("listenbrainz_token"))
    bind("listen_together", names = arrayOf("listenTogetherSessionToken"))
    bind("proxy", names = arrayOf("proxyUsername", "proxyPassword"))
    listOf("Tidal", "Qobuz", "Deezer", "Amazon").forEach {
        bind(it.lowercase(), CredentialScope.PLAYBACK_POOL, "pool${it}Accounts")
    }
    // Apple's pool list keeps its baseline key but routes under the canonical "apple" provider its personal slot uses.
    bind("apple", CredentialScope.PLAYBACK_POOL, "poolAppleMusicAccounts")
    // Account identity, generation and cookie rows are credentials too: plaintext persistence refuses them.
    putAll(accountCredentialRoutes())
}
