package moe.rukamori.archivetune.desktop

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowser
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowserListener
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowserSink
import moe.rukamori.archivetune.shared.data.provider.KtorAmazonAuthorization
import moe.rukamori.archivetune.shared.data.provider.amazonTurnstileExpiry
import moe.rukamori.archivetune.shared.domain.settings.AmazonBypassTokenKey
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.settings.commitAmazonTurnstileJwt
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/** How one desktop Amazon authorization ended. Cancellation is the user closing the window, never an error. */
internal sealed interface AmazonAuthorizeOutcome {
    data class Authorized(val expiresAtMs: Long) : AmazonAuthorizeOutcome
    data object Cancelled : AmazonAuthorizeOutcome

    /**
     * This build carries no Turnstile sitekey or no valid origin for it, so no challenge page is loaded and no request
     * is sent.
     */
    data object NotConfigured : AmazonAuthorizeOutcome

    /** The settings page already refuses the row without an instance; this is the same refusal behind the window. */
    data object NoInstance : AmazonAuthorizeOutcome
    data object Busy : AmazonAuthorizeOutcome
    data class Failed(val reason: String) : AmazonAuthorizeOutcome
}

/** What a challenge page reported through its capture-prefix navigation. */
internal sealed interface AmazonChallengeReport {
    data class Token(val value: String) : AmazonChallengeReport
    data class Failure(val reason: String) : AmazonChallengeReport

    /** The only report that carries no page value: the window was closed or its Close button was pressed. */
    data object Cancelled : AmazonChallengeReport

    /** A prefix navigation that is not a well-formed report, so the window keeps waiting instead of failing. */
    data object Ignored : AmazonChallengeReport
}

/**
 * The Cloudflare Turnstile page the authorization window runs. Android loads the same widget into its WebView with
 * `loadDataWithBaseURL(monochrome.tf)`, because the sitekey is domain-locked to that origin and a challenge from any
 * other origin is refused by Cloudflare. The desktop window therefore loads this document *as* the sign-in origin (the
 * native `openHtml`), and the page reports back by navigating to [resultPrefix], which the native side cancels after
 * delivering the URL — the same intercept the OAuth capture prefix uses, and the token never enters a page title.
 *
 * Only the widget result crosses: a token, or a short reason. No cookie and no other page value is read.
 */
internal object AmazonTurnstileChallenge {
    /** The document the window loads: the root of the sign-in origin the build names, which the sitekey is locked to. */
    fun documentUrl(origin: String): String = "$origin/"

    /** The navigation the page uses to answer. Inside the sign-in origin, so the window may navigate to it. */
    fun resultPrefix(origin: String): String = "$origin/turnstile-result"

    /** Cloudflare renders the widget in a frame from this origin, so the frame's response must be allowed too. */
    const val CHALLENGE_FRAME_ORIGIN = "https://challenges.cloudflare.com:443"

    /** The canonical origins the native policy matches exactly: the sign-in origin with its port, then the frame. */
    fun origins(origin: String): Array<String> {
        val hostAndPort = origin.removePrefix("https://")
        val documentOrigin = if (':' in hostAndPort) "https://$hostAndPort" else "https://$hostAndPort:443"
        return arrayOf(documentOrigin, CHALLENGE_FRAME_ORIGIN)
    }

