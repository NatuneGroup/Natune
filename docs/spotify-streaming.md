# Spotify streaming source (experimental)

`AudioSourceType.SPOTIFY` plays audio from Spotify itself. It is ported from 4nx3b's fork
(`4nx3b/dev`, commits `3b138b559` through `4d183f803`), which in turn ported the web-player
pipeline from Stopify and the desktop pipeline from echo-spotify-extension. It is opt-in, off by
default, and sits in `AudioSourceConfig.DEFAULT_ORDER` like every other source — just above
YouTube — but counts as enabled only while the streaming switch is on and a streaming account is
signed in.

## Two accounts, never one

The catalog integration (home, library, search, playlist import, history) signs in once and keeps
its session in `SpotifySpDcKey` / `SpotifyAccessTokenKey`, with the global `Spotify.accessToken`.
Streaming uses a **second** account when the Unofficial engine is selected. The engine choice lives
in `SpotifyStreamEngineKey` (`SpotifyStreamEngine`): `UNOFFICIAL` (the original path, separate
account) or `LIBRESPOT` (a librespot-protocol audio client bound to the catalog account — the one
exception to the two-accounts rule; see the Librespot section).

| | Catalog | Streaming |
|---|---|---|
| Preferences | `SpotifySpDcKey`, `SpotifySpKeyKey`, `SpotifyAccessToken*` | `SpotifyStreamSpDcKey`, `SpotifyStreamSpKeyKey`, `SpotifyStreamAccessToken*`, `SpotifyStreamUserIdKey`, `SpotifyStreamAccountNameKey` |
| Token holder | `Spotify.accessToken` (spotifycore global) | `SpotifyStreamingAccount.accessToken()` (never touches the global) |
| Sign-in | `SpotifyLoginSheet` in the default WebView profile | `SpotifyLoginSheet(isolatedProfile = "spotify-streaming")` |
| Settings | Integration → Spotify | Integration → Spotify streaming (`settings/spotify_streaming`) |

The Unofficial STOPIFY method registers a virtual Connect device ("ArchiveTune Player"), issues play commands and
reports plays (start, 30 s, finish). None of that may land on the listener's real library, which is
why the account is separate.

Separation is enforced, not just suggested:

- `SpotifyStreamingAccount.signIn` refuses a cookie identical to the catalog cookie, and compares the
  two accounts' user ids (GraphQL `profileAttributes`, REST `/v1/me` as fallback). If the catalog is
  signed in but cannot be read, the sign-in is refused rather than assumed different.
