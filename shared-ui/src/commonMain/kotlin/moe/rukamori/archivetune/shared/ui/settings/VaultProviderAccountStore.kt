package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PendingProviderAccount
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.ProviderAccountCommitFailure
import moe.rukamori.archivetune.shared.domain.platform.ProviderAccountCommitPort
import moe.rukamori.archivetune.shared.domain.platform.ProviderAccountCommitResult
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey

/**
 * One provider account slot. [credentialNames] are the logical credentials that only this account owns. They are the
 * only names a commit may write or clear. Saved-account switcher lists, PO tokens and visitor data belong to other
 * flows and never ride along in an account commit.
 */
private class AccountSlot(
    val provider: String,
    val scope: CredentialScope,
    val credentialNames: List<String>,
    // Only the Spotify catalog and streaming pair refuse the same provider account across scopes. Other providers
    // get no cross-scope rule, so a Tidal or Qobuz account never gains a restriction it was not given explicitly.
    val sameAccountRefusedBy: Set<CredentialScope> = emptySet(),
) {
    val slotKey: String = "$provider/${scope.name}"

    /** The scope string that native accesses carry for this slot. Callers pass it through; only this store maps it back. */
    val accountScope: String = scope.name
    val idKey = StringPreferenceKey("account.$provider.${scope.name}.id")
    val generationKey = StringPreferenceKey("account.$provider.${scope.name}.generation")
    val cookiesKey = StringPreferenceKey("account.$provider.${scope.name}.cookies")

    /** The identity recorded for this slot, or null when no account is accepted there. */
    fun acceptedIdentity(snapshot: PreferenceSnapshot): ProviderIdentity? {
        val accountId = snapshot[idKey]?.takeIf { it.isNotBlank() } ?: return null
        return ProviderIdentity(provider, scope, accountId)
    }

    /**
     * The generation of the last commit or sign-out. Null before the slot was ever used or when the stored value is
     * malformed, so a read fails closed and a later commit or sign-out can still replace the slot.
     */
    fun storedGeneration(snapshot: PreferenceSnapshot): Long? =
        snapshot[generationKey]?.toLongOrNull()?.takeIf { it >= 0L }
}

/**
 * Registered account slots, one per retained native provider scope. Each slot lists only the single-account secrets
 * that one account owns, so one vault edit can replace or clear the whole account. Non-secret companion settings
 * (for example a storefront) are not account secrets and keep their own flows.
 *
 * PLAYBACK_POOL slots are deliberately absent. The existing pool keys (`pool<Provider>Accounts`) are PoolAccountManager
 * feed caches that can hold several accounts, so one account commit must never overwrite them. A pool slot needs its
 * own single-account credential names first, decided by the pool owner.
 *
 * A function rather than a top-level value: settings route construction in another file reads these slots during that
 * file's own initialization, so no cross-file global initialization order is relied upon.
 */
private fun accountSlots(): List<AccountSlot> = listOf(
    AccountSlot(
        provider = "youtube",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("innerTubeCookie", "innerTubeOAuthToken", "innerTubeOAuthRefreshToken", "dataSyncId"),
    ),
    AccountSlot(
        provider = "spotify",
        scope = CredentialScope.SPOTIFY_CATALOG,
        credentialNames = listOf("spotify_sp_dc", "spotify_sp_key", "spotify_access_token"),
        sameAccountRefusedBy = setOf(CredentialScope.SPOTIFY_STREAMING),
    ),
    AccountSlot(
        provider = "spotify",
        scope = CredentialScope.SPOTIFY_STREAMING,
        credentialNames = listOf("spotifyStreamSpDc", "spotifyStreamSpKey", "spotifyStreamAccessToken"),
        sameAccountRefusedBy = setOf(CredentialScope.SPOTIFY_CATALOG),
    ),
    // Provider strings are the ContentProvider ids that native admission passes. Spotify streaming is the exception:
    // it stays under "spotify", not ContentProvider.SPOTIFY_STREAMING.id, because the same-account refusal compares
    // identities of a single provider.
    AccountSlot(
        provider = "tidal",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("tidalAccessToken", "tidalRefreshToken", "tidalCookie"),
    ),
    AccountSlot(
        provider = "qobuz",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("qobuzTokens"),
    ),
    AccountSlot(
        provider = "deezer",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("deezerArl"),
    ),
    AccountSlot(
        provider = "apple",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("appleMusicMediaUserToken", "appleMusicDevToken"),
    ),
    AccountSlot(
        provider = "amazon",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("amazonTurnstileJwt", "amazonBypassToken"),
    ),
    AccountSlot(
        provider = "qq",
        scope = CredentialScope.PERSONAL,
        credentialNames = listOf("qqMusicMusickey", "qqMusicCredential"),
    ),
)

