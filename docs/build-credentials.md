# Build-time provider credentials

Provider credentials are not stored in the source tree. Each one is injected at build time from an
environment variable, an untracked `local.properties` entry or a Gradle property. A value that is not
set resolves to an empty string, and the feature that needs it reports "not configured" instead of
failing the build or crashing. No feature is removed when its value is missing.

**Injection gives no client-side confidentiality.** A value compiled into an APK, a desktop jar or an iOS
binary can be extracted from that artifact by anyone who has it. Injection only keeps values out of the
repository. A value that was ever committed stays in git history, and rotating it is an owner decision
that this mechanism does not make.

## Resolution order

For each variable, the first non-blank value wins:

1. Environment variable of the same name (GitHub Actions secrets arrive this way).
2. `local.properties` at the repository root (untracked; already in `.gitignore`).
3. Gradle property of the same name, for example `-P<NAME>=<value>` on the command line. Prefer 1 or 2:
   command-line values end up in shell history.
4. Blank (the default). The dependent feature reports "not configured".

Last.fm also reads the legacy names `LASTFM_API_KEY` and `LASTFM_SECRET` when the `ARCHIVETUNE_LASTFM_*`
name is blank, so existing local setups keep working. Lyprio reads `LYPRIO_BASE_URL` and
`LYPRIO_HMAC_KEY` the same way when the `ARCHIVETUNE_LYPRIO_*` names are blank, and the pool's
deployment override reads `ARCHIVEPOOL_BASE_URL` when `ARCHIVETUNE_ARCHIVEPOOL_BASE_URL` is blank.

## Variables

