package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class QqMusicAccess(val scope: String, val uin: String, val musicKey: String, val loginType: Int,
    val credentialScope: CredentialScope, val isActive: () -> Boolean) {
    init {
        require(scope.isNotBlank() && uin.isNotBlank() && uin.all { it.isDigit() })
        require(musicKey.isNotBlank() && musicKey.none { it in "\r\n;" })
        require(loginType in 1..2 && credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL))
    }
    internal fun gtk(): Int = musicKey.fold(5381) { h, c -> ((h shl 5) + h + c.code) and Int.MAX_VALUE }
    internal fun cookie() = "uin=$uin; qqmusic_uin=$uin; qm_keyst=$musicKey; qqmusic_key=$musicKey"
    override fun toString() = "QqMusicAccess(REDACTED)"
}

/** Actual desktop RPC and entitled plain/EVkey resource minting; no regional or entitlement bypass. */
class KtorQqMusicRepository(client: HttpClient, private val access: QqMusicAccess?,
    private val guid: () -> String, rpcUrl: String = "https://u.y.qq.com/cgi-bin/musicu.fcg") :
    PagedAudioProvider("qq:${access?.scope ?: "public"}:$rpcUrl") {
    override val provider = ContentProvider.QQ
    override val source = AudioSourceType.QQ
    private val http = ProviderHttp(client)
    private val rpcUrl = streamUrl(rpcUrl)
    private suspend fun rpc(module: String, method: String, param: JsonObject, auth: Boolean = false,
        allowRefusal: Boolean = false): JsonObject? {
        access?.let { check(it.isActive()) }
        if (auth) requireNotNull(access)
        val body = buildJsonObject {
            put("comm", buildJsonObject {
                put("ct", if (auth) 24 else 19); put("cv", if (auth) 0 else 2201)
                if (auth) {
                    put("format", "json"); put("uin", access!!.uin); put("authst", access.musicKey)
                    put("tmeLoginType", access.loginType)
                } else {
                    put("chid", "0")
                    access?.let { put("uin", it.uin); put("g_tk", it.gtk()); put("guid", guid().uppercase()) }
                }
            })
            put("req_0", buildJsonObject { put("module", module); put("method", method); put("param", param) })
        }
        val root = http.json(rpcUrl, headers = buildMap {
            put("Referer", "https://y.qq.com/"); access?.let { put("Cookie", it.cookie()) }
        }, body = body).jsonObject
        access?.let { check(it.isActive()) }
        val sub = root.obj("req_0") ?: root.obj("req") ?: throw ProviderProtocolFailure()
        val code = sub.long("code") ?: root.long("code") ?: throw ProviderProtocolFailure()
        if (code in setOf(1000L, 104400L, 104401L)) throw ProviderHttpFailure(401)
        if (code != 0L) {
            if (allowRefusal) return null
            throw ProviderProtocolFailure()
        }
        return sub.obj("data") ?: throw ProviderProtocolFailure()
    }
    data class HomeChart(val id: String, val title: String, val tracks: List<ProviderTrack>)

    /** Same Toplist GetAll/GetDetail calls as Android's QqMusicApi.home, with bounded parallel charts. */
    suspend fun home(): List<HomeChart> = coroutineScope {
        val listing = requireNotNull(rpc("music.musicToplist.Toplist", "GetAll", buildJsonObject {}))
        val charts = listing.array("group") ?: throw ProviderProtocolFailure()
        charts.flatMap { group -> (group as? JsonObject)?.array("toplist").orEmpty() }.take(6).map { entry -> async {
            val chart = entry.jsonObject
            val id = chart.long("topId") ?: throw ProviderProtocolFailure()
            val detail = rpc("music.musicToplist.Toplist", "GetDetail", buildJsonObject {
                put("topId", id); put("offset", 0); put("num", 20); put("withTags", false)
            }) ?: throw ProviderProtocolFailure()
            HomeChart(id.toString(), chart.text("title").orEmpty(), detail.array("songInfoList").orEmpty()
                .mapNotNull { try { parse(it.jsonObject) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } })
        } }.awaitAll().filter { it.tracks.isNotEmpty() }
    }

    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        if (request.surface != ContentSurface.SEARCH) throw UnsupportedContentOperation()
        require(offset % limit == 0)
        val data = requireNotNull(rpc("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", buildJsonObject {
            put("grp", 1); put("num_per_page", limit); put("page_num", offset / limit + 1)
            put("query", requireNotNull(request.text)); put("search_type", 0)
        }))
        val songs = data.obj("body")?.obj("song") ?: throw ProviderProtocolFailure()
        val rows = songs.array("list") ?: throw ProviderProtocolFailure()
        return ProviderTrackPage(rows.map { parse(it.jsonObject) },
            songs.long("totalnum") ?: data.obj("meta")?.long("sum"))
    }
    override suspend fun track(id: String): ProviderTrack {
        val data = rpc("music.pf_song_detail_svr", "get_song_detail_yqq",
            buildJsonObject { put("song_mid", pathId(id)) }) ?: throw ProviderProtocolFailure()
        return parse(data.obj("track_info") ?: throw ProviderProtocolFailure())
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val account = access ?: return null
        check(account.isActive())
        val track = match(request) ?: return null
        val raw = Json.parseToJsonElement(track.rawJson).jsonObject
        val mediaMid = raw.obj("file")?.text("media_mid") ?: raw.text("media_mid")
            ?: (track.item.ref.id + track.item.ref.id)
        val formats = when (request.qualities[source] ?: "LOSSLESS") {
            "LOSSLESS" -> listOf("F000" to ".flac", "F0M0" to ".mflac")
            "HIGH" -> listOf("M800" to ".mp3", "O8M0" to ".mgg")
            "STANDARD" -> listOf("M500" to ".mp3", "O4M0" to ".mgg")
            else -> throw IllegalArgumentException("Unknown QQ quality")
        }
        for ((prefix, extension) in formats) {
            val encryptedFormat = extension in setOf(".mflac", ".mgg")
            val modules = if (encryptedFormat) listOf("music.vkey.GetEVkey" to "CgiGetEVkey") else listOf(
                "music.vkey.GetVkey" to "UrlGetVkey", "music.vkey.GetEVkey" to "CgiGetEVkey",
                "vkey.GetVkeyServer" to "CgiGetVkey")
            for ((module, method) in modules) {
                val data = rpc(module, method, buildJsonObject {
                    put("uin", account.uin); put("guid", guid()); put("ctx", 0); put("loginflag", 1); put("platform", "20")
                    putJsonArray("songmid") { add(track.item.ref.id) }
                    putJsonArray("filename") { add("$prefix$mediaMid$extension") }
                    putJsonArray("songtype") { add(raw.long("type") ?: 0L) }
                }, auth = true, allowRefusal = true) ?: continue
                val entry = data.array("midurlinfo")?.firstOrNull() as? JsonObject ?: continue
                val path = entry.text("purl") ?: continue
                val encrypted = encryptedFormat || Regex("\\.(mflac\\w*|mgg\\w*|mmp4|mnac)$")
                    .containsMatchIn(path.substringBefore('?').lowercase())
                // The ekey is optional: a footer-less QMC1 container opens with the static cipher. An encrypted container is
                // never progressive, so it always takes the native route, with a null key when the service sent none.
                val key = if (encrypted) entry.text("ekey")?.takeIf { it.isNotBlank() } else null
                val cdn = data.array("sip")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull {
                    it.startsWith("https://") && !it.startsWith("https://ws")
                } ?: "https://isure.stream.qqmusic.qq.com/"
                val url = streamUrl(if (path.startsWith("http")) path else cdn.trimEnd('/') + "/" + path.trimStart('/'))
                // A manifest answer is a media playlist, not a progressive file: it takes the segmented native route,
                // which is what the progressive engine path plays it as on hosts that expand HLS themselves.
                if (path.substringBefore('?').lowercase().endsWith(".m3u8")) {
                    val manifestStream = direct(request, track, url, "application/vnd.apple.mpegurl", "",
                        accountScope = streamAccount(account.scope))
                    check(account.isActive())
                    return PreparedAudio.Native(manifestStream,
                        NativeAudioRecipe.SegmentedHls(playlistUrl = url, playlistText = null,
                            audioSource = AudioSourceType.QQ, accountScope = account.scope,
                            expiresAtEpochMs = manifestStream.expiresAtEpochMs))
                }
                val mime = when (extension) { ".flac", ".mflac" -> "audio/flac"; ".mp3" -> "audio/mpeg"; else -> "audio/ogg" }
                val codec = when (mime) { "audio/flac" -> "flac"; "audio/mpeg" -> "mp3"; else -> "vorbis" }
                val stream = direct(request, track, url, mime, codec, accountScope = streamAccount(account.scope))
                check(account.isActive())
                return if (encrypted) PreparedAudio.Native(stream,
                    NativeAudioRecipe.QqEncrypted(url, key?.let(::AudioSecret), account.scope)) else PreparedAudio.Progressive(stream)
            }
        }
        return null
    }
    /** Caller commits returned credential under its existing refresh mutex/epoch, never directly here. */
    suspend fun refreshCredential(material: JsonObject): JsonObject {
        val account = requireNotNull(access)
        val data = rpc("music.login.LoginServer", "Login", buildJsonObject {
            put("openid", material.text("openId").orEmpty()); put("refresh_token", material.text("refreshToken").orEmpty())
            put("musickey", account.musicKey); put("refresh_key", material.text("refreshKey").orEmpty()); put("loginMode", 2)
            if (account.loginType == 1) {
                put("str_musicid", account.uin); put("unionid", material.text("unionId").orEmpty())
            } else {
                put("access_token", material.text("accessToken").orEmpty()); put("expired_in", material.long("expiredAt") ?: 0)
                put("musicid", account.uin.toLong())
            }
        }, auth = true) ?: throw ProviderProtocolFailure()
        return data.obj("credential") ?: data
    }
    private fun parse(row: JsonObject): ProviderTrack {
        val id = row.text("mid") ?: row.text("songmid") ?: throw ProviderProtocolFailure()
        val title = row.text("title") ?: row.text("name") ?: row.text("songname") ?: throw ProviderProtocolFailure()
        val artists = row.array("singer")?.mapNotNull { (it as? JsonObject)?.text("name") }.orEmpty()
        val album = row.obj("album")
        val albumMid = album?.text("mid") ?: row.text("albummid")
        val artwork = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id), title, artists.joinToString(", "),
            artwork, row.long("interval")?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)),
            artists, album?.text("name") ?: row.text("albumname"), row.text("isrc"), row.toString())
    }
}