/**
 * Credential routes for every account slot: its secrets, identity, generation and cookie rows. The secrets are routed
 * from the same list that commits write, so a registered secret can never persist as plaintext.
 * [settingsCredentialRoutes] includes them.
 */
internal fun accountCredentialRoutes(): Map<StringPreferenceKey, CredentialKey> = buildMap {
    accountSlots().forEach { slot ->
        slot.credentialNames.forEach { name ->
            put(StringPreferenceKey(name), CredentialKey(slot.provider, slot.scope, name))
        }
        put(slot.idKey, CredentialKey(slot.provider, slot.scope, "accountId"))
        put(slot.generationKey, CredentialKey(slot.provider, slot.scope, "accountGeneration"))
        put(slot.cookiesKey, CredentialKey(slot.provider, slot.scope, "accountCookies"))
    }
}

private fun accountSlotFor(provider: String, scope: CredentialScope): AccountSlot? =
    accountSlots().firstOrNull { it.provider == provider && it.scope == scope }

private fun accountSlotForScope(provider: String, accountScope: String): AccountSlot? =
    accountSlots().firstOrNull { it.provider == provider && it.accountScope == accountScope }

/**
 * The credential scope that the identity behind [accountScope] must carry for [provider], or null when the scope names no
 * account there. Telegram admits only its PERSONAL scope, and a pool token addresses only pool identities. A provider
 * match alone never admits an account.
 */
private fun expectedIdentityScope(provider: String, accountScope: String): CredentialScope? = when {
    provider == TELEGRAM_ACCOUNT_PROVIDER -> CredentialScope.PERSONAL.takeIf { accountScope == it.name }
    poolFeedIdOfScope(accountScope) != null -> CredentialScope.PLAYBACK_POOL
    else -> accountSlotForScope(provider, accountScope)?.scope
}

/**
 * The account scope string that a native access must carry for [provider] and [scope], or null when no account slot is
 * registered there. Access objects take this value unchanged, and only the store maps it back to a slot. "public" never
 * names a slot, so anonymous access cannot reach a personal account. A missing slot refuses personal preparation
 * rather than downgrading it to anonymous. The Telegram session has no credential slot, so its native PERSONAL scope is
 * named here. Playback pool entries are not named here: they are addressed by [VaultProviderAccountStore.playbackPoolEntries].
 */
fun providerAccountScope(provider: String, scope: CredentialScope): String? {
    accountSlotFor(provider, scope)?.let { return it.accountScope }
    return if (provider == TELEGRAM_ACCOUNT_PROVIDER && scope == CredentialScope.PERSONAL) scope.name else null
}

/**
 * Cookie rows travel as one value per slot: a line per cookie holding scheme, host, port, name and value, separated by
 * tabs. Tabs and line breaks are refused, so no field can shift into another field.
 */
private fun encodeAccountCookies(cookies: List<ScopedCookie>): String =
    cookies.joinToString(separator = "\n") { cookie ->
        require(cookie.name.isNotBlank() && cookie.origin.host.isNotBlank()) { "Cookie row is incomplete" }
        val fields = listOf(
            cookie.origin.scheme,
            cookie.origin.host,
            cookie.origin.port.toString(),
            cookie.name,
            cookie.value,
        )
        require(fields.none { field -> field.any { it == '\t' || it == '\n' || it == '\r' } }) {
            "Cookie row contains a separator"
        }
        fields.joinToString(separator = "\t")
    }

private fun decodeAccountCookies(encoded: String, account: ByteSourceAccount): List<ScopedCookie> {
    if (encoded.isEmpty()) return emptyList()
    return encoded.split('\n').map { line ->
        val fields = line.split('\t')
        require(fields.size == 5) { "Stored cookie row is malformed" }
        val port = requireNotNull(fields[2].toIntOrNull()) { "Stored cookie row is malformed" }
        ScopedCookie(account, StreamOrigin(fields[0], fields[1], port), fields[3], fields[4])
    }
}

/**
 * Builds the candidate that [VaultProviderAccountStore.commit] accepts. [credentials] are keyed by the logical credential
 * names of the identity's slot. [cookies] are the rows that the verified attempt keeps for that same account. They carry
 * no entry scope, because they are written before any entry is known; the store binds the scope when it reads them.
 */
fun providerAccountPending(
    identity: ProviderIdentity,
    generation: Long,
    credentials: Map<String, PlatformSecret>,
    cookies: List<ScopedCookie>,
): PendingProviderAccount {
    val slot = requireNotNull(accountSlotFor(identity.provider, identity.scope)) {
        "No account slot is registered for this provider scope"
    }
    require(credentials.keys.all { it in slot.credentialNames }) { "Credential is outside the account slot" }
    val account = identity.toByteSourceAccount()
    require(cookies.all { it.account == account }) { "Cookie row belongs to another account" }
    val values = LinkedHashMap<String, PlatformSecret>(credentials)
    values[slot.cookiesKey.name] = PlatformSecret(encodeAccountCookies(cookies))
    return PendingProviderAccount(identity, generation, values)
}