| Variable | Feature that uses it | Behavior when blank |
| --- | --- | --- |
| `ARCHIVETUNE_LASTFM_API_KEY` (legacy `LASTFM_API_KEY`) | Last.fm sign-in and the default Last.fm API key for scrobbling. Read by `:app` (`BuildConfig.LASTFM_API_KEY`) and `:lastfm` (`ProviderBuildCredentials`). | Last.fm sign-in reports not configured unless the user has entered their own key in Last.fm settings. Libre.fm and custom endpoints keep their own keys or the compatibility pair. |
| `ARCHIVETUNE_LASTFM_API_SECRET` (legacy `LASTFM_SECRET`) | Signing secret for the same Last.fm calls. Same consumers as above. | As above. A key without a secret does not count as configured. |
| `ARCHIVETUNE_LYPRIO_BASE_URL` (legacy `LYPRIO_BASE_URL`) | Built-in Lyprio lyrics server base URL (the service the Metrolist developers run; not user-configurable), for example `https://ly.meowery.eu`. Read by `:app` (`BuildConfig.LYPRIO_BASE_URL`), desktop (`DesktopCredentials.lyprioBaseUrl`) and iOS (`Info.plist` `ArchiveTuneLyprioBaseUrl`). | The Lyprio provider is absent and its settings toggle greys out as unavailable in this build. |
| `ARCHIVETUNE_LYPRIO_HMAC_KEY` (legacy `LYPRIO_HMAC_KEY`) | Base64 of the 32-byte request-signing key paired with the built-in Lyprio server. Same consumers as the base URL. It is never written to settings, so it stays out of backups and diagnostics, and settings never display it. | As above. Both values must be present for the bundled endpoint to exist. |
| `ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY` | Cloudflare Turnstile verification for Amazon Music instances (`AmazonAudioProvider`, `AmazonTurnstileActivity`; desktop `DesktopAmazonAuthorize`, which runs the challenge in its native window). | "Authorize with Turnstile" shows "not configured" and loads nothing. Operator bypass tokens keep working. |
| `ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN` | Origin the Turnstile sitekey is locked to: the `Origin` header of the instance requests and the document the challenge runs on (the Android WebView base URL, the desktop challenge window). Desktop accepts only a plain `https://host[:port]` origin. iOS does not read this value yet and keeps its built-in origin. | Same as the sitekey: the sign-in reports not configured. Android and desktop then send no `Origin` header. |
| `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID` | YouTube device-code sign-in (`YouTubeOAuthRepository`, `YouTubeOAuthLoginScreen`; desktop `DesktopYouTubeOAuth`). | The sign-in shows "not configured" and sends no request. Stored sessions are not cleared. |
| `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET` | Client secret for the same flow. | As above. |
| `ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY` | WebClient PO token generation (`PoTokenWebView`) and BotGuard token minting (`BotGuardTokenGenerator`, as its `API_KEY`). The same value feeds both. On desktop the shared BotGuard engine (`DesktopPoTokenOwner`) reads it through `DesktopCredentials`; on iOS the shared engine reads the `ArchiveTunePoTokenGoogleApiKey` Info.plist key. | No WebClient PO token is minted; the existing client fallbacks continue. On desktop and iOS the resolver sends untokened requests and settings generation reports not configured. |
| `ARCHIVETUNE_POTOKEN_REQUEST_KEY` | Request key for the same PO token and BotGuard paths. The same value feeds both. On desktop it comes through `DesktopCredentials`; on iOS through the `ArchiveTunePoTokenRequestKey` Info.plist key. | As above. |
| `ARCHIVETUNE_TIDAL_CLIENT_ID` | TIDAL OAuth client for token refresh (`TidalAccountManager`). | Refresh of sessions that use this flow is skipped: no request is sent and the stored session is kept. The PKCE sign-in and the web-player capture remain. |
| `ARCHIVETUNE_TIDAL_CLIENT_SECRET` | Client secret for the TIDAL OAuth flow. | As above. |
| `ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID` | TIDAL PKCE sign-in flow (`TidalAccountManager`, `TidalLoginScreen`). | The PKCE flow is skipped and the login screen uses the web-player capture fallback. |
| `ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET` | Client secret for the TIDAL PKCE flow. | As above. |
| `ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID` (legacy `SPOTIFY_CLIENT_ID`) | Librespot streaming sign-in (`SpotifyLibrespotAccount` on Android, `DesktopSpotifyLibrespot` on desktop through `DesktopCredentials`, `IosSpotifyLibrespot` on iOS through the `ArchiveTuneSpotifyOauthClientId` Info.plist key): the registered public OAuth client id for the authorization-code PKCE flow. No client secret is injected — PKCE proves the caller. | The Librespot sign-in reports not configured and sends no request. A stored session keeps refreshing only while the same client id is configured. |
| `ARCHIVETUNE_TIDAL_CANVAS_EMBED_TOKEN` | TIDAL Canvas lookups (`:canvas`, `TidalCanvasProvider`, via `ProviderBuildCredentials`). | Canvas lookups return nothing and the provider reports not configured. |
| `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID` | Desktop only: the TIDAL device-flow client for "Link this device" and its token refresh (`TidalAccountAuth`). Falls back to `ARCHIVETUNE_TIDAL_CLIENT_ID`. | With neither set, "Link this device" shows unavailable and refresh is skipped; pasting a refresh token still needs a client. |
| `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET` | Client secret for the same device flow. Falls back to `ARCHIVETUNE_TIDAL_CLIENT_SECRET`. | As above. |
| `ARCHIVETUNE_JIOSAAVN_DES_KEY` | Desktop only: the JioSaavn media URL decoder key (`desktopJioSaavnSource`). The `archivetune.jiosaavn.key` system property overrides it. | The JioSaavn source is not composed on desktop. |
| `ARCHIVETUNE_LISTENER_PRESENCE_URL` | Opt-in listener presence heartbeat origin (`:app` `BuildConfig.ARCHIVETUNE_LISTENER_PRESENCE_URL`, desktop `DesktopCredentials`, iOS `Info.plist` `ArchiveTuneListenerPresenceUrl`). Must be the https:// origin of `services/listener-presence`, for example the deployed public endpoint `https://natune-listener-presence.vercel.app` (not a secret). Every client appends `/api/heartbeat` itself, so give the origin only. | No heartbeat is sent and the feature stays silent. |
| `NATUNE_NEWS_URL` | The in-app News feed base (`<base>/index.json` and `<base>/content/<id>`), not a secret. Unset means the default `https://natune.pages.dev/api/news`. Read by `:app` (`BuildConfig.NATUNE_NEWS_URL`, from `local.properties` then the environment), desktop (`DesktopBuildIdentity.NATUNE_NEWS_URL`, same precedence) and iOS (`Info.plist` `NatuneNewsUrl`, defaulted in `Native.xcconfig` and overridable in `Credentials.xcconfig`). | An explicitly blank value disables the feed: no request is sent and the News entry is hidden. |
| `ARCHIVETUNE_TELEGRAM_API_ID` (legacy `TELEGRAM_API_ID`) | Desktop only: the Telegram api_id of the TDLight session (`DesktopCredentials.telegramApi`). The Android app keeps its own `TELEGRAM_API_ID` build value. | The desktop Telegram session is not configured: no TDLight client starts and the sign-in page says so. |
| `ARCHIVETUNE_TELEGRAM_API_HASH` (legacy `TELEGRAM_API_HASH`) | Desktop only: the api_hash paired with the api_id above. | As above. Both values are required. |
| `ARCHIVETUNE_ARCHIVEPOOL_BASE_URL` (legacy `ARCHIVEPOOL_BASE_URL`) | The dedicated ArchivePool deployment for the Playback Pool and Pool instance discovery; when set and https it wins over `SOURCE_PROVIDER_URL` as the primary on every host — Android `BuildConfig.ARCHIVEPOOL_BASE_URL`, desktop `DesktopCredentials.poolBaseUrl`, iOS `Info.plist` `ArchiveTuneArchivePoolBaseUrl`. | The `SOURCE_PROVIDER_URL` value is used, then the public `https://archivepool.vercel.app` fallback (which every host still retries after the primary on a host failure). |
| `ARCHIVETUNE_SOURCE_PROVIDER_URL` (legacy `SOURCE_PROVIDER_URL`) | Desktop: the ArchivePool deployment the Playback Pool reads (`DesktopPlaybackPool`). Android keeps its own `SOURCE_PROVIDER_URL` build value. | The desktop uses the same public default as Android, `https://archivepool.vercel.app`. Set `-DARCHIVETUNE_SOURCE_PROVIDER_URL=off` to turn the feed off for a launch. |
| `ARCHIVETUNE_SOURCE_PROVIDER_KEY` (legacy `SOURCE_PROVIDER_KEY`) | Desktop: the per-app read key sent as a bearer token to the pool feed, discovery and report calls; the v2 feed derives its decryption key from it. | The feed is read without a key, which works while the pool does not enforce one. Encrypted fields that need the key are dropped. |
| `ARCHIVETUNE_POOL_CLIENT_KEY` (legacy `POOL_CLIENT_KEY`) | Desktop: the legacy base64 AES-256 key for pool deployments older than the v2 feed. | Only v2 decryption is tried. |
| `ARCHIVETUNE_FEEDBACK_API_URL` (legacy `FEEDBACK_API_URL`) | Desktop: the opt-in hosted feedback service that receives a report the user reviewed and chose to send. | The feedback action stays unavailable and nothing is sent. |
| `ARCHIVETUNE_FEEDBACK_INGEST_KEY` (legacy `FEEDBACK_INGEST_KEY`) | Desktop: the write-only ingest key for the same service. It ships with the app, so the server must treat it as write-only access. | Reports are sent without the key; a service that requires it refuses them. |
| `ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID` | Desktop only: the Google OAuth client of type "Desktop app" for Google Drive app data backup (`DesktopGoogleDriveAuthorization`, loopback sign-in with PKCE). It must belong to a Google Cloud project with the Drive API enabled and the `drive.appdata` scope on its consent screen. | The Google Drive backup rows show "Google Drive is not configured" and no sign-in starts. Manual backup files keep working. |
| `ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET` | Desktop only: the client secret Google issues with the same desktop client. Google does not treat it as confidential for installed apps; it is sent when set. | Sign-in is tried without a secret; a client that requires one is reported as not configured. |
| `ARCHIVETUNE_GOOGLE_DRIVE_CLIENT_ID` | iOS only: the Google OAuth client of type "iOS" for Google Drive app data backup (`IosGoogleDriveAuthorization`, `ASWebAuthenticationSession` sign-in with PKCE, no client secret). Create it in a Google Cloud project that has the Drive API enabled and the `drive.appdata` scope on its consent screen, with the app's bundle id (`moe.rukamori.archivetune`) as its iOS bundle ID. Written into `Info.plist` `ArchiveTuneGoogleDriveClientId`. | The Google Drive backup section reports "Google Drive is not configured for this app installation." and no sign-in starts. Manual backup files keep working. |
| `ARCHIVETUNE_GOOGLE_DRIVE_URL_SCHEME` | iOS only: an explicit URL scheme for the consent redirect of the same sign-in, written into `Info.plist` `ArchiveTuneGoogleDriveUrlScheme`. It is only an override — `CFBundleURLTypes` always registers the bundle id (a scheme Google accepts for iOS clients) so the entry can never be empty, and the auth sheet delivers custom-scheme callbacks without registration regardless. | The sign-in uses the reversed client id (`com.googleusercontent.apps.<id>`) derived from the client id. |
| `ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID` | Desktop only: the retained Echo/PlayPlay device-pair flow, using the separate Spotify streaming account. This is a protocol client identifier, not a subscriber credential or the catalog token. | Echo reports client configuration unavailable and the normal source chain continues. |
| `ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID` | Optional client-token fallback for the same desktop streaming flow. Never used to mint or change a catalog session. | Only the supplied desktop client is tried. |
| `ARCHIVETUNE_SPOTIFY_AUDIO_IV` | Desktop only: the retained Echo audio protocol's 16-byte AES-CTR initialization vector, supplied as 32 hexadecimal characters. No protocol bytes are newly embedded by the desktop adapter. | Echo reports client configuration unavailable; neither ciphertext nor a guessed progressive URL reaches the engine. |

