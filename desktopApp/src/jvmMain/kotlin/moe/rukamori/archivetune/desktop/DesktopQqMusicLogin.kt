package moe.rukamori.archivetune.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome
import moe.rukamori.archivetune.shared.ui.settings.QqLoginFailure
import moe.rukamori.archivetune.shared.ui.settings.QqLoginUiState
import org.jetbrains.skia.Image as SkiaImage

/** Where a scanned QR code has got to. The QQ numbering is the only one this source speaks. */
private enum class QqQrStatus { WAITING, SCANNED, CONFIRMED, EXPIRED, REFUSED, FAILED }

/**
 * The `ptuiCB(...)` reply, split into the fields the flow uses. The script is positional: the state code first, the
 * signed redirect URL third, the human message fifth and the nickname sixth. [uin] and [ptsigx] are read out of the
 * redirect URL rather than returned as fields.
 */
private class QqPtuiCallback(
    val code: String,
    redirectUrl: String,
    val nickname: String?,
) {
    val uin: String? = QqQrWire.queryParameter(redirectUrl, "uin")
    val ptsigx: String? = QqQrWire.queryParameter(redirectUrl, "ptsigx")
    val status: QqQrStatus get() = QqQrWire.statusOf(code)
}

/** The endpoints and the wire format of Tencent's login chain, exactly as the Android QQ sign-in speaks it. */
private object QqQrWire {
    const val QR_IMAGE_URL = "https://ssl.ptlogin2.qq.com/ptqrshow"
    const val POLL_URL = "https://ssl.ptlogin2.qq.com/ptqrlogin"
    const val CHECK_SIG_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig"
    const val AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize"
    const val MUSICU_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    /** The QQ Music web application, as the login server knows it. Public identifiers, not credentials. */
    const val APP_ID = "716027609"
    const val THIRD_PARTY_APP_ID = "100497308"

    const val REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"
    const val LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
    const val REFERER = "https://xui.ptlogin2.qq.com/"

    fun statusOf(code: String): QqQrStatus =
        when (code.trim()) {
            "0" -> QqQrStatus.CONFIRMED
            "66" -> QqQrStatus.WAITING
            "67" -> QqQrStatus.SCANNED
            "65" -> QqQrStatus.EXPIRED
            "68" -> QqQrStatus.REFUSED
            else -> QqQrStatus.FAILED
        }

    private val callbackPattern = Regex("""ptuiCB\((.*)\)""", RegexOption.DOT_MATCHES_ALL)

    /** Single-quoted script arguments, tolerating backslash escapes as the server emits them. */
    private val argumentPattern = Regex("""'((?:\\.|[^'])*)'""")

    fun parseCallback(body: String): QqPtuiCallback? {
        val inner = callbackPattern.find(body)?.groupValues?.get(1) ?: return null
        val arguments = argumentPattern.findAll(inner).map { it.groupValues[1] }.toList()
        if (arguments.size < 3) return null
        return QqPtuiCallback(
            code = arguments[0],
            redirectUrl = arguments[2],
            nickname = arguments.getOrNull(5)?.takeIf { it.isNotBlank() },
        )
    }

    /** A value out of a URL, whichever parameter order the server used. */
    fun queryParameter(url: String, name: String): String? =
        Regex("[?&]" + Regex.escape(name) + "=([^&]+)").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * Tencent's hash33 with the seeds of the web login: 0 for the `ptqrlogin` poll token, 5381 for the `g_tk` CSRF
     * parameter. Both are load-bearing; this is the same function the Android source carries.
     */
    fun hash33(value: String, initial: Int = 0): Int {
        var hash = initial
        for (character in value) {
            hash = (hash shl 5) + hash + character.code
            hash = hash and 0x7FFFFFFF
        }
        return hash
    }

    fun url(base: String, vararg parameters: Pair<String, String>): String =
        base + parameters.joinToString(separator = "&", prefix = "?") { (name, value) ->
            "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }

    fun form(vararg parameters: Pair<String, String>): String =
        parameters.joinToString(separator = "&") { (name, value) ->
            "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
}

/** One QR code the user can scan, together with the session id its scan is reported against. */
private class QqQrChallenge(val image: ByteArray, val qrsig: String)

/** A finished sign-in: the account's ticket and the refresh material the account server named, for the vault commit. */
internal class DesktopQqMusicLogin(
    val uin: String,
    val musicKey: String,
    val nickname: String?,
    val refreshCredential: String?,
)

/** A response reduced to what the login chain needs, so a consumed body can never be read twice. */
private class QqHttpResponse(val code: Int, val location: String?, val bytes: ByteArray)

/**
 * Cookies for one login attempt. A fresh jar per attempt keeps a stale `qrsig` or `p_skey` from a previous scan out
 * of the next one. The chain carries its cookies across qq.com hosts the way the Android jar does, so they are keyed
 * by name alone.
 */
private class LoginCookies {
    private val stored = LinkedHashMap<String, String>()

    @Synchronized
    fun accept(setCookieHeaders: List<String>) {
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';')
            val equals = pair.indexOf('=')
            if (equals > 0) stored[pair.substring(0, equals).trim()] = pair.substring(equals + 1).trim()
        }
    }