/** The provider string of native Telegram admission. Telegram has no credential bundle, only a verified native session. */
const val TELEGRAM_ACCOUNT_PROVIDER = "telegram"

/**
 * One addressable entry of a playback pool list, as the owner reports it. [accountScope] is opaque: admission and cookie
 * reads take it unchanged. [accountId] and [generation] stay null until the entry is committed with a verified identity,
 * so an unverified entry is listed but never admitted.
 */
data class PlaybackPoolAccountEntry(
    val provider: String,
    val feedId: Long,
    val accountScope: String,
    val accountId: String?,
    val generation: Long?,
)

/**
 * One begun attempt for one playback pool entry. It names the entry it was begun for, so the commit that completes it can
 * write only that entry, even when another entry of the same list holds the same generation number. Build the candidate
 * with [generation] and pass this value back unchanged. The provider and feed id are never supplied by the caller.
 */
class PlaybackPoolAttempt internal constructor(
    internal val provider: String,
    internal val feedId: Long,
    val generation: Long,
)

/**
 * Builds the candidate for one playback pool entry. [credentials] are keyed by the entry's credential field names, and
 * [cookies] are the rows that the verified attempt keeps for that same account. They carry no entry scope, because the
 * entry is only chosen at commit; the store binds the scope when it reads them. [generation] must be the generation of the
 * [PlaybackPoolAttempt] that the commit completes, and [VaultProviderAccountStore.commitPlaybackPoolEntry] refuses any other
 * value. [providerAccountPending] refuses the pool identity, because a pool entry has no single-account slot.
 */
fun playbackPoolPending(
    identity: ProviderIdentity,
    generation: Long,
    credentials: Map<String, PlatformSecret>,
    cookies: List<ScopedCookie>,
): PendingProviderAccount {
    require(identity.scope == CredentialScope.PLAYBACK_POOL) { "Identity is not a playback pool account" }
    val service = requirePoolService(identity.provider)
    require(credentials.keys.all { it in service.fieldKinds }) { "Credential is outside the pool entry fields" }
    val account = identity.toByteSourceAccount()
    require(cookies.all { it.account == account }) { "Cookie row belongs to another account" }
    val values = LinkedHashMap<String, PlatformSecret>(credentials)
    values[POOL_ACCOUNT_COOKIES_FIELD] = PlatformSecret(encodeAccountCookies(cookies))
    return PendingProviderAccount(identity, generation, values)
}

/** The native Telegram session state. [newestAttempt] orders native attempts; [accepted] is the verified current session. */
private class NativeTelegramState(
    val newestAttempt: Long,
    val accepted: AcceptedAccount?,
)

private fun requirePoolService(provider: String): PoolService =
    requireNotNull(poolServiceFor(provider)) { "No playback pool is registered for this provider" }

/** The parsed pool list, or null when the stored value cannot be read. Callers fail closed on null, never treat it as empty. */
private fun parsePoolListOrNull(stored: String?): PoolEntryList? =
    try {
        PoolEntryList.parse(stored)
    } catch (_: Exception) {
        null
    }

/**
 * The entry that [feedId] addresses when it is the single match and holds both an account identity and a generation.
 * An ambiguous, absent, unreadable or unverified entry yields null.
 */
private fun admittedPoolEntry(service: PoolService, feedId: Long, snapshot: PreferenceSnapshot): PoolJsonObject? {
    val list = parsePoolListOrNull(snapshot[service.listKey]) ?: return null
    val matches = list.matchingIndices(feedId)
    if (matches.size != 1) return null
    return list.objectAt(matches.single()).takeIf { it.accountId() != null && it.generation() != null }
}

/**
 * One admitted personal account, read from a single snapshot. [admitted] compares the live vault with exactly the values that
 * this read captured, so a sign-out, a replacement or a changed credential stops every lease built from it.
 */
private class PersonalLeaseRead(
    val accountScope: String,
    val accountId: String,
    val generation: Long,
    private val values: Map<String, PlatformSecret?>,
    val admitted: () -> Boolean,
) {
    /** The captured non-blank secret of [name], or null when it was absent or blank. Only this read's whitelist resolves. */
    fun secret(name: String): PlatformSecret? = values[name]?.takeIf { it.reveal().isNotBlank() }
}