Desktop streaming still requires its existing opt-in switch and a separately verified streaming account.
Selecting the web-player method is preserved, not silently changed to Echo: that method needs a licensed
content-decryption module and is unavailable on this desktop engine. The PlayPlay protocol implementation is
compiled verbatim from the five retained, platform-independent files in `app/.../spotify/echo`; no Android
auth, WebView or Media3 classes are included.

## How each module receives the values

- **`:app`** declares each variable with `buildConfigField` in `app/build.gradle.kts`, so the value is
  available as `BuildConfig.<NAME>`. `BuildConfig.LASTFM_API_KEY` and `BuildConfig.LASTFM_SECRET` keep
  their historical names, and `BuildConfig.LYPRIO_BASE_URL` / `BuildConfig.LYPRIO_HMAC_KEY` carry the
  built-in Lyprio pair.
- **`:lastfm`** and **`:canvas`** are plain JVM modules without `BuildConfig`. Each has a
  `generateProviderCredentials` task that writes `moe.rukamori.archivetune.<module>.ProviderBuildCredentials`
  into `build/generated/providerCredentials`, and that source directory is compiled with the module.
  The generated file is never committed, and its task is excluded from the build cache because it embeds
  the values.
- The resolver lives in `buildSrc/src/main/kotlin/ProviderCredentials.kt`. Gradle task inputs carry only
  SHA-256 fingerprints of the values, so a changed value regenerates the source without the value
  entering Gradle's task history.