    /** A Turnstile token is a few kilobytes; anything longer is not one and is refused before it is used. */
    const val MAX_TOKEN_LENGTH = 4096
    private const val MAX_REASON_LENGTH = 160

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~-]+$")
    private val REASON_CHARS = Regex("^[A-Za-z0-9 ._:()\\[\\]-]+$")

    /**
     * The challenge page. A hidden interaction-only widget is tried first, exactly as the Android screen does, and a
     * failure escalates once to the visible widget the user can act on. [checking] is the host's status line.
     */
    fun page(sitekey: String, checking: String, resultPrefix: String): String {
        val status = jsString(checking)
        val site = jsString(sitekey)
        val prefix = jsString(resultPrefix)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
              <style>
                html,body{margin:0;height:100%;background:#101014;color:#e6e6e6;font:14px sans-serif}
                #cf{display:flex;align-items:center;justify-content:center;min-height:100%}
                #msg{position:fixed;left:0;right:0;bottom:16px;text-align:center;color:#a0a0a8}
              </style>
            </head>
            <body>
              <div id="cf"></div>
              <div id="msg"></div>
              <script>
                var prefix = $prefix;
                var attempts = 0;
                var usedVisible = false;
                var settled = false;
                document.getElementById('msg').textContent = $status;
                function report(kind, value) {
                  if (settled) { return; }
                  settled = true;
                  document.getElementById('msg').textContent = '';
                  location.assign(prefix + '?kind=' + kind + '&value=' + encodeURIComponent(String(value)));
                }
                function renderWidget(visible) {
                  var options = {
                    sitekey: $site,
                    action: 'auth',
                    theme: 'dark',
                    callback: function (token) { report('token', token); },
                    'error-callback': function () {
                      if (!usedVisible) {
                        usedVisible = true;
                        document.getElementById('cf').innerHTML = '';
                        renderWidget(true);
                      } else {
                        report('error', 'turnstile-error');
                      }
                    },
                    'expired-callback': function () { report('error', 'turnstile-expired'); }
                  };
                  if (!visible) { options.appearance = 'interaction-only'; options.execution = 'execute'; }
                  try {
                    var id = window.turnstile.render('#cf', options);
                    if (!visible) { window.turnstile.execute(id); }
                  } catch (error) {
                    report('error', 'render-exception');
                  }
                }
                function start() {
                  attempts++;
                  if (window.turnstile && window.turnstile.render) { renderWidget(false); return; }
                  if (attempts > 80) { report('error', 'turnstile-script-timeout'); return; }
                  setTimeout(start, 150);
                }
                start();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Reads a reported result URL. A token must look like one and stay inside the length bound, so a page cannot hand
     * the exchange a header or a body fragment; a reason is bounded and reduced to characters a log line can hold.
     */
    fun parse(url: String, resultPrefix: String): AmazonChallengeReport {
        if (!url.startsWith(resultPrefix + "?")) return AmazonChallengeReport.Ignored
        val query = url.substring(resultPrefix.length + 1)
        val fields = query.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else decode(part.substring(0, index)) to decode(part.substring(index + 1))
        }.toMap()
        val value = fields["value"].orEmpty()
        return when (fields["kind"]) {
            "token" -> value.takeIf { it.length in 1..MAX_TOKEN_LENGTH && TOKEN_CHARS.matches(it) }
                ?.let(AmazonChallengeReport::Token)
                ?: AmazonChallengeReport.Failure("token-invalid")
            "error" -> AmazonChallengeReport.Failure(
                value.take(MAX_REASON_LENGTH).takeIf { it.isNotBlank() && REASON_CHARS.matches(it) }
                    ?: "turnstile-failed",
            )
            else -> AmazonChallengeReport.Ignored
        }
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        ""
    }

    /*
     * The status line and the sitekey are interpolated into a script element, so a quote, a backslash, an angle bracket
     * or a line separator must not end the literal or the element.
     */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\u2028", " ")
            .replace("\u2029", " ")
        return "'$escaped'"
    }
}

/**
 * The desktop Amazon authorization: the window runs the Turnstile challenge on the sign-in origin, the token is
 * exchanged against the configured primary instance, and the returned JWT plus its expiry are committed together
 * through the one settings owner — the same keys, the same expiry margin and the same exchange request the Android
 * screen uses. Nothing is committed unless the exchange returns a token.
 */