    @Synchronized
    fun header(): String? =
        stored.entries.joinToString(separator = "; ") { "${it.key}=${it.value}" }.ifEmpty { null }

    @Synchronized
    fun value(name: String): String? = stored[name]?.takeIf { it.isNotEmpty() }
}

/**
 * One QQ Music QR sign-in attempt over the JDK client: Tencent's own four-hop chain, as the Android source runs it.
 * The system proxy settings apply, so a desktop user behind a proxy reaches the same servers as other desktop
 * software; the Android client applies its own app proxy instead. Redirects are never followed, because each hop's
 * answer is in its own headers — cookies, and the authorisation code in a 302. Every hop fails closed: a null means
 * "ask for a new code", never a half-signed-in state.
 */
private class QqQrLoginChain(private val report: (String) -> Unit) {
    private val cookies = LoginCookies()
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.getDefault())
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Fetches a fresh QR code. The image is raw PNG bytes. */
    suspend fun requestChallenge(): QqQrChallenge? {
        val url = QqQrWire.url(
            QqQrWire.QR_IMAGE_URL,
            "appid" to QqQrWire.APP_ID,
            "e" to "2",
            "l" to "M",
            "s" to "3",
            "d" to "72",
            "v" to "4",
            "t" to Random.nextDouble().toString(),
            "daid" to "383",
            "pt_3rd_aid" to QqQrWire.THIRD_PARTY_APP_ID,
        )
        val response = execute(request(url, QqQrWire.REFERER).GET().build()) ?: return null
        val qrsig = cookies.value("qrsig")
        if (qrsig == null) {
            report("ptqrshow returned no qrsig cookie")
            return null
        }
        if (response.bytes.isEmpty()) {
            report("ptqrshow returned no image")
            return null
        }
        return QqQrChallenge(image = response.bytes, qrsig = qrsig)
    }

    /** Polls the scan state of [challenge]. A null answer is retried by the caller's next tick. */
    suspend fun poll(challenge: QqQrChallenge): QqPtuiCallback? {
        val url = QqQrWire.url(
            QqQrWire.POLL_URL,
            "u1" to QqQrWire.LOGIN_JUMP,
            "ptqrtoken" to QqQrWire.hash33(challenge.qrsig).toString(),
            "ptredirect" to "0",
            "h" to "1",
            "t" to "1",
            "g" to "1",
            "from_ui" to "1",
            "ptlang" to "2052",
            "action" to "0-0-${System.currentTimeMillis()}",
            "js_ver" to "20102616",
            "js_type" to "1",
            "pt_uistyle" to "40",
            "aid" to QqQrWire.APP_ID,
            "daid" to "383",
            "pt_3rd_aid" to QqQrWire.THIRD_PARTY_APP_ID,
            "has_onekey" to "1",
        )
        val response = execute(request(url, QqQrWire.REFERER).GET().build()) ?: return null
        val callback = QqQrWire.parseCallback(String(response.bytes, Charsets.UTF_8))
        if (callback == null) report("ptqrlogin reply was not a ptuiCB script")
        return callback
    }

    /**
     * Completes the chain for a confirmed scan: the signed `uin`/`ptsigx` pair becomes the `p_skey` cookie, that a
     * one-shot authorisation code, and the code the QQ Music ticket. A null return means the chain broke.
     */
    suspend fun complete(callback: QqPtuiCallback): DesktopQqMusicLogin? {
        val uin = callback.uin
        val ptsigx = callback.ptsigx
        if (uin.isNullOrBlank() || ptsigx.isNullOrBlank()) {
            report("a confirmed scan arrived without a signed uin/ptsigx pair")
            return null
        }
        val pSkey = exchangeForPSkey(uin, ptsigx) ?: return null
        val code = requestAuthorizeCode(pSkey) ?: return null
        return exchangeForSession(code, callback.nickname)
    }

    /** The signed pair becomes the `p_skey` cookie. */
    private suspend fun exchangeForPSkey(uin: String, ptsigx: String): String? {
        val url = QqQrWire.url(
            QqQrWire.CHECK_SIG_URL,
            "uin" to uin,
            "pttype" to "1",
            "service" to "ptqrlogin",
            "nodirect" to "0",
            "ptsigx" to ptsigx,
            "s_url" to QqQrWire.LOGIN_JUMP,
            "ptlang" to "2052",
            "ptredirect" to "100",
            "aid" to QqQrWire.APP_ID,
            "daid" to "383",
            "j_later" to "0",
            "low_login_hour" to "0",
            "regmaster" to "0",
            "pt_login_type" to "3",
            "pt_aid" to "0",
            "pt_aaid" to "16",
            "pt_light" to "0",
            "pt_3rd_aid" to QqQrWire.THIRD_PARTY_APP_ID,
        )
        execute(request(url, QqQrWire.REFERER).GET().build()) ?: return null
        val pSkey = cookies.value("p_skey")
        if (pSkey == null) report("check_sig returned no p_skey cookie")
        return pSkey
    }

    /** The `p_skey` becomes a one-shot authorisation code, carried in a redirect. */
    private suspend fun requestAuthorizeCode(pSkey: String): String? {
        val body = QqQrWire.form(
            "response_type" to "code",
            "client_id" to QqQrWire.THIRD_PARTY_APP_ID,
            "redirect_uri" to QqQrWire.REDIRECT_URI,
            "scope" to "get_user_info,get_app_friends",
            "state" to "state",
            "switch" to "",
            "from_ptlogin" to "1",
            "src" to "1",
            "update_auth" to "1",
            "openapi" to "1010_1030",
            "g_tk" to QqQrWire.hash33(pSkey, 5381).toString(),
            "auth_time" to System.currentTimeMillis().toString(),
            "ui" to UUID.randomUUID().toString(),
        )
        val response = execute(
            request(QqQrWire.AUTHORIZE_URL, GRAPH_REFERER)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        ) ?: return null
        val code = response.location?.let { QqQrWire.queryParameter(it, "code") }
        if (code == null) report("authorize answered ${response.code} without an authorisation code")
        return code
    }

    /** The authorisation code becomes the QQ Music ticket. */
    private suspend fun exchangeForSession(code: String, nickname: String?): DesktopQqMusicLogin? {
        // The web profile of the comm block, as an anonymous caller sends it, plus the QQ login type.
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "24")
                put("cv", "4747474")
                put("platform", "yqq.json")
                put("chid", "0")
                put("uin", "0")
                put("g_tk", "5381")
                put("g_tk_new_20200303", "5381")
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", "0")
                put("need_new_code", "1")
                put("tmeLoginType", "2")
            }
            putJsonObject("req_0") {
                put("module", "QQConnectLogin.LoginServer")
                put("method", "QQLogin")
                putJsonObject("param") { put("code", code) }
            }
        }
        val response = execute(
            request(QqQrWire.MUSICU_ENDPOINT, MUSIC_REFERER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
        ) ?: return null
        val parsed = runCatching { json.parseToJsonElement(String(response.bytes, Charsets.UTF_8)).jsonObject }
            .getOrElse {
                report("the login exchange body was not a JSON object")
                return null
            }
        val refusal = parsed["req_0"].asObject()?.text("code")
        if (refusal != null && refusal != "0") {
            // 20279 is the account's device limit, 20277/20278/20450 a restricted account, 104604 rate limiting.
            report("the login exchange was refused with code $refusal")
            return null
        }
        val credential = credentialOf(parsed)
        if (credential == null) {
            report("the login exchange returned no credential")
            return null
        }
        val login = loginOf(credential, nickname)
        if (login == null) report("the login exchange credential held no account id or ticket")
        return login
    }

    /** The credential object, wherever the envelope put it. */
    private fun credentialOf(response: JsonObject): JsonObject? {
        val data = response["req_0"].asObject()?.get("data").asObject() ?: response["data"].asObject()
        return data?.get("credential").asObject() ?: data
    }

    /**
     * The sign-in a login reply describes, or null when it names no account or no ticket. The refresh material is
     * kept exactly as the reply named it, in the JSON shape the Android session stores, so the vault's QQ lease reads
     * it the same way.
     */
    private fun loginOf(credential: JsonObject, nickname: String?): DesktopQqMusicLogin? {
        val uin = credential.text("musicid")?.takeIf { it != "0" } ?: credential.text("str_musicid") ?: return null
        val musicKey = credential.text("musickey") ?: return null
        // A WeChat ticket is recognisable by its W_X prefix; this chain issues QQ tickets (login type 2).
        val loginType = credential.text("loginType")?.toIntOrNull()?.takeIf { it > 0 }
            ?: if (musicKey.startsWith("W_X")) 1 else 2
        if (credential.text("refresh_key") == null) {
            report("the login reply carried no refresh_key; the ticket cannot be renewed")
        }
        val refresh = buildJsonObject {
            put("loginType", loginType)
            credential.text("refresh_key")?.let { put("refresh_key", it) }
            credential.text("refresh_token")?.let { put("refresh_token", it) }
            credential.text("access_token")?.let { put("access_token", it) }
            credential.text("openid")?.let { put("openid", it) }
            credential.text("unionid")?.let { put("unionid", it) }
            put("expired_at", credential.number("expired_at") ?: 0L)
            put("musickeyCreateTime", credential.number("musickeyCreateTime") ?: 0L)
            put("keyExpiresIn", credential.number("keyExpiresIn") ?: 0L)
        }.toString()
        return DesktopQqMusicLogin(uin, musicKey, nickname, refresh)
    }

    private fun request(url: String, referer: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
        cookies.header()?.let { builder.header("Cookie", it) }
        return builder
    }

    private suspend fun execute(request: HttpRequest): QqHttpResponse? =
        try {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
            cookies.accept(response.headers().allValues("Set-Cookie"))
            QqHttpResponse(
                code = response.statusCode(),
                location = response.headers().firstValue("Location").orElse(null),
                bytes = response.body(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("a QQ sign-in hop failed: ${failure.javaClass.simpleName}")
            null
        }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        const val GRAPH_REFERER = "https://graph.qq.com/"
        const val MUSIC_REFERER = "https://y.qq.com/"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(12)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * The desktop owner of the QQ Music QR sign-in the shared settings page drives. [start] begins one attempt (a fresh
 * chain and a fresh sign-in generation) and returns at once; [cancel] stops it when the card leaves. A finished
 * sign-in commits through the shared lifecycle, which flips the card to the signed-in account. An expired code is
 * replaced without a failure, as on Android; a code is offered for [CODE_LIFETIME_MS] and polled every
 * [POLL_INTERVAL_MS].
 */
internal class DesktopQqMusicLoginOwner(
    private val scope: CoroutineScope,
    private val accounts: ProviderAccountLifecycle,
    private val report: (String) -> Unit,
) {
    val loginState = MutableStateFlow<QqLoginUiState>(QqLoginUiState.Requesting)
    private var attempt: Job? = null

    fun start() {
        if (attempt?.isActive == true) return
        attempt = scope.launch { run() }
    }

    fun cancel() {
        attempt?.cancel()
        attempt = null
    }

    private suspend fun run() {
        loginState.value = QqLoginUiState.Requesting
        val generation = accounts.begin(ProviderAccountLifecycle.QQ)
        while (true) {
            val chain = QqQrLoginChain(report)
            val challenge = chain.requestChallenge()
            if (challenge == null) {
                loginState.value = QqLoginUiState.Failed(QqLoginFailure.UNAVAILABLE)
                return
            }
            val image = withContext(Dispatchers.Default) {
                SkiaImage.makeFromEncoded(challenge.image)?.toComposeImageBitmap()
            }
            if (image == null) {
                loginState.value = QqLoginUiState.Failed(QqLoginFailure.UNAVAILABLE)
                return
            }
            loginState.value = QqLoginUiState.Ready(image)

            var stale = false
            val deadline = System.currentTimeMillis() + CODE_LIFETIME_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                val callback = chain.poll(challenge) ?: continue
                when (callback.status) {
                    QqQrStatus.WAITING -> Unit
                    QqQrStatus.SCANNED -> loginState.value = QqLoginUiState.Scanned
                    // A stale code is not a failure: it is replaced and the user scans again.
                    QqQrStatus.EXPIRED -> stale = true
                    QqQrStatus.CONFIRMED -> {
                        val login = chain.complete(callback)
                        if (login == null) {
                            loginState.value = QqLoginUiState.Failed(QqLoginFailure.INCOMPLETE)
                            return
                        }
                        val outcome = accounts.commitQqMusic(
                            generation, login.uin, login.musicKey, login.nickname, login.refreshCredential,
                        )
                        if (outcome != ProviderSignInOutcome.Accepted) {
                            report("QQ sign-in was not saved: $outcome")
                            loginState.value = QqLoginUiState.Failed(QqLoginFailure.INCOMPLETE)
                        }
                        return
                    }
                    QqQrStatus.REFUSED -> {
                        loginState.value = QqLoginUiState.Failed(QqLoginFailure.DECLINED)
                        return
                    }
                    QqQrStatus.FAILED -> {
                        loginState.value = QqLoginUiState.Failed(QqLoginFailure.UNAVAILABLE)
                        return
                    }
                }
                if (stale) break
            }
            if (!stale) {
                loginState.value = QqLoginUiState.Failed(QqLoginFailure.TIMED_OUT)
                return
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val CODE_LIFETIME_MS = 120_000L
    }
}