- **`:desktopApp`** has its own `generateProviderCredentials` task that writes
  `moe.rukamori.archivetune.desktop.DesktopBuildCredentials` (TIDAL OAuth clients, the JioSaavn key,
  Last.fm, listener presence, the Telegram api_id/api_hash, the Playback Pool feed, the feedback service,
  the Google Drive desktop client, Spotify Echo protocol configuration, the YouTube device-code
  client pair, the Amazon Turnstile sitekey and origin and the built-in Lyprio server pair).
  `DesktopCredentials` reads each value from an environment variable of the
  same name, then a JVM system property of the same name (`-D<NAME>=<value>`), then the build-time value, so a
  packaged desktop build can be configured at launch without rebuilding.
- **iOS** maps the Telegram pair, the TIDAL client pairs, `ARCHIVETUNE_LISTENER_PRESENCE_URL`, the Last.fm
  pair, the BotGuard PO token pair (`ARCHIVETUNE_POTOKEN_REQUEST_KEY`,
  `ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY`), the TIDAL canvas embed token, the Lyprio pair and the Google
  iOS Drive client pair (`ARCHIVETUNE_GOOGLE_DRIVE_CLIENT_ID`,
  `ARCHIVETUNE_GOOGLE_DRIVE_URL_SCHEME`) into `Info.plist`. Xcode takes them from the untracked
  `iosApp/Configuration/Credentials.xcconfig`
  (included last by `Native.xcconfig`) or from build settings. The release workflow's iOS job writes that file from
  the same secrets before it builds (step `Write iOS provider credentials`). An xcconfig file treats `//` as a
  comment, so a value such as the presence origin is written with `/$()/` in place of `//`. The Turnstile sitekey
  and origin are written to the file too, but no iOS code reads them yet.

