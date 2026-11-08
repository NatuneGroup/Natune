package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.shared.data.lyrics.LyricsProvider
import moe.rukamori.archivetune.shared.data.provider.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.TrackMatching
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

internal fun desktopAccountLyrics(http: HttpClient, accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?, pool: DesktopPlaybackPool): List<LyricsProvider> {
    fun provider(name: String, fetch: suspend (AudioResolveRequest) -> String?) = object : LyricsProvider {
        override val name = name
        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, durationSec: Int, isrc: String?): Result<String> =
            try {
                val request = AudioResolveRequest("lyrics:$id", TrackMatching.Target(title, listOf(artist), album,
                    durationSec.takeIf { it > 0 }?.times(1000L)), isrc = isrc)
                Result.success(fetch(request)?.takeIf { it.isNotBlank() } ?: error("Account lyrics unavailable"))
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
    }
    return listOf(
        provider("Tidal") { request ->
            val token = preferences()?.get(TidalAccessTokenKey)?.takeIf(String::isNotBlank)
            val pooled = if (token == null) pool.tidalAccounts().firstOrNull() else null
            val bearer = token ?: pooled?.token ?: return@provider null
            val access = TidalAccess("lyrics:tidal", bearer, credentialScope = if (pooled == null) CredentialScope.PERSONAL else CredentialScope.PLAYBACK_POOL,
                isActive = { if (pooled == null) preferences()?.get(TidalAccessTokenKey) == bearer else pool.enabled() })
            KtorTidalRepository(http, access, countryCode = preferences()?.get(TidalCountryCodeKey, "US") ?: "US").lyrics(request)
        },
        provider("Deezer") { request ->
            val scope = providerAccountScope(ContentProvider.DEEZER.id, CredentialScope.PERSONAL)
            val lease = scope?.let { accounts.personalDeezerLease(it) }
            val pooled = if (lease == null) pool.deezerAccounts().firstOrNull() else null
            val access = if (lease != null) DeezerAccess(lease.accountScope, lease.arl.reveal(), CredentialScope.PERSONAL, lease::isStillAdmitted)
                else pooled?.let { DeezerAccess("lyrics:deezer-pool", it.arl, CredentialScope.PLAYBACK_POOL, pool::enabled) } ?: return@provider null
            KtorDeezerRepository(http, System::currentTimeMillis, access).lyrics(request)
        },
        provider("Apple Music") { request ->
            val captured = preferences() ?: return@provider null
            val personal = captured[AppleMusicMediaUserTokenKey]?.takeIf(String::isNotBlank)
            val pooled = if (personal == null) pool.appleAccounts().firstOrNull() else null
            val media = personal ?: pooled?.token ?: return@provider null
            val developer = captured[AppleMusicDevTokenKey]?.takeIf(String::isNotBlank) ?: AppleMusicProvider.ensureTokenFresh() ?: return@provider null
            KtorAppleMusicRepository(http, AppleMusicAccess("lyrics:apple", developer, media,
                credentialScope = if (pooled == null) CredentialScope.PERSONAL else CredentialScope.PLAYBACK_POOL,
                isActive = { if (pooled == null) preferences()?.get(AppleMusicMediaUserTokenKey) == media else pool.appleActive(pooled) }), nowMs = System::currentTimeMillis).lyrics(request)
        },
        provider("Musixmatch (experimental)") { request ->
            if (preferences()?.get(EnableMusixmatchExperimentalKey, false) != true) return@provider null
            moe.rukamori.archivetune.musixmatch.Musixmatch.getLyrics(request.target.title,
                request.target.artists.joinToString(", "), request.target.album, ((request.target.durationMs ?: 0) / 1000).toInt()).getOrThrow()
        },
    )
}
