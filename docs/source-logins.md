# Source sign-in screens

The provider-specific WebView screens below cover Tidal, Qobuz, Deezer and Apple Music.
YouTube's separate embedded sign-in is described afterward. Screens should capture
credentials, verify access where supported, and show loading/recovery rather than look idle.

| Source | Credential | How it is captured | Proof before saving |
|---|---|---|---|
| Tidal | PKCE refresh + access token; web-player Bearer as fallback | redirect interception, then a `fetch`/XHR header hook | `exchangePkceCode` / `buildSessionFromBearer` |
| Qobuz | user auth token + app id + app secret | header hook, plus a bundle scrape for the secret | `QobuzAudioProvider.verifyToken` |
| Deezer | `arl` cookie | `CookieManager` (the cookie is HttpOnly) | `DeezerAudioProvider.verifyArl` |
| Apple Music | Music User Token (+ developer token) | MusicKit instance poll, localStorage fallback | none available — the token shape is the only check |

## Auth browser rendering and keyboard policy

Every interactive Android auth WebView, including Spotify's OAuth popups, is created
by `createAuthWebView`. It supplies an opaque white fallback canvas, JavaScript/DOM
storage, touch-mode focus and match-parent native dimensions. View force-dark,
algorithmic darkening and legacy WebView force-dark are explicitly disabled when
supported. Provider-authored CSS and `color-scheme` remain untouched; this does not
force a light page or inject input colors.

The Spotify input-text report is not a reproduced forced-dark diagnosis: the
previous Spotify views had no explicit transparent background or force-dark call,
and the Android XML host theme is light even when Compose is dark. The explicit
policy removes dependence on WebView defaults, while fixing the source-visible
layout hazards below. Provider/device rendering still requires real-device QA.

`AuthWebViewSheet` now serves both Spotify profiles and all routed Android web
sign-ins. Its shared KMP `AuthWebViewLayout` consumes the remaining IME inset,
reserves a weighted browser viewport and bounds the scrollable heading/status area
to one third of the available height. Long errors and recovery controls can no
longer measure the browser to zero in a short window. After a viewport shrink or
width reflow, the Android browser scrolls its DOM-focused control into view without
reading or exporting its value. The same layout backs the shared YouTube screen
and provider pages that embed a host web surface.

| Surface | Rendering/layout correction |
|---|---|
| Spotify catalog, streaming and their OAuth popups | Shared factory and keyboard-aware sheet; native popup/root dimensions remain match-parent. Profile selection, popup profile inheritance, cookie resets/capture and the unsupported-multi-profile fallback are unchanged. |
| YouTube embedded login, onboarding and embedded install recovery | Shared factory/sheet; same-WebView return, retry jar, private verification and YouTube-scoped logout are unchanged. |
| Deezer | Shared factory/sheet; existing ARL capture and verification are unchanged. |
| Tidal PKCE and web-player fallback | Shared factory/sheet; existing redirect/Bearer handling is unchanged. |
| Qobuz | Shared factory/sheet; existing token hook and verified secret search are unchanged. |
| Apple Music | Shared factory/sheet; existing MusicKit/localStorage capture is unchanged. |
| Last.fm and Libre.fm | Shared factory/sheet; existing auth redirects and session exchange are unchanged. |
| YouTube PO-token extraction Activity | Shared factory, explicit edge-to-edge IME/system-bar handling and `adjustResize`; the Activity previously had no explicit resize mode. |
| Amazon Turnstile Activity | Previously transparent HTML/native canvas now uses an opaque browser and authored `Canvas`/`CanvasText` with `color-scheme: light dark`. Status is above, not underneath, the challenge. Explicit IME handling and `adjustResize` keep interactive challenges usable. |

Discord PKCE and YouTube device-code approval open the system browser, not an
Android WebView. Telegram uses native TDLib phone/code/password controls, QQ Music
uses QR approval, and pasted provider/scrobbling credentials use native dialogs.
Those do not inherit auth-WebView color transforms. Headless cipher/BotGuard views
are not sign-in surfaces and are not changed.

On iOS, `installAuthWebView` is shared by the Deezer cookie sheet
(`IosDeezerWebSignIn` via `IosCookieOnlyBrowserAuth`), the Tidal PKCE sheet and the
generic embedded OAuth adapter. Their old full-controller frames/autoresizing
masks did not account for the navigation bar or keyboard. The browser is now
opaque and constrained to the safe area and keyboard layout guide, without
forcing UIKit/page appearance or replacing the caller's nonpersistent data store.
`ASWebAuthenticationSession` remains OS-owned and ephemeral. Other currently
unwired iOS sign-ins are not newly implemented; Qobuz remains an external web
player plus native credential entry.

Desktop Spotify catalog/streaming, YouTube, Deezer and Tidal use the existing
isolated native browser window (WebKitGTK, WebView2 or WKWebView). These have no
app-imposed darkening, transparent canvas or Android IME sheet, and already resize
their positive-height browser area with the window; their native setup is
unchanged. Qobuz, Discord, Last.fm/Libre.fm and device approval use external
browsers; Telegram/QQ Music and pasted credentials are native surfaces. The
generic Chromium OAuth adapter also retains its isolated private profile and
unmodified page rendering.

Configuration/resize-decision unit tests, short-viewport Compose layout tests and
iOS native layout/configuration tests are authored but unrun locally. They do
not establish visible provider input text, successful sign-in or software-keyboard
behavior; exact-head CI and light/dark, portrait/landscape, IME and popup device QA
remain required.

## Why credential presence is not proof

Three of the four hand out a credential that *looks* right before anyone has signed in:

- Deezer issues an `arl` to anonymous visitors.
- Tidal's web player sends a Bearer on first load, before the sign-in form is submitted.
- Qobuz's bundle contains dozens of 32-character hex strings, and the app secret is one of them.

So presence is not proof, and a screen that saves on presence produces an account that looks
connected and fails on the first play. Tidal, Qobuz and Deezer check with the provider and
only write to DataStore once that succeeds. Apple Music's token-shape check,
listed above, is not equivalent to verified account access.

## YouTube embedded login and committed sessions

The reported post-sign-in screen showed embedded `net::ERR_UNKNOWN_URL_SCHEME` for
a YouTube Music `intent://play.google.com` install target. It did not establish a
renderer crash or a successful live login.

`YouTubeLoginNavigationPolicy` recognizes narrowly validated official Music install
targets. A trusted main-frame GET may make one owned return to fixed
`https://music.youtube.com/` in the same WebView; repeated redirects stop with
Retry/Cancel. Subframes, POSTs and legacy callbacks without frame/method metadata
cannot trigger that top-level return. No external store Activity, supplied fallback
URL, copied intent headers/extras, universal rewrite or Google challenge/SSL bypass
is used. Normal DOM storage and cookies survive entry, return and retry.

`LoginScreen` owns document/view-bound extraction and sanitized main-frame/validation
errors, loading, Retry and Cancel. Subresource errors alone do not fail sign-in.
Preferred trusted origins contribute the first nonblank cookie value, preserving `=`
inside values. An explicit retry may resubmit unchanged context; duplicate callbacks
do not create parallel validation. Renderer-loss recovery exists, but is not the
observed recording's established failure.

`LoginViewModel` keeps candidate cookie, visitor and DataSyncId in memory.
`YouTubeLoginRepository` serializes validation on one private reusable core `InnerTube`,
clearing its auth in `finally`; it never stages a candidate on playback's singleton.
It verifies the selected account context through the account list, then obtains
identity from the final-context account menu. Display-name equality or a default
account is not proof of the requested context. Unsupported/ambiguous contexts fail visibly.

Only a still-owned verified attempt persists cookie/context/identity together.
Failed/cancelled attempts preserve the prior committed session. Attempt generations
and committed revisions also guard App publication, logout, account switches and delayed
visitor/identity/OAuth/repair writers. YouTube logout expires only YouTube cookie scopes,
not application-global cookies/WebStorage. Pool and Spotify accounts remain separate.

This follow-up is local uncompiled source. Synthetic regression fixtures, independent
changed-path review and exact-head hosted build/tests are pending; no live Google,
rendered WebView or device success is claimed. The separate anonymous InnerTubeX
[library pin](extraction.md#innertubex-version-and-scope) is not this login fix.

## The Qobuz app secret

This one cannot be solved by matching. The secret is a bare 32-character lowercase hex string
with no reliable marker around it, and webpack chunk hashes and asset digests are the same shape.
An earlier version took the first hex string it found, excluded the md5 of the empty string, and
reported success — which is how a saved Qobuz session could fail every stream request with
`invalid request signature`.

`QobuzBundleSecrets` therefore collects *candidates* and orders them, and the login screen tries
each against `verifyToken` until one signs a stream request. The ordering only decides who pays
the network cost first:

1. `keyed:` — found next to the app id, which the current bundle generation does. Usually one, and
   usually correct.
2. `legacy:` — an older bundle splits the secret across a `seed` and a timezone-keyed
   `info`/`extras` pair which concatenate into base64 with 44 trailing characters of filler.
   The hook reports the fragments and `decodeLegacy` reassembles them, so the arithmetic is unit
   tested rather than buried in injected JavaScript.
3. `hex:` — everything else, capped at `DEFAULT_LIMIT` so a bundle full of hashes cannot turn one
   sign-in into a hundred requests.

A rejected candidate is remembered, so a second batch of scripts never pays for it again. When the
whole pool is rejected the screen says so and offers the manual paste field, which goes through
the same verification.

## Never going quiet

Every failure path reports. That is the fix for "sometimes the WebView just doesn't work": the
credential search is asynchronous and has no natural end, so a screen that only speaks on success
is indistinguishable from a screen that is still trying. Deezer says when the cookie belongs to an
anonymous visitor, Qobuz says when no candidate signed, Apple says when its poll gave up after two
minutes. Tidal's rejected first Bearer is the one deliberate silence — it happens on every load
before sign-in, so a toast there would fire on the normal path.

## The QQ Music QR sign-in

QQ Music has no personal-developer API, so the source signs in the way the official client does:
the settings card requests a QR code, the user scans it with the QQ Music app, and the app polls the
login ticket until it is confirmed. The chain is `ptqrshow` → `ptqrlogin` → `check_sig` → the
`oauth2.0/authorize` hop → `QQConnectLogin/QQLogin`, and what comes back is the ticket/cookie set the
catalogue and the stream minter need.

Two things follow from that shape, and both are deliberate:

- **The QR expires, and the card says so.** The poll has four distinguishable states — waiting for a
  scan, scanned and waiting for confirmation, expired, refused — and each is rendered, with a refresh
  that re-requests the code. This is the same rule as the rest of this file: a screen that only
  speaks on success is indistinguishable from one that is still trying.
- **Signing out is a first-class action.** The stored ticket is not a session the app can refresh on
  its own, so the signed-in card carries the sign-out, and a failed sign-out is reported rather than
  leaving a stale card.

The account is the user's own; nothing is pooled, shared, or minted on anyone's behalf, and the
service's own quality locks are respected rather than worked around.