## GitHub Actions

Map each variable to the repository secret of the same name in the `env:` block of every job step that
runs Gradle with the app or one of the modules above:

```yaml
env:
  ARCHIVETUNE_LASTFM_API_KEY: ${{ secrets.ARCHIVETUNE_LASTFM_API_KEY }}
  ARCHIVETUNE_LASTFM_API_SECRET: ${{ secrets.ARCHIVETUNE_LASTFM_API_SECRET }}
  ARCHIVETUNE_LYPRIO_BASE_URL: ${{ vars.LYPRIO_BASE_URL }}
  ARCHIVETUNE_LYPRIO_HMAC_KEY: ${{ secrets.LYPRIO_HMAC_KEY }}
  ARCHIVETUNE_ARCHIVEPOOL_BASE_URL: ${{ vars.ARCHIVEPOOL_BASE_URL }}
  ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY: ${{ secrets.ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY }}
  ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN: ${{ secrets.ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN }}
  ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID: ${{ secrets.ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID }}
  ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET: ${{ secrets.ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET }}
  ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY: ${{ secrets.ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY }}
  ARCHIVETUNE_POTOKEN_REQUEST_KEY: ${{ secrets.ARCHIVETUNE_POTOKEN_REQUEST_KEY }}
  ARCHIVETUNE_TIDAL_CLIENT_ID: ${{ secrets.ARCHIVETUNE_TIDAL_CLIENT_ID }}
  ARCHIVETUNE_TIDAL_CLIENT_SECRET: ${{ secrets.ARCHIVETUNE_TIDAL_CLIENT_SECRET }}
  ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID: ${{ secrets.ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID }}
  ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET: ${{ secrets.ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET }}
  ARCHIVETUNE_TIDAL_CANVAS_EMBED_TOKEN: ${{ secrets.ARCHIVETUNE_TIDAL_CANVAS_EMBED_TOKEN }}
  ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID: ${{ secrets.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID }}
  ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET: ${{ secrets.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET }}
  ARCHIVETUNE_JIOSAAVN_DES_KEY: ${{ secrets.ARCHIVETUNE_JIOSAAVN_DES_KEY }}
  ARCHIVETUNE_LISTENER_PRESENCE_URL: ${{ secrets.ARCHIVETUNE_LISTENER_PRESENCE_URL }}
  ARCHIVETUNE_TELEGRAM_API_ID: ${{ secrets.TELEGRAM_API_ID }}
  ARCHIVETUNE_TELEGRAM_API_HASH: ${{ secrets.TELEGRAM_API_HASH }}
  ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID: ${{ secrets.ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID }}
  ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET: ${{ secrets.ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET }}
  ARCHIVETUNE_GOOGLE_DRIVE_CLIENT_ID: ${{ secrets.ARCHIVETUNE_GOOGLE_DRIVE_CLIENT_ID }}
  ARCHIVETUNE_GOOGLE_DRIVE_URL_SCHEME: ${{ secrets.ARCHIVETUNE_GOOGLE_DRIVE_URL_SCHEME }}
  ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID: ${{ secrets.ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID }}
  ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID: ${{ secrets.ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID }}
  ARCHIVETUNE_SPOTIFY_AUDIO_IV: ${{ secrets.ARCHIVETUNE_SPOTIFY_AUDIO_IV }}
```

