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
name is blank, so existing local setups keep working.

## Variables

| Variable | Feature that uses it | Behavior when blank |
| --- | --- | --- |
| `ARCHIVETUNE_LASTFM_API_KEY` (legacy `LASTFM_API_KEY`) | Last.fm sign-in and the default Last.fm API key for scrobbling. Read by `:app` (`BuildConfig.LASTFM_API_KEY`) and `:lastfm` (`ProviderBuildCredentials`). | Last.fm sign-in reports not configured unless the user has entered their own key in Last.fm settings. Libre.fm and custom endpoints keep their own keys or the compatibility pair. |
| `ARCHIVETUNE_LASTFM_API_SECRET` (legacy `LASTFM_SECRET`) | Signing secret for the same Last.fm calls. Same consumers as above. | As above. A key without a secret does not count as configured. |
| `ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY` | Cloudflare Turnstile verification for Amazon Music instances (`AmazonAudioProvider`, `AmazonTurnstileActivity`). | "Authorize with Turnstile" shows "not configured" and loads nothing. Operator bypass tokens keep working. |
| `ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN` | Origin the Turnstile sitekey is locked to, sent as the `Origin` header and used as the WebView base URL. | Same as the sitekey. With the origin blank, no `Origin` header is sent. |
| `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID` | YouTube device-code sign-in (`YouTubeOAuthRepository`, `YouTubeOAuthLoginScreen`). | The sign-in shows "not configured" and sends no request. Stored sessions are not cleared. |
| `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET` | Client secret for the same flow. | As above. |
| `ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY` | WebClient PO token generation (`PoTokenWebView`) and BotGuard token minting (`BotGuardTokenGenerator`, as its `API_KEY`). The same value feeds both. | No WebClient PO token is minted; the existing client fallbacks continue. |
| `ARCHIVETUNE_POTOKEN_REQUEST_KEY` | Request key for the same PO token and BotGuard paths. The same value feeds both. | As above. |
| `ARCHIVETUNE_TIDAL_CLIENT_ID` | TIDAL OAuth client for token refresh (`TidalAccountManager`). | Refresh of sessions that use this flow is skipped: no request is sent and the stored session is kept. The PKCE sign-in and the web-player capture remain. |
| `ARCHIVETUNE_TIDAL_CLIENT_SECRET` | Client secret for the TIDAL OAuth flow. | As above. |
| `ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID` | TIDAL PKCE sign-in flow (`TidalAccountManager`, `TidalLoginScreen`). | The PKCE flow is skipped and the login screen uses the web-player capture fallback. |
| `ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET` | Client secret for the TIDAL PKCE flow. | As above. |
| `ARCHIVETUNE_TIDAL_CANVAS_EMBED_TOKEN` | TIDAL Canvas lookups (`:canvas`, `TidalCanvasProvider`, via `ProviderBuildCredentials`). | Canvas lookups return nothing and the provider reports not configured. |
| `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID` | Desktop only: the TIDAL device-flow client for "Link this device" and its token refresh (`TidalAccountAuth`). Falls back to `ARCHIVETUNE_TIDAL_CLIENT_ID`. | With neither set, "Link this device" shows unavailable and refresh is skipped; pasting a refresh token still needs a client. |
| `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET` | Client secret for the same device flow. Falls back to `ARCHIVETUNE_TIDAL_CLIENT_SECRET`. | As above. |
| `ARCHIVETUNE_JIOSAAVN_DES_KEY` | Desktop only: the JioSaavn media URL decoder key (`desktopJioSaavnSource`). The `archivetune.jiosaavn.key` system property overrides it. | The JioSaavn source is not composed on desktop. |
| `ARCHIVETUNE_LISTENER_PRESENCE_URL` | Opt-in listener presence heartbeat origin (`:app` `BuildConfig.ARCHIVETUNE_LISTENER_PRESENCE_URL`, desktop `DesktopCredentials`, iOS `Info.plist` `ArchiveTuneListenerPresenceUrl`). Must be the https:// origin of `services/listener-presence`, for example the deployed public endpoint `https://natune-listener-presence.vercel.app` (not a secret). Every client appends `/api/heartbeat` itself, so give the origin only. | No heartbeat is sent and the feature stays silent. |
| `ARCHIVETUNE_TELEGRAM_API_ID` (legacy `TELEGRAM_API_ID`) | Desktop only: the Telegram api_id of the TDLight session (`DesktopCredentials.telegramApi`). The Android app keeps its own `TELEGRAM_API_ID` build value. | The desktop Telegram session is not configured: no TDLight client starts and the sign-in page says so. |
| `ARCHIVETUNE_TELEGRAM_API_HASH` (legacy `TELEGRAM_API_HASH`) | Desktop only: the api_hash paired with the api_id above. | As above. Both values are required. |
| `ARCHIVETUNE_SOURCE_PROVIDER_URL` (legacy `SOURCE_PROVIDER_URL`) | Desktop: the ArchivePool deployment the Playback Pool reads (`DesktopPlaybackPool`). Android keeps its own `SOURCE_PROVIDER_URL` build value. | The desktop uses the same public default as Android, `https://archivepool.vercel.app`. Set `-DARCHIVETUNE_SOURCE_PROVIDER_URL=off` to turn the feed off for a launch. |
| `ARCHIVETUNE_SOURCE_PROVIDER_KEY` (legacy `SOURCE_PROVIDER_KEY`) | Desktop: the per-app read key sent as a bearer token to the pool feed, discovery and report calls; the v2 feed derives its decryption key from it. | The feed is read without a key, which works while the pool does not enforce one. Encrypted fields that need the key are dropped. |
| `ARCHIVETUNE_POOL_CLIENT_KEY` (legacy `POOL_CLIENT_KEY`) | Desktop: the legacy base64 AES-256 key for pool deployments older than the v2 feed. | Only v2 decryption is tried. |
| `ARCHIVETUNE_FEEDBACK_API_URL` (legacy `FEEDBACK_API_URL`) | Desktop: the opt-in hosted feedback service that receives a report the user reviewed and chose to send. | The feedback action stays unavailable and nothing is sent. |
| `ARCHIVETUNE_FEEDBACK_INGEST_KEY` (legacy `FEEDBACK_INGEST_KEY`) | Desktop: the write-only ingest key for the same service. It ships with the app, so the server must treat it as write-only access. | Reports are sent without the key; a service that requires it refuses them. |
| `ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID` | Desktop only: the Google OAuth client of type "Desktop app" for Google Drive app data backup (`DesktopGoogleDriveAuthorization`, loopback sign-in with PKCE). It must belong to a Google Cloud project with the Drive API enabled and the `drive.appdata` scope on its consent screen. | The Google Drive backup rows show "Google Drive is not configured" and no sign-in starts. Manual backup files keep working. |
| `ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET` | Desktop only: the client secret Google issues with the same desktop client. Google does not treat it as confidential for installed apps; it is sent when set. | Sign-in is tried without a secret; a client that requires one is reported as not configured. |
## How each module receives the values