/**
 * Reads the personal slot of [provider] that [accountScope] names, with its [required] and [optional] credential names, from one
 * snapshot. A snapshot is atomic because a commit writes every slot value in one edit, so a read needs no store gate and never
 * holds one across a network call. It fails closed, returning null, unless the scope names the personal slot, an identity and a
 * generation are accepted there, every required value is present, and the vault is readable. The names are the slot's own
 * credential names, so no read can reach another account, a pool entry or a Telegram session.
 */
private suspend fun personalLeaseRead(
    settings: VaultSettingsStore,
    provider: String,
    accountScope: String,
    required: List<String>,
    optional: List<String>,
): PersonalLeaseRead? {
    val snapshot = try {
        settings.snapshot()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    val slot = accountSlotForScope(provider, accountScope) ?: return null
    if (slot.scope != CredentialScope.PERSONAL) return null
    val names = required + optional
    if (names.any { it !in slot.credentialNames }) return null
    val identity = slot.acceptedIdentity(snapshot) ?: return null
    val generation = slot.storedGeneration(snapshot) ?: return null
    val values = names.associateWith { name -> snapshot[StringPreferenceKey(name)]?.let { PlatformSecret(it) } }
    if (required.any { name -> values[name]?.reveal()?.isNotBlank() != true }) return null
    // Checks the latest committed state only. A sign-out, a replacement or a changed value makes this false at once.
    val admitted: () -> Boolean = {
        settings.snapshots.value?.let { now ->
            slot.acceptedIdentity(now) == identity &&
                slot.storedGeneration(now) == generation &&
                names.all { name -> now[StringPreferenceKey(name)] == values[name]?.reveal() }
        } ?: false
    }
    return PersonalLeaseRead(accountScope, identity.accountId, generation, values, admitted)
}

/** The refusal that DeezerAccess applies, so a lease is never issued for a value its constructor would reject. */
private fun deezerArlAccepted(arl: String): Boolean = arl.isNotBlank() && arl.none { it in "\r\n;" }

/**
 * Vault-backed accepted-account owner for the account commit, admission and cookie-source ports.
 *
 * A commit writes the identity, generation, cookie rows and credentials of one slot in a single [VaultSettingsStore.edit].
 * It is therefore all-or-nothing, and every reader sees the same state. Sign-out and replacement go through this same
 * owner, so readers and cookie lookups stop serving an account at once. The store adds no database, cookie jar or
 * plaintext fallback.
 */
class VaultProviderAccountStore(
    private val settings: VaultSettingsStore,
) : ProviderAccountCommitPort, AcceptedAccountReader, ScopedCookieSource {
    private val gate = Mutex()

    /** Newest generation begun or committed per slot in this process. An older attempt can no longer commit. */
    private val newestAttempts = mutableMapOf<String, Long>()

    /**
     * The native Telegram session. It lives only in memory: persisted metadata never activates it, so a new process
     * admits nothing until native authorization is confirmed again.
     */
    private val nativeTelegram = MutableStateFlow(NativeTelegramState(newestAttempt = 0L, accepted = null))

    /**
     * Starts a login attempt for [provider] and [scope] and returns its generation. Each call returns a larger number than
     * any earlier attempt or stored account, so an attempt that finishes late is refused as stale.
     */
    suspend fun beginAttempt(provider: String, scope: CredentialScope): Long = gate.withLock {
        val slot = requireNotNull(accountSlotFor(provider, scope)) {
            "No account slot is registered for this provider scope"
        }
        val newest = maxOf(slot.storedGeneration(settings.snapshot()) ?: -1L, newestAttempts[slot.slotKey] ?: -1L)
        (newest + 1).also { newestAttempts[slot.slotKey] = it }
    }

    override suspend fun commit(pending: PendingProviderAccount): ProviderAccountCommitResult = gate.withLock {
        val identity = pending.identity
        val slot = accountSlotFor(identity.provider, identity.scope)
            ?: return@withLock ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.STORAGE_REJECTED)
        val allowedNames = slot.credentialNames.toSet() + slot.cookiesKey.name
        if (pending.values.keys.any { it !in allowedNames }) {
            return@withLock ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.STORAGE_REJECTED)
        }
        try {
            val cookieRows = pending.values[slot.cookiesKey.name]?.reveal()
            if (cookieRows != null) decodeAccountCookies(cookieRows, identity.toByteSourceAccount())
            val snapshot = settings.snapshot()
            val storedGeneration = slot.storedGeneration(snapshot)
            val accepted = slot.acceptedIdentity(snapshot)
            val newest = maxOf(storedGeneration ?: -1L, newestAttempts[slot.slotKey] ?: -1L)
            val conflictingSameGeneration =
                storedGeneration == pending.generation && accepted != null && accepted != identity
            if (pending.generation < newest || conflictingSameGeneration) {
                return@withLock ProviderAccountCommitResult.StaleGeneration
            }
            val otherScopeHoldsAccount = accountSlots().any { other ->
                other.provider == identity.provider && other.scope in slot.sameAccountRefusedBy &&
                    other.acceptedIdentity(snapshot)?.isSameProviderAccount(identity) == true
            }
            if (otherScopeHoldsAccount) return@withLock ProviderAccountCommitResult.SameAccountRejected(identity)
            settings.edit {
                slot.credentialNames.forEach { name ->
                    this[StringPreferenceKey(name)] = pending.values[name]?.reveal()
                }
                this[slot.cookiesKey] = cookieRows
                this[slot.idKey] = identity.accountId
                this[slot.generationKey] = pending.generation.toString()
            }
            newestAttempts[slot.slotKey] = maxOf(newest, pending.generation)
            ProviderAccountCommitResult.Accepted(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SettingsVaultUnavailable) {
            ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.VAULT_UNAVAILABLE)
        } catch (_: Exception) {
            ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.STORAGE_REJECTED)
        }
    }

    override suspend fun acceptedAccount(provider: String, accountScope: String): AcceptedAccount? {
        if (provider == TELEGRAM_ACCOUNT_PROVIDER) return nativeTelegramAccount(accountScope)
        val feedId = poolFeedIdOfScope(accountScope)
        if (feedId != null) {
            val service = poolServiceFor(provider) ?: return null
            val entry = admittedPoolEntry(service, feedId, settings.snapshot()) ?: return null
            val accountId = entry.accountId() ?: return null
            val generation = entry.generation() ?: return null
            return AcceptedAccount(
                identity = ProviderIdentity(provider, CredentialScope.PLAYBACK_POOL, accountId),
                generation = generation,
            )
        }
        val slot = accountSlotForScope(provider, accountScope) ?: return null
        val snapshot = settings.snapshot()
        val identity = slot.acceptedIdentity(snapshot) ?: return null
        val generation = slot.storedGeneration(snapshot) ?: return null
        return AcceptedAccount(identity = identity, generation = generation)
    }

    /**
     * The native Telegram session under the PERSONAL scope, or null while no verified session is accepted. Only an identity
     * that is exactly Telegram PERSONAL is returned, so no other provider or scope can ride on this session.
     */
    private fun nativeTelegramAccount(accountScope: String): AcceptedAccount? {
        if (accountScope != CredentialScope.PERSONAL.name) return null
        return nativeTelegram.value.accepted?.takeIf {
            it.identity.provider == TELEGRAM_ACCOUNT_PROVIDER && it.identity.scope == CredentialScope.PERSONAL
        }
    }

    /**
     * Reads the cookie rows of the one entry that [account] names by its scope. An anonymous or unscoped account reads
     * nothing. Neither does an account whose identity differs from the identity that the scope holds, so a same-user
     * entry in another scope never answers for it.
     */
    override suspend fun storedCookies(account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> {
        val accountScope = account.accountScope ?: return emptyList()
        val snapshot = settings.snapshot()
        val (bound, encoded) = scopedCookieRows(account.provider, accountScope, snapshot) ?: return emptyList()
        if (bound != account) return emptyList()
        return scopedRowsFor(encoded, bound, origin)
    }

    /**
     * Captures the accepted account of [accountScope] only when its identity is the one that the scope names. The reader's
     * default checks the provider alone, which would admit a Telegram session or a pool identity under the wrong scope.
     */
    override suspend fun admit(provider: String, accountScope: String): AccountAdmission? {
        val accepted = acceptedAccount(provider, accountScope) ?: return null
        val expected = expectedIdentityScope(provider, accountScope) ?: return null
        if (accepted.identity.provider != provider || accepted.identity.scope != expected) return null
        return AccountAdmission(accountScope = accountScope, accepted = accepted)
    }

    /**
     * The cookie rows of the exact account that [accountScope] names for [provider], filtered to [origin]. Unlike
     * [storedCookies], it is addressed by scope, so two pool entries of one real user keep their own rows and never see each
     * other's cookies. Anonymous, Telegram, unknown, unaccepted and unreadable scopes return no rows.
     */
    suspend fun storedCookiesForScope(provider: String, accountScope: String, origin: StreamOrigin): List<ScopedCookie> {
        val (account, encoded) = scopedCookieRows(provider, accountScope, settings.snapshot()) ?: return emptyList()
        return scopedRowsFor(encoded, account, origin)
    }

    /**
     * The account and encoded cookie rows that [accountScope] names, or null unless that scope names an accepted account.
     * The account carries the scope, so the rows decoded with it match only the entry that holds them.
     */
    private fun scopedCookieRows(
        provider: String,
        accountScope: String,
        snapshot: PreferenceSnapshot,
    ): Pair<ByteSourceAccount, String>? {
        val feedId = poolFeedIdOfScope(accountScope)
        if (feedId != null) {
            val service = poolServiceFor(provider) ?: return null
            val entry = admittedPoolEntry(service, feedId, snapshot) ?: return null
            val accountId = entry.accountId() ?: return null
            val identity = ProviderIdentity(provider, CredentialScope.PLAYBACK_POOL, accountId)
            return identity.toByteSourceAccount(accountScope) to entry.text(POOL_ACCOUNT_COOKIES_FIELD).orEmpty()
        }
        if (provider == TELEGRAM_ACCOUNT_PROVIDER) return null
        val slot = accountSlotForScope(provider, accountScope) ?: return null
        val identity = slot.acceptedIdentity(snapshot) ?: return null
        if (slot.storedGeneration(snapshot) == null) return null
        return identity.toByteSourceAccount(slot.accountScope) to snapshot[slot.cookiesKey].orEmpty()
    }

    /** The rows of one entry that match [origin]. A value that does not decode yields no rows, never a partial read. */
    private fun scopedRowsFor(encoded: String, account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> {
        val rows = runCatching { decodeAccountCookies(encoded, account) }.getOrNull() ?: return emptyList()
        return rows.filter { it.origin == origin }
    }

    /**
     * Clears the accepted account of [provider] and [scope]. Identity, cookie rows and credentials go in one edit, and
     * every attempt begun before this call is refused. The generation advances in the same edit, so it never moves
     * backwards and a malformed stored value is replaced rather than blocking the slot.
     */
    suspend fun signOut(provider: String, scope: CredentialScope) {
        gate.withLock {
            val slot = requireNotNull(accountSlotFor(provider, scope)) {
                "No account slot is registered for this provider scope"
            }
            val snapshot = settings.snapshot()
            val next = maxOf(slot.storedGeneration(snapshot) ?: -1L, newestAttempts[slot.slotKey] ?: -1L) + 1
            settings.edit {
                slot.credentialNames.forEach { name -> this[StringPreferenceKey(name)] = null }
                this[slot.cookiesKey] = null
                this[slot.idKey] = null
                this[slot.generationKey] = next.toString()
            }
            newestAttempts[slot.slotKey] = next
        }
    }

    /**
     * The playback pool entries of [provider] in stored order, each with its opaque account scope. Select an entry from this
     * list and pass its scope through unchanged. Manual or legacy entries without a feed id are omitted. Throws when the stored
     * list cannot be read or two entries share a feed id, so a failure is never reported as an empty pool.
     */
    suspend fun playbackPoolEntries(provider: String): List<PlaybackPoolAccountEntry> {
        val service = requirePoolService(provider)
        val entries = PoolEntryList.parse(settings.snapshot()[service.listKey]).addressableEntries()
        val feedIds = entries.mapNotNull { it.feedId() }
        require(feedIds.toSet().size == feedIds.size) { "Playback pool feed ids are ambiguous" }
        return entries.mapNotNull { entry ->
            val feedId = entry.feedId() ?: return@mapNotNull null
            PlaybackPoolAccountEntry(
                provider = provider,
                feedId = feedId,
                accountScope = poolScopeForFeedId(feedId),
                accountId = entry.accountId(),
                generation = entry.generation(),
            )
        }
    }

    /**
     * Starts an attempt for one pool entry and returns it. The generation exceeds every stored or earlier-begun generation of
     * that entry, so a late attempt cannot overwrite a newer one. The attempt names its entry, so a completion aimed at another
     * entry is refused even when the numbers match.
     */
    suspend fun beginPlaybackPoolAttempt(provider: String, feedId: Long): PlaybackPoolAttempt = gate.withLock {
        val service = requirePoolService(provider)
        require(feedId > 0L) { "Playback pool feed id must be positive" }
        val list = PoolEntryList.parse(settings.snapshot()[service.listKey])
        val matches = list.matchingIndices(feedId)
        require(matches.size <= 1) { "Playback pool feed id is ambiguous" }
        val stored = matches.singleOrNull()?.let { list.objectAt(it).generation() }
        val key = service.slotKey(feedId)
        val generation = maxOf(stored ?: -1L, newestAttempts[key] ?: -1L) + 1L
        newestAttempts[key] = generation
        PlaybackPoolAttempt(provider = service.provider, feedId = feedId, generation = generation)
    }

    /**
     * Completes [attempt] with one pool entry: its complete credential bundle, cookie rows, verified identity and generation.
     * The entry written is the one that the attempt names, never one the caller chooses. The candidate must carry the attempt's
     * provider and generation, or it is refused without writing. The whole list is read and written back in one
     * [VaultSettingsStore.edit], so sibling entries and personal slots keep their values. An unreadable list, an ambiguous
     * feed id or a stale generation is refused and never overwritten.
     */
    suspend fun commitPlaybackPoolEntry(
        attempt: PlaybackPoolAttempt,
        pending: PendingProviderAccount,
    ): ProviderAccountCommitResult = gate.withLock {
        val identity = pending.identity
        val refused = ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.STORAGE_REJECTED)
        if (identity.scope != CredentialScope.PLAYBACK_POOL || identity.provider != attempt.provider) return@withLock refused
        if (pending.generation != attempt.generation || attempt.feedId <= 0L) return@withLock refused
        val feedId = attempt.feedId
        val service = poolServiceFor(attempt.provider) ?: return@withLock refused
        val cookieRows = pending.values[POOL_ACCOUNT_COOKIES_FIELD]?.reveal()
        val credentials = pending.values
            .filterKeys { it != POOL_ACCOUNT_COOKIES_FIELD }
            .mapValues { it.value.reveal() }
        if (credentials.keys.any { it !in service.fieldKinds }) return@withLock refused
        try {
            if (cookieRows != null) decodeAccountCookies(cookieRows, identity.toByteSourceAccount())
            val list = PoolEntryList.parse(settings.snapshot()[service.listKey])
            val matches = list.matchingIndices(feedId)
            if (matches.size > 1) return@withLock refused
            val index = matches.singleOrNull()
            val current = if (index != null) list.objectAt(index) else newPoolEntry(feedId)
            val storedGeneration = current.generation()
            val storedAccountId = current.accountId()
            val key = service.slotKey(feedId)
            val newest = maxOf(storedGeneration ?: -1L, newestAttempts[key] ?: -1L)
            val conflictingSameGeneration =
                storedGeneration == pending.generation && storedAccountId != null && storedAccountId != identity.accountId
            if (pending.generation < newest || conflictingSameGeneration) {
                return@withLock ProviderAccountCommitResult.StaleGeneration
            }
            val next = service.applyCommit(current, identity.accountId, pending.generation, credentials, cookieRows)
            if (!service.usable(next)) return@withLock refused
            if (index != null) list.replaceAt(index, next) else list.append(next)
            settings.edit { this[service.listKey] = list.encode() }
            newestAttempts[key] = maxOf(newest, pending.generation)
            ProviderAccountCommitResult.Accepted(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SettingsVaultUnavailable) {
            ProviderAccountCommitResult.Failed(ProviderAccountCommitFailure.VAULT_UNAVAILABLE)
        } catch (_: Exception) {
            refused
        }
    }

    /**
     * Removes one pool entry and leaves every other entry and personal slot unchanged. Attempts for that entry that began
     * earlier are refused, and a later entry with the same feed id starts above the removed generation, so an old admission
     * can never match a new entry. Throws when the list cannot be read or the feed id is ambiguous.
     */
    suspend fun removePlaybackPoolEntry(provider: String, feedId: Long) {
        gate.withLock {
            val service = requirePoolService(provider)
            require(feedId > 0L) { "Playback pool feed id must be positive" }
            val list = PoolEntryList.parse(settings.snapshot()[service.listKey])
            val matches = list.matchingIndices(feedId)
            require(matches.size <= 1) { "Playback pool feed id is ambiguous" }
            val index = matches.singleOrNull()
            val key = service.slotKey(feedId)
            val stored = index?.let { list.objectAt(it).generation() }
            val next = maxOf(stored ?: -1L, newestAttempts[key] ?: -1L) + 1
            if (index != null) {
                list.removeAt(index)
                settings.edit { this[service.listKey] = list.encode() }
            }
            newestAttempts[key] = next
        }
    }

    /**
     * The credential fields of the pool entry that [accountScope] addresses, or null unless that entry is currently accepted.
     * Read them after admission and again before each access, so a sign-out, removal or replacement stops the read.
     */
    suspend fun acceptedPlaybackPoolCredentials(provider: String, accountScope: String): Map<String, PlatformSecret>? {
        val feedId = poolFeedIdOfScope(accountScope) ?: return null
        val service = poolServiceFor(provider) ?: return null
        return admittedPoolEntry(service, feedId, settings.snapshot())?.credentialFields()
    }

    /**
     * The personal TIDAL lease of the account that [accountScope] names, or null. Take the scope from [providerAccountScope] for
     * TIDAL and PERSONAL and pass it unchanged. The stored access token is required; the refresh token and cookie are included only
     * when they are stored. Nothing is invented, and no pool, anonymous or Telegram scope can answer. Sign-in must commit a verified
     * identity through this store before any lease can be issued.
     */
    suspend fun personalTidalLease(accountScope: String): PersonalTidalLease? {
        val read = personalLeaseRead(
            settings = settings,
            provider = "tidal",
            accountScope = accountScope,
            required = listOf("tidalAccessToken"),
            optional = listOf("tidalRefreshToken", "tidalCookie"),
        ) ?: return null
        val accessToken = read.secret("tidalAccessToken") ?: return null
        return PersonalTidalLease(
            accountScope = read.accountScope,
            accountId = read.accountId,
            generation = read.generation,
            accessToken = accessToken,
            refreshToken = read.secret("tidalRefreshToken"),
            cookie = read.secret("tidalCookie"),
            admitted = read.admitted,
        )
    }

    /**
     * The personal Deezer lease of the account that [accountScope] names, or null. The stored `deezerArl` is required and must be a
     * value that DeezerAccess accepts. Otherwise the lease is refused, instead of being handed to a constructor that would throw.
     */
    suspend fun personalDeezerLease(accountScope: String): PersonalDeezerLease? {
        val read = personalLeaseRead(
            settings = settings,
            provider = "deezer",
            accountScope = accountScope,
            required = listOf("deezerArl"),
            optional = emptyList(),
        ) ?: return null
        val arl = read.secret("deezerArl") ?: return null
        if (!deezerArlAccepted(arl.reveal())) return null
        return PersonalDeezerLease(
            accountScope = read.accountScope,
            accountId = read.accountId,
            generation = read.generation,
            arl = arl,
            admitted = read.admitted,
        )
    }

    /**
     * The personal QQ Music lease of the account that [accountScope] names, or null. The accepted account id must be the QQ musicid:
     * decimal digits, and never "0", which the Android login path refuses too. The stored ticket is required. The login type comes
     * from the stored credential row exactly as the Android session reads it, and a value outside 1..2 refuses the lease.
     */
    suspend fun personalQqMusicLease(accountScope: String): PersonalQqMusicLease? {
        val read = personalLeaseRead(
            settings = settings,
            provider = "qq",
            accountScope = accountScope,
            required = listOf("qqMusicMusickey"),
            optional = listOf("qqMusicCredential"),
        ) ?: return null
        val uin = read.accountId
        if (uin.isEmpty() || uin == "0" || uin.any { it !in '0'..'9' }) return null
        // The Android session trims the ticket on read, so the lease carries the trimmed ticket.
        val musicKey = read.secret("qqMusicMusickey")?.reveal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (musicKey.any { it in "\r\n;" }) return null
        val credential = read.secret("qqMusicCredential")?.reveal() ?: ""
        val loginType = qqMusicLoginTypeOf(credential, musicKey)
        if (loginType !in 1..2) return null
        return PersonalQqMusicLease(
            accountScope = read.accountScope,
            uin = uin,
            generation = read.generation,
            musicKey = PlatformSecret(musicKey),
            loginType = loginType,
            admitted = read.admitted,
        )
    }

    /**
     * Starts one native Telegram authorization attempt and returns its token. Call it before each GetMe, so an accept that
     * finishes after a newer attempt or a revocation is refused. Beginning an attempt does not revoke an accepted session.
     */
    fun beginNativeTelegramAttempt(): Long {
        var attempt = 0L
        nativeTelegram.update { state ->
            attempt = state.newestAttempt + 1L
            NativeTelegramState(newestAttempt = attempt, accepted = state.accepted)
        }
        return attempt
    }

    /**
     * Accepts the authorized TDLib GetMe user id for [attempt]. The identity is the Telegram account and carries no credential
     * bundle, so a chat, file or session path can never stand in for it. Returns the accepted account, or null when the attempt
     * is no longer the newest. Repeating the same account keeps its generation, so admissions taken earlier stay valid.
     */
    fun acceptNativeTelegramSession(attempt: Long, userId: Long): AcceptedAccount? {
        if (attempt <= 0L || userId <= 0L) return null
        val identity = ProviderIdentity(TELEGRAM_ACCOUNT_PROVIDER, CredentialScope.PERSONAL, userId.toString())
        var accepted: AcceptedAccount? = null
        nativeTelegram.update { state ->
            if (state.newestAttempt != attempt) {
                accepted = null
                state
            } else {
                val current = state.accepted
                val next = if (current?.identity == identity) current else AcceptedAccount(identity, attempt)
                accepted = next
                NativeTelegramState(newestAttempt = state.newestAttempt, accepted = next)
            }
        }
        return accepted
    }

    /**
     * Revokes the native Telegram session on logout, closure, session end or account switch. Every attempt begun before this
     * call is refused, and admissions taken from the revoked session stop matching at once.
     */
    fun revokeNativeTelegramSession() {
        nativeTelegram.update { state -> NativeTelegramState(newestAttempt = state.newestAttempt + 1L, accepted = null) }
    }
}