The two Telegram variables reuse the existing `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` secrets of the Android build.
The repository owner creates the secrets. A secret that does not exist resolves to an empty string, so the build still
succeeds and the matching feature reports not configured. `.github/workflows/_release-artifacts.yml` passes these values
to its Android, desktop and iOS jobs. The pull-request and test workflows read the Last.fm pair under the canonical
names and fall back to the legacy `LASTFM_*` secrets. The Lyprio pair comes from the `LYPRIO_BASE_URL` Actions
variable and the `LYPRIO_HMAC_KEY` secret in every job that receives the Last.fm secrets, and the pool
deployment override comes from the `ARCHIVEPOOL_BASE_URL` Actions variable in the same jobs; fork pull requests get
none of these, so the bundled endpoint and the override simply stay unconfigured there.

## Local setup

Add the values you want to `local.properties` at the repository root. The file is untracked:

```properties
ARCHIVETUNE_TIDAL_CLIENT_ID=<your value>
ARCHIVETUNE_TIDAL_CLIENT_SECRET=<your value>
```

Or copy `.env.example` to `.env`, fill in only what you need, and export it before running Gradle:

```sh
set -a; . ./.env; set +a
```

Leave a variable blank to keep its feature in the "not configured" state. A debug build works without any
of them.

## Shared values

- `ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY` and `ARCHIVETUNE_POTOKEN_REQUEST_KEY` are read by both the PO
  token path and the BotGuard path. Setting one of them configures both.
- The sitekey and origin for Amazon Turnstile are a pair. The origin is what the sitekey is locked to,
  so set both or neither.

## Values that stay in source

These are public identifiers rather than provider secrets, so this mechanism does not move them. They
remain literals in source:

- Telegram: the public Telegram Desktop api_id and api_hash used when no value is injected
  (`app/build.gradle.kts`).
- Start.io app ID and the Source Pool URL defaults (`app/build.gradle.kts`).
- Discord application ID: Android reads `DISCORD_APPLICATION_ID` from `local.properties` or the environment, else the
  public default (`app/build.gradle.kts`). Desktop keeps a literal of the same value in `desktopApp/build.gradle.kts`
  (`desktopDiscordApplicationId`, which names the macOS `discord-<id>` URL scheme) and in `DesktopDiscordPresence`.
  Desktop does not read the environment for it, so a local override of the Android value must be mirrored there.
- Spotify public web and desktop client IDs (`SpotifyWebStreamer`, `SpotifyDesktopAuth`), QQ Music and
  Musixmatch app IDs.
- No TIDAL catalog token is shipped or injected. TIDAL catalog reads use the signed-in account's bearer or a
  Playback Pool account from the ArchivePool feed; without either, those lookups are skipped and the HiFi
  instance search (which needs no token) stands alone.
- Libre.fm and custom-endpoint compatibility identifiers (`lastfm/LastFM.kt`, `LastFmSettingsPolicy.kt`).
  They are used only when the user has not entered a key of their own for that service. Last.fm itself
  never uses them.