- **`:app`** declares each variable with `buildConfigField` in `app/build.gradle.kts`, so the value is
  available as `BuildConfig.<NAME>`. `BuildConfig.LASTFM_API_KEY` and `BuildConfig.LASTFM_SECRET` keep
  their historical names.
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
  Last.fm, listener presence, the Telegram api_id/api_hash, the Playback Pool feed, the feedback service and the Google Drive desktop client). `DesktopCredentials` reads each value from an environment variable of the
  same name, then a JVM system property of the same name (`-D<NAME>=<value>`), then the build-time value, so a
  packaged desktop build can be configured at launch without rebuilding.
- The iOS host reads the Telegram pair and `ARCHIVETUNE_LISTENER_PRESENCE_URL` so far. Xcode takes them from the
  untracked `iosApp/Configuration/Credentials.xcconfig` (included by `Native.xcconfig`) or from build settings,
  and writes them into `Info.plist`. An xcconfig file treats `//` as a comment, so write the presence origin as
  `https:/$()/host` there. The other variables must be wired through the same build configuration before the
  dependent features work there.

## GitHub Actions

Map each variable to the repository secret of the same name in the `env:` block of every job step that
runs Gradle with the app or one of the modules above:

```yaml
env:
  ARCHIVETUNE_LASTFM_API_KEY: ${{ secrets.ARCHIVETUNE_LASTFM_API_KEY }}
  ARCHIVETUNE_LASTFM_API_SECRET: ${{ secrets.ARCHIVETUNE_LASTFM_API_SECRET }}
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
```

The two Telegram variables reuse the existing `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` secrets of the Android build. The repository owner creates these secrets. A secret that does not exist resolves to an empty string,
so the build still succeeds and the matching feature reports not configured. The workflow files in this
repository are not changed by this document; the workflow owner applies the mapping.

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
- Start.io app ID, Discord application ID and the Source Pool URL defaults (`app/build.gradle.kts`).
- Spotify public web and desktop client IDs (`SpotifyWebStreamer`, `SpotifyDesktopAuth`), QQ Music and
  Musixmatch app IDs.
- No TIDAL catalog token is shipped or injected. TIDAL catalog reads use the signed-in account's bearer or a
  Playback Pool account from the ArchivePool feed; without either, those lookups are skipped and the HiFi
  instance search (which needs no token) stands alone.
- Libre.fm and custom-endpoint compatibility identifiers (`lastfm/LastFM.kt`, `LastFmSettingsPolicy.kt`).
  They are used only when the user has not entered a key of their own for that service. Last.fm itself
  never uses them.