- On every token refresh, `collidesWithCatalog` re-checks once per catalog session (using only the
  catalog's existing, still-valid token; it never mints or stores one). If the catalog has since
  been signed in with the streaming account, no streaming token is returned and the source falls
  through.
- The catalog token is read only for that comparison. Search, device registration, play commands,
  license requests and reports all use the streaming token.

### Cookie isolation in the sign-in WebView

When the installed WebView reports `WebViewFeature.MULTI_PROFILE` (via `androidx.webkit`), the streaming sign-in runs in
its own WebView profile: separate cookie jar and storage. The profile's cookies and storage are
cleared before the page loads and the cookies again after capture, and the default profile, with
every other provider's sign-in, is not touched.

Without multi-profile support the sheet falls back to the shared jar: it wipes it before loading
(the catalog sign-in does the same on every open) and wipes it again after capture, so the
streaming session is not left behind for the next sign-in. Credentials only ever live in DataStore.

## The Librespot engine (catalog account, Premium)

`LIBRESPOT` is the second engine: a librespot-protocol audio session on the **catalog**
account — deliberately the same account. Android now has a Rust/JNI audio implementation;
compilation and device playback remain unverified. This adapter does not advertise a Connect
receiver, accept remote Connect controls or use librespot's separate player/output engine.
It requires Spotify Premium, which is checked at sign-in and re-checked on token
refresh (`SpotifyPremiumPolicy`, 12 h observation cache).

Sign-in is a registered OAuth authorization-code flow with PKCE (`spotify/SpotifyLibrespotAccount.kt`):

- The client id is a build-time public value, `ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID` (`BuildConfig`;
  legacy `SPOTIFY_CLIENT_ID` resolves too, see `docs/build-credentials.md`). No client secret exists
  — PKCE proves the caller. A build without the id reports "not configured" and sends nothing.
- The authorize URL (shared-domain `source/SpotifyLibrespotOAuth.kt`) goes to the system browser;
  the redirect `natune://spotify-callback` lands in `SpotifyLibrespotCallbackActivity`, which emits
  into `SpotifyLibrespotAuthCoordinator` for the settings screen to complete.
- Acceptance is `SpotifyStreamingPolicy.decideLibrespotSignIn`: the authorized account must equal
  the catalog sign-in (a `/v1/me` comparison — the inverse of the dedicated account's refusal) and
  must read `product == "premium"`. A free plan or a different account is refused with its own
  message and nothing is stored.
- The grant persists as `spotify_librespot_*` keys (refresh token, cached access token + expiry,
  user id, display name, and the catalog sp_dc it was minted under — the refresh token alone is the
  session). That stored cookie binds the grant to the catalog session: `accessToken()` refuses
  every cached or minted token once the cookie differs, the refresh commit re-checks it inside the
  same edit, and a catalog credential replacement clears the grant atomically. Signing out of the
  catalog or of the Librespot session clears them, and turns streaming off while the stored engine
  is Librespot.
- `SpotifyLibrespotAccount.accessToken()` is the hand-off the engine consumes as
  `Credentials::with_access_token`: it returns the stored token while fresh and otherwise runs the
  refresh grant (Spotify rotates refresh tokens; a new one replaces the stored one) and re-checks
  the plan when the observation is stale. Null means no session — the caller treats it as signed out.

On desktop the same flow runs without a deeplink: `DesktopSpotifyLibrespot` opens the shared
authorize URL in the system browser and serves the registered loopback redirect
(`http://127.0.0.1:43821/spotify/callback`, bound to loopback only, one attempt at a time,
strict state match, five-minute deadline) instead of an activity. The client id comes through
`DesktopCredentials` (`ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID`); the stored grant, the catalog
fingerprint binding and every `accessToken()` guard are the ones listed above, and the
unofficial pipelines get no credentials while the effective engine is Librespot.

On iOS `IosSpotifyLibrespot` runs the same flow through the system authentication sheet
(`BrowserAuthPort` — `ASWebAuthenticationSession` with an ephemeral session per attempt): the
registered `natune://spotify-callback` scheme returns the code inside the sheet with the port's
strict redirect and state match, so no deep-link intake touches the grant. The client id comes
through Info.plist into `IosProviderCredentials.spotifyOauthClientId`; the grant storage, the
binding guards and the effective-engine gating are the same ones listed above — the iOS host
carries no unofficial streaming transport at all, so the source stays unavailable regardless.

### Android audio path

`native/librespot/` pins Rust librespot core/metadata at
`15e02e86c72d96dd39d548e3749f00abe39ffdde` (MIT); the deprecated Java 1.6.5 artifact
is not used. The [research and plan](engineering/librespot-audio-plan.md) records
current upstream evidence and the Android example (Outify uses a Rust fork/JNI,
not librespot-java). The [native recipe](../native/librespot/README.md) documents
CI packaging and the embedding patches.

- `playback/SpotifyAudioResolver` branches on the effective engine inside the
  existing source attempt. Librespot failure returns no candidate, allowing the
  configured source order to continue; it never sends catalog credentials to the
  Unofficial methods or retries that engine in the same attempt.
- `spotify/librespot/SpotifyLibrespotAudio` obtains only
  `SpotifyLibrespotAccount.accessToken()`. JNI authenticates with the OAuth bearer,
  retains AP reusable credentials in memory and uses login5 for spclient requests.
  It verifies the AP username and Premium product before resolving audio. The
  upstream OAuth StoredCredential/Keymaster profile is retained; the device's
  real `Build.VERSION.SDK_INT` supplies the Android protocol version.
- A queued Spotify id resolves directly. Otherwise a valid ISRC gets one exact
  OAuth Web API search (no text substitution for a missing ISRC match); tracks
  without a recording id use bounded title/artist/duration matching. Audio metadata
  and up to three relink alternatives come from the protocol session. No catalog
  home/library/playlist mapping is changed; Spotify-only tracks still have the
  queue-entry limitation documented in [native playback](spotify-native-playback.md).
- The existing audio quality preference selects Vorbis: LOW → 96, HIGH → 160,
  HIGHEST/AUTO → 320 or the best available lower tier. Low-data mode permits this
  lossy source at 96. A capped tier does not select a higher-only file. AAC/FLAC
  formats may appear upstream but are not claimed playable by this adapter.
- JNI obtains the AP audio key and expiring CDN URLs. An opaque
  `librespot-spotify://audio/<random-reference>` contains none of those values.
  `SpotifyLibrespotDataSource` reuses Echo's AES-128-CTR range alignment, IV and
  decrypted Vorbis header skip (0xA7), using a 32 KiB buffer; Media3 decodes the
  resulting Ogg bytes. The separate PlayPlay/Widevine pipelines are not invoked.
- Playback-cache tags include engine, account/catalog-binding fingerprint,
  requested quality, actual format and file id. Admission wraps both caches, so
  logout or a changed identity fails the next read even for cached plaintext.
  The selected tag is validated again under the read guard's captured identity;
  changing engines during lookup cannot remove that guard.
- Healthy sessions are reused. Token rotation or a disconnected session rebuilds
  on a later playback request. Logout, catalog/engine/quality/proxy changes and
  service destruction revoke descriptors and cancel in-flight Rust work. AP plan
  loss updates the bound Premium observation under a checked account binding and
  tears down the session; the existing effective-engine policy then applies.
- JNI operations share the source deadline and return fixed failure categories.
  spclient gets one attempt. HTTP 429 returns Retry-After to the existing cooldown
  policy. Client-token authentication/throttle failures stop the request, as do
  login5 protocol timeout/throttle responses; its challenge exchange stays bounded.
  Rust logging/panic text is disabled because upstream trace diagnostics include
  credentials. Android diagnostics log the failure category only.
- Queued JNI operations check the native generation before using credentials,
  so logout also revokes work that has not started. A Librespot read-admission
  failure rejects this source and re-prepares through the existing source chain
  with the playback position preserved, subject to its existing retry budget.

The native library is compiled **on CI**, for each shipped ABI, with the pinned
NDK, Rust 1.97.1, rustls/WebPKI and 16 KiB ELF alignment. APK packaging checks every
selected ABI's library; Kotlin compilation/unit tests need no native library.
There is no JVM playback dependency or bundled second decoder/output backend.
The upstream MIT notice ships in Android assets. Actual APK size, CI build time,
compilation, minified JNI linkage, seeking and playback need CI/device evidence.

Desktop and iOS remain OAuth-only: the Rust choice requires separate native
library packaging and host byte-stream adapters. Neither host gains audio from
this Android binding. The Android adapter also falls through for SOCKS or
credentialed HTTP proxies, whose settings the native AP transport cannot honor;
it never silently bypasses them.

**Current upstream restriction:** the
[November 7 warning](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/README.md)
and [issue #1649](https://github.com/librespot-org/librespot/issues/1649) report
newer Premium accounts can authenticate but cannot obtain legacy audio keys
(`audio key error 0 1`). No workaround is known. Key denial is a source failure,
not permission to feed encrypted bytes to Media3 or use PlayPlay on the catalog
account. This is an unofficial protocol client, not Spotify's official SDK.

## Resolve methods

These apply to the Unofficial engine; the Librespot engine resolves through its own session.

`SpotifyStreamResolveMethodKey` (`SpotifyStreamResolveMethod`):

- **STOPIFY** (default) — `SpotifyWebStreamer`: Dealer WebSocket → device registration →
  connect-state play command → manifest file id → storage-resolve CDN URL. The CDN file is
  Widevine-CENC fMP4 AAC 128. The key comes from Spotify's web-player license endpoint through
  `SpotifyStreamDrm` (provisioning goes to Google's server via `HttpMediaDrmCallback`). Needs
  Widevine on the device.
- **ECHO** — `spotify/echo/`: desktop device authorization from the streaming `sp_dc` →
  extended-metadata audio file list → storage-resolve → PlayPlay key. `EchoDecryptingDataSource`
  AES-CTR decrypts the CDN bytes (Ogg Vorbis 320/160/96 or FLAC). Falls back to STOPIFY when it
  cannot resolve. `SpotifyDesktopAuth` fetches the current desktop client version string from
  `loadspot.pages.dev/versions.json` once per process (validated by regex; a fixed default is used
  otherwise).

A queue item that came from a Spotify catalog import carries `MediaMetadata.spotifyTrackId`; that id
is played directly and trusted. Anything else is looked up with `searchDesktop` on the streaming
account, scored on title, artist and duration (minimum score 5).

## Wiring in MusicService

- Chain: every playback path derives its source chain from one `PlaybackSourceSnapshot`
  (`audiosource/PlaybackSourceChain.kt`) built from the stored order plus the per-source toggles.
  SPOTIFY is part of that snapshot and is enabled only when `SpotifyStreamEnabledKey` is on **and**
  a streaming account is signed in (`isSpotifyStreamingConfigured`). Its default position is just
  above YouTube; the user reorders it in "Preferred sources", and switching the toggle on or off
  never changes the order.
- Data sources: `librespot-spotify://` routes to the guarded Librespot DataSource;
  `echo-spotify://` is routed by both `SchemeRoutingDataSource` and
  `ResolvedSchemeRoutingDataSource` to `EchoDecryptingDataSource`, decrypted below the player cache,
  and bypasses the YouTube resolver. STOPIFY URLs are plain HTTPS on `*.spotifycdn.com`; the
  `mediaOkHttpClient` interceptor adds the open.spotify.com UA/Origin/Referer.
- DRM: the item's source is only known once its data source resolves, after the media source
  factory has asked for a DRM manager. So while Unofficial streaming is configured, every media source gets a
  Spotify Widevine manager (Apple items keep theirs). Clear streams never request a key. One manager
  per media source factory, because the crossfade player builds its own and a manager binds to one
  playback looper.
- Cache: all engines share the `spotify:<mediaId>` cache key but write different bytes
  (encrypted MP4 vs plain Ogg/FLAC). `SpotifyAudioCache.align` tags the cache entry
  with the method or Librespot namespace in content metadata and drops incompatible bytes.
- Reporting: `updateSpotifyPlaybackReporting` runs per media-item transition, only for items whose
  selected source is SPOTIFY and that resolved through STOPIFY.

## Known limits

- Neither Unofficial method nor the new Android Librespot path has been verified on a device in this fork. They depend on undocumented Spotify
  endpoints, hard-coded client versions and (for ECHO) PlayPlay key derivation that Spotify can
  change at any time.
- First resolution can be slow (Dealer connect + manifest wait, or the desktop authorization flow)
  and may run into the shared per-source attempt timeout; later tracks reuse the session.
- Not a download source (`DownloadSourceConfig.fromAudioSource(SPOTIFY) == null`) and not castable.
- Using this source is against Spotify's terms of use, and the streaming account can be suspended.
  The settings screen says so.