internal class DesktopAmazonAuthorize(
    private val graph: DesktopSessionGraph,
    private val controller: SettingsController,
) {
    private val running = AtomicBoolean(false)

    suspend fun authorize(): AmazonAuthorizeOutcome {
        if (!running.compareAndSet(false, true)) return AmazonAuthorizeOutcome.Busy
        var handle = 0L
        try {
            // The sitekey is a build-time value, so a build without it has no challenge to run and loads nothing.
            val sitekey = DesktopCredentials.value(TURNSTILE_SITEKEY_NAME).trim()
            if (sitekey.isEmpty()) return AmazonAuthorizeOutcome.NotConfigured
            // The origin is paired with the sitekey; without a valid one the challenge is refused, so nothing loads.
            val origin = DesktopCredentials.amazonTurnstileOrigin ?: return AmazonAuthorizeOutcome.NotConfigured
            val snapshot = graph.settings.snapshots.value ?: return AmazonAuthorizeOutcome.Failed("settings-unavailable")
            val instance = snapshot.amazonInstanceList().firstOrNull() ?: return AmazonAuthorizeOutcome.NoInstance
            val operatorToken = snapshot[AmazonBypassTokenKey, ""].trim().ifBlank { null }
            val sink = ChallengeSink(AmazonTurnstileChallenge.resultPrefix(origin))
            handle = openChallenge(sink, sitekey, origin)
            if (handle == 0L) return AmazonAuthorizeOutcome.Failed("browser-unavailable")
            val report = withTimeoutOrNull(CHALLENGE_TIMEOUT_MS) { sink.result.await() }
                ?: return AmazonAuthorizeOutcome.Failed("challenge-timeout")
            // The window has done its part, so the exchange runs without a blank page in front of the user.
            NativeCookieBrowser.close(handle)
            handle = 0L
            val token = when (report) {
                is AmazonChallengeReport.Token -> report.value
                AmazonChallengeReport.Cancelled -> return AmazonAuthorizeOutcome.Cancelled
                is AmazonChallengeReport.Failure -> return AmazonAuthorizeOutcome.Failed(report.reason)
                AmazonChallengeReport.Ignored -> return AmazonAuthorizeOutcome.Failed("challenge-unreported")
            }
            val jwt = try {
                KtorAmazonAuthorization(graph.http, instance, origin).exchangeTurnstile(token, operatorToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("amazon token exchange failed: ${failure::class.simpleName}")
                return AmazonAuthorizeOutcome.Failed("exchange")
            }
            if (jwt.isBlank()) return AmazonAuthorizeOutcome.Failed("exchange")
            val expiresAtMs = amazonTurnstileExpiry(jwt, System.currentTimeMillis())
            try {
                controller.edit { commitAmazonTurnstileJwt(jwt, expiresAtMs) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("amazon authorization was not stored: ${failure::class.simpleName}")
                return AmazonAuthorizeOutcome.Failed("store")
            }
            return AmazonAuthorizeOutcome.Authorized(expiresAtMs)
        } finally {
            running.set(false)
            if (handle != 0L) NativeCookieBrowser.close(handle)
        }
    }

    /*
     * The window opens on the IO dispatcher because the native open waits for the browser owner, and it is not
     * cancellable so a cancelled caller still learns the handle it has to close.
     */
    private suspend fun openChallenge(sink: ChallengeSink, sitekey: String, origin: String): Long {
        val resultPrefix = AmazonTurnstileChallenge.resultPrefix(origin)
        val page = AmazonTurnstileChallenge.page(sitekey, DesktopStrings["amazon_challenge_checking"], resultPrefix)
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                NativeCookieBrowser.ensureLoaded()
                NativeCookieBrowser.openHtml(
                    documentUrl = AmazonTurnstileChallenge.documentUrl(origin),
                    html = page,
                    origins = AmazonTurnstileChallenge.origins(origin),
                    title = DesktopStrings["amazon_challenge_title"],
                    doneLabel = DesktopStrings["close"],
                    capturePrefix = resultPrefix,
                    listener = NativeCookieBrowserListener(sink),
                )
            } catch (_: LinkageError) {
                0L
            }
        }
    }

    /** The window's events. The first report wins; a later one changes nothing. */
    private class ChallengeSink(private val resultPrefix: String) : NativeCookieBrowserSink {
        val result = CompletableDeferred<AmazonChallengeReport>()

        override fun onFinish() {
            result.complete(AmazonChallengeReport.Cancelled)
        }

        override fun onCookieRows(requestId: Long, rows: Array<Array<String>>?) = Unit

        override fun onWindowClosed(failed: Boolean) {
            result.complete(AmazonChallengeReport.Cancelled)
        }

        override fun onNavigate(url: String) {
            when (val report = AmazonTurnstileChallenge.parse(url, resultPrefix)) {
                AmazonChallengeReport.Ignored -> Unit
                else -> result.complete(report)
            }
        }
    }

    private companion object {
        /** Long enough for an interactive challenge, short enough that a window nobody answers releases its caller. */
        const val CHALLENGE_TIMEOUT_MS = 5L * 60L * 1000L

        /** The build-time name of the sitekey. Only the name is in source; the value is read at run time and never logged. */
        const val TURNSTILE_SITEKEY_NAME = "ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY"
    }
}
