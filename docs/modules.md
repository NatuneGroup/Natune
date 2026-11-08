# Source modules and Natune extensions

Natune ports the Android source-module host from ArchiveTune `upstream/dev`
`8f25a9e21` (ArchiveTune 15.1.0). No downloaded module is installed or enabled on
a fresh installation. The built-in Spotify extensions are listed separately and
remain off until an account/source is enabled or the user switches an extension
on. Open Settings → Modules, import a file/URL, or add a suggested catalog,
review the installation, configure credentials and explicitly enable Modules.
Each installed module has its own enable switch and order.

The audio catalogs remain portable to upstream ArchiveTune. A separate Natune-only
[extensions catalog](https://raw.githubusercontent.com/NatuneGroup/Natune/feat/r3-spotify-ext/addons/modules/natune-extensions.json)
contains the optional LRCLIB lyrics example. All three catalogs are available as
explicit suggestions in Modules settings.

The portable audio catalogs are:

- [Natune catalog](https://raw.githubusercontent.com/NatuneGroup/Natune/feat/r3-modules/addons/modules/registry.json)
- [ArchiveTune catalog](https://raw.githubusercontent.com/rukamori/ArchiveTuneModules/main/registry.json)

The Natune catalog and in-app suggestion currently target `feat/r3-modules`.
After merging, update the suggestions in `ModulesSettings.kt`, `BRANCH` and
`NATUNE_BRANCH` in `addons/modules/scripts/build_catalog.py`, and these links to
the shipping branch, then regenerate both catalogs. Do not delete the feature
branches before publishing replacement download links.

## Architecture and compatibility

`:modules` is an Android library: bounded package/catalog readers, QuickJS
runtime, HTTP transport, encrypted runtime storage, extension compatibility and
signed sessions, plus `module-host.js` and `module-extension.js`. It matches the
other Android libraries: compileSdk 37, minSdk 24, Java toolchain 21. The Android
app provides Hilt repositories, Keystore-backed registry/configuration storage,
track matching and diagnostics, Media3 playback/download storage, settings UI
and the `archivetune://module-session` authorization callback.

The wire contracts remain `format: "archivetune.module"`, `formatVersion: 1`,
`apiVersion: 1`, `entry: "provider.js"`, script SHA-256 and optional packaged PNG.
Manifest, settings, registry/category catalogs, typed quality options, native
CommonJS exports and the extension host APIs retain the upstream parsing and
limits. The installer does not extract paths to disk. `8SM1.` is intentionally
unsupported. Upstream text modules and readable `.arch` packages remain accepted.
The nine portable audio packages use only upstream JS API v1, including `fetch`,
typed settings, base64 and inline DASH. No Natune runtime feature is required by
the portable audio catalog.
Packages can consequently be installed on either host; live service/account
availability is separate from package compatibility and is not verified here.

`minAppVersion` describes the ArchiveTune release contract, not Natune's release
name or monotonically increasing Android versionCode. Natune passes the explicit
`ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION = "15.1.0"` into package checks and
configuration validation. This is the upstream release whose host was ported;
requirements through 15.1.0 pass, future requirements fail, unknown runtime
features still fail. Do not use a high sentinel or disable the check. Advance the
mapping only after porting the corresponding host changes. Raw legacy extension
manifests retain upstream's separate minimum-version behavior.

Android playback still enters through `MusicService.resolveMultiSourceDataSpec`.
`MODULES` participates in the existing source order, per-song pins, enabled-source
snapshot and rejection policy. Its default position is before Spotify and
YouTube; Spotify remains directly before YouTube. Existing stored orders insert
missing sources before the terminal YouTube entry. Modules are available only
when the registry and an installed audio/playback module are enabled. Module quality remains
independent of YouTube/low-data quality, as upstream specifies; existing sources
retain their low-data rules. Local files, podcasts, music videos and Telegram
keep their existing paths.

`ModuleMediaSourceFactory` prepares source-order selection before constructing
Media3's source, so inline DASH/HLS can use the upstream module storage. Standard
sources retain their DRM factory and constant-bitrate seeking. Modules preserve
song/queue identity and store audio under source/revision-specific cache keys.
`ModuleSelectingDownloader` uses the download order and persisted bindings for
resumption/offline playback, with upstream segment/cache/header handling.
`ModuleMediaStorage` adapts to Natune's existing `FormatEntity`; it writes declared
metadata without inventing decoded audio evidence or adding a database migration.

`ResolveAudioStreamUseCase` continues to own the existing terminal YouTube
extractor tiers; module failure does not alter their order. The shared domain
reserves the same `MODULES` name, defaults it off and does not enable an Android
runtime on desktop/iOS. `ModuleAudioProviderRepository` is an Android adapter for
progressive consumers of `OrderedAudioResolver`; segmented modules use the
Android Media3 owner. The shared resolver itself stays platform-independent.
Desktop and iOS receive no QuickJS/Android module dependency. Their source settings
report the runtime unavailable. Module portable export/restore helpers are
retained, but they are not added to Natune's transactional application-backup
format in this port.

## Extension kinds and compatibility

`module.type` declares the kind (`audio_provider`, `metadata_provider`,
`lyrics_provider` or `playback_provider`), as a string or array. One Natune kind
per package is supported when `natuneExtensions@1` is required. All manifests without
that marker retain audio-source behavior and their original type labels. Audio
parsing still requires `searchTracks` and `getTrackStreamUrl`; new kinds do not
relax that audio contract or change its recognized capabilities.

| Kind | Natune Android | Upstream ArchiveTune API v1 |
| --- | --- | --- |
| `audio_provider` / legacy audio | Existing QuickJS audio source and download route; unchanged `.arch` v1 behavior | Same package, methods and settings supported |
| `metadata_provider` | Metadata-only packages: at least one of `searchTracks`, `getHome`, `getLibrary`, `getAlbum`, `getArtist`, `getPlaylist`; bounded runtime calls | Unsupported kind; new methods/features are rejected, or a host may ignore the kind label |
| `lyrics_provider` | `getLyrics(track, context)` joins the Android lyrics chain | Rejected: no lyrics capability/API in upstream v1 |
| `playback_provider` (JS) | URL-based `searchTracks` + `getTrackStreamUrl`, in the `MODULES` source slot | Natune marker rejected; without the marker an upstream host may ignore the kind and treat the methods as audio |
| Built-in native metadata/playback | Registered alongside installed extensions, Android code and existing settings routes | Not a downloadable `.arch` package; no upstream registration |

Natune packages keep `format: "archivetune.module"`, `formatVersion: 1`,
`apiVersion: 1` and the same checksum/entry/size protections. Authors of Natune-only
packages must declare `requiredRuntimeFeatures: ["natuneExtensions@1"]` to opt into
Natune kind interpretation; otherwise all type strings remain upstream audio labels. Add
`lyrics@1` or `metadata@1` as applicable. Upstream rejects these host features;
the API version is not bumped and audio package validation is unchanged. The
Natune packager adds kind-specific validation, preserving the upstream path for
audio packages and all original notices. The nine audio `.arch` files and their
portable catalog are unchanged by this extension work.

Modules settings offers All / Audio sources / Metadata / Lyrics / Playback
filters. Installed JS extensions retain their own enable switch, configuration,
installation review and priority controls. The Modules master switch applies to
all downloaded JS extensions; native extensions use their independent switches.
Only audio/playback JS extensions participate in audio resolution. Metadata
methods return bounded JSON: `getHome(context)`, `getLibrary(context)` and
`getAlbum(id, context)` / `getArtist(id, context)` / `getPlaylist(id, context)`.
`searchTracks(query, limit, context)` retains its v1 track schema. The runtime
exposes these metadata calls for catalog consumers; this change does not add a
generic JS homepage/library renderer. Spotify's native metadata extension uses
its existing catalog screens.

## Lyrics extensions

Export `getLyrics(track, context)` from `provider.js` and declare only
`getLyrics` in `capabilities`, with `module.type: "lyrics_provider"`. `track`
contains `id`, `title`, `artist` (joined credits), `artists` (string array),
`album` (nullable), `duration` (nullable seconds), `explicit` and `isrc`.
`context.settings` keeps the upstream `{key: {value: ...}}` shape, and
`context.track` / `metadata` / `item` contain the same target recording. A module
gets no access to personal Spotify or other native account credentials.

A success returns:

```json
{"format": "lrc", "text": "[00:01.25]Hello", "language": "en"}
```

`format` must be exactly `lrc`, `ttml` or `plain`. `text` is the complete raw
LRC/TTML/plain document; timing is preserved for Natune's existing parsers and
renderers. It must be a nonblank string, contain no NUL, and fit within 1 MiB of
UTF-8. Optional `language` is a 1–64 character string or null. Return null (or
undefined) for no lyrics/instrumental recordings; do not return a sentinel as
successful lyrics. Strings, malformed objects and unsupported formats are
rejected. Requests are bounded and cancellable, with ordinary timeout/provider
failure allowing the existing lyrics chain to continue.

`ModuleLyricsProviders` adapts each installation to `LyricsHelper`. Built-in
providers, the word-sync sweep, sync-tier ranking and renderers remain. Lyrics
Providers settings links to Modules and adds **Prefer lyrics extensions**, which
places the extension group before/after built-ins within the same sync tier;
the group's relative order comes from Modules. Disabled/uninstalled or revised
extensions do not reuse their old provider cache. The existing built-in LRCLIB
provider stays available independently of the example extension.

The example is `addons/modules/natune/lrclib/provider.js`, distributed as
`addons/modules/dist/lrclib.arch`. It uses LRCLIB's exact `/api/get` recording
lookup, prefers synced LRC, falls back to plain text and returns null on 404 or
instrumental responses. It bundles no credentials. Its separate catalog uses
`feat/r3-spotify-ext`; update that URL and `NATUNE_BRANCH` after merge.

## Built-in Spotify extensions

`ExtensionRegistry` exposes installed packages and three native registrations.
The Android-only `NativeExtension` interface has stable `id`, `kind`,
`displayName`, `enabled: Flow<Boolean>`, `settingsRoute` and `priority: Flow<Int>`.
Additional native extensions can implement the interface, register their
preferences/default migration and settings route, and gate their owned callers.
Shared modules, desktopApp and iOS receive no dependency on this registry.

| Extension / stable ID | Kind | Gate and existing settings |
| --- | --- | --- |
| Spotify Metadata / `app.natune.native.spotify.metadata` | Metadata | Home/library selectors, search picker/results, playlists, catalog authentication/enrichment and cached Android Auto folders; Integration → Spotify catalog login/settings |
| Spotify Playback (alternate account) / `app.natune.native.spotify.alternate` | Playback | Dedicated streaming-token owner and source availability; existing alternate-account WebView login/method/settings |
| Spotify Playback (official, librespot) / `app.natune.native.spotify.librespot` | Playback | Catalog-bound OAuth token owner and source availability; existing librespot OAuth login/settings |

Native enablement is separate from credentials and the existing Spotify audio
source switch. Opening a settings link shows the relevant account login without
changing playback priority. The two native playback entries share the existing
`SPOTIFY` source slot; their priority controls reuse `SpotifyStreamEngineKey`
and preserve the existing effective-engine/Premium policy. Disabling the chosen
playback extension removes that Spotify path from the enabled source set. The
existing engine selection still selects one Spotify path; this change does not
introduce automatic cross-account fallback. Catalog and streaming credentials
remain separate, including same-account refusal for alternate playback and the
Premium/catalog-account checks for official playback. Native DRM/decryption is
never routed through QuickJS.

Absent enable keys infer existing catalog/streaming/OAuth sessions or an enabled
legacy source. Registry initialization writes those defaults once; existing
explicit choices win, later sign-out/migration never resets them, and no account
credential or source-enabled preference is changed. Fresh installs migrate all
three switches off. The same keys appear in Modules and the existing Spotify
settings screens.

**Inherited Android librespot gap:** the supplied integration base contains
`SpotifyLibrespotAccount`, PKCE callback and engine settings, but no Android
librespot playback binding or librespot playback dependency in
`app/build.gradle.kts`. `SpotifyStreamingAccount` explicitly stands the unofficial
pipelines down for a Premium librespot selection, falling through to the next
audio source. The official extension is registered, configurable and gated, and
the UI reports that runtime absence. This work does not claim working Android
librespot audio or add an unvalidated native engine. A native playback binding
is still required to fulfill official playback; account/device validation is
also pending.

## Catalog sources and comparisons

The reference sources and icons are preserved under `addons/modules/upstream/`
from ArchiveTuneModules `main` `38565cc0f92eb268eb4f031679f52fc76a79da40`; their original IDs/manifests are unchanged. The
active catalog has nine packages. Modified providers get Natune IDs so installing
one cannot silently replace a publisher's original module. JioSaavn stays byte
for byte unchanged and retains `moe.rukamori.provider.jiosaavn` 1.0.0.

| Package | Upstream / retained Kotlin behavior | Natune module behavior and limits |
| --- | --- | --- |
| TIDAL 1.2.0, `app.natune.provider.tidal` | Upstream 1.1.0 has static token, country lookup, BTS and unprotected DASH; Kotlin also refreshes sessions and handles quality restriction/rate limits | Adds 401 `subStatus`/`sub_status` 5003 fallback `HI_RES_LOSSLESS → LOSSLESS → HIGH`, without retrying unrelated failures; bounded 429 Retry-After cooldown; optional user OAuth client/refresh token and one refresh retry; preserves actual quality, DRM/preview refusals and DASH |
| Qobuz 1.1.0, `app.natune.provider.qobuz` | Upstream already has user/app credentials, MD5 signatures, login and one 401 login retry; Kotlin has richer provider error handling | Keeps those behaviors; adds 429/503 cooldown and descending format fallback only for an explicit unavailable-format response; account determines country; previews remain refused |
| JioSaavn 1.0.0, original ID | Upstream already handles HTML entities, artist credits, details/auth-token exchange and available 320/96 AAC; Kotlin has native decrypt/quality paths | Reuses the upstream readable module unchanged; its 320/96 policy is preserved |
| Deezer 1.0.0, `app.natune.provider.deezer` | Kotlin has both ARL/encrypted native streaming and `DeezerInstanceResolver` plaintext mirrors | Public Deezer metadata plus user plaintext instance, optional instance token, ISRC then track-ID URLs; checks bounded range bytes for FLAC/MP3 and actual content length; ARL/Blowfish native streams cannot be represented as a portable v1 URL |
| Amazon 1.0.0, `app.natune.provider.amazon` | Kotlin uses metadata-driven authorized instance v2 then v1.5, actual response metadata, native encrypted recipes | Public Deezer recording metadata supplies search candidates; same-recording Amazon instance resolution, exact returned title/artist check, v2/v1.5 quality mapping and user operator/Turnstile credentials; plaintext only; challenge UI, CENC decryption and async jobs stay native |
| QQ 1.0.0, `app.natune.provider.qq` | Kotlin desktop search plus Vkey/EVkey, plaintext, QMC decryption and HLS | Own UIN/music key/login type; same desktop search and three Vkey dialects; plaintext FLAC/MP3 or HLS only; encrypted eKey/QMC answers are explicitly refused |
| Qobuz instance 1.0.0, `app.natune.provider.qobuz.instance` | Public instance `/api/get-music` and `/api/download-music` | Same account-free protocol, user HTTPS instance, format selection and direct URL/preview validation; no embedded community credentials or discovery key |
| TIDAL instance 1.0.0, `app.natune.provider.tidal.instance` | Public instance `/search/`, `/track/`, native queued/manifest variants | Account-free user instance, search metadata, plaintext URL/BTS and unprotected inline DASH; queued jobs and alternate manifest dialects are not implemented |
| ArchivePool 1.0.0, `app.natune.provider.archivepool` | Natune `/api/accounts` lease, bearer read key, v2 header, encrypted fields, refusal backoff and ETag | Same lease/header/field protocol, configurable pool URL and secret read key, ETag/30-minute memory cache, refusal cooldown, up to four accounts per service attempt; reuses the TIDAL/Qobuz fork code; no reports or credential contribution |

Apple Music is not packaged: the retained `KtorAppleMusicRepository` resolves
webPlayback assets requiring the native license exchange/CENC path. There is no
non-DRM full-track implementation to port. Preview URLs are not full songs and
are not published as an Apple module. Its existing native source remains intact.

Refresh tokens, leases and cooldowns live in module runtime memory; upstream
CommonJS API v1 exposes no persistent provider-setting mutation API. A restarted
or evicted engine loses rotated refresh tokens, lease cache and cooldown state.
Users may need to reconfigure rotated credentials. Personal app account tokens
are never read or overwritten by these modules.

## ArchivePool cryptography

The separate ArchivePool module defaults to
`https://archivepool.natune.workers.dev`, and uses only the user's password-setting
read key. It requests `GET /api/accounts`, `Authorization: Bearer <readKey>` and
`X-Pool-Client: v2`. Account arrays are read from `<service>.accounts` or
`<service>`; TIDAL fields are `token`, `refreshToken`, `countryCode`, Qobuz fields
are `token`, `appId`, `appSecret`.

Encrypted fields are `enc:1:<base64 IV>:<base64 ciphertext+tag>`. The AES-256 key
is SHA-256 of UTF-8 `archivepool-client:` plus the trimmed read key. The pure JS
implementation in `lib/crypto.js` supports the protocol's 96-bit IV and 128-bit
tag, checks authentication before returning plaintext, and uses no bundled key.
It works without `crypto.subtle`, a native crypto bridge or a Natune host
extension. The regression fixtures compare it with independently generated Node
AES-GCM/SHA-256 vectors and tampered tags; those fixtures await CI execution.

## Removed upstream traps and audit

- Removed `NetworkGatekeeper` import/interceptor from `ModuleNetworkPolicy`.
  Natune's existing proxy/DNS preferences remain, with no private network gate.
- Deleted the `8SM1.` alphabet, hardcoded mask/key, custom ciphertext/tag decoder
  and decryption path. Raw, comment-prefixed and exported-string envelopes get
  `ENCRYPTED_8SM1_REJECTED` with an explicit readable-package instruction.
- Searched ported Kotlin/JS for gatekeeper, bearer BuildConfig fields, koiverse /
  koiiverse endpoints, package/signature checks, telemetry, remote kill switches
  and hardcoded publisher URLs. No additional such enforcement/phone-home code
  was found. The package-name read in the extension host obtains the installed
  app version; it does not compare package identity or authorize network use.
- Retained user-declared extension domain permissions, HTTPS same-origin signed
  sessions, callback state/expiry verification, checksums and cross-origin secret
  stripping. These are package/network protections with user-provided endpoints,
  not Rukamori service gates. The upstream catalog suggestion is an explicit user
  choice; the host does not fetch catalogs at startup.

GPL source headers remain on ported host files. ArchiveTuneModules includes an
LGPL-3.0-only repository notice/LICENSE plus GPL-3.0 manifest declarations and a
GPL packager header. All original notices/texts and manifests are preserved;
modified Natune sources keep upstream attribution and their fork metadata.
`docs/CREDITS.md` records provenance. No credentials, default API tokens, keys or
signing material are included.

## Building and checking modules

Run `python3 addons/modules/scripts/build_catalog.py`. It bundles `lib/common.js`,
custom `main.js` sources, and the TIDAL/Qobuz forks into `provider.js`, then runs
`package_module.py --check`, package creation and `--inspect` for
each provider, and writes `dist/*.arch` plus SHA-256 catalog entries. It performs
no Gradle/JVM operation or live provider request. For manual authoring, use
`python3 addons/modules/scripts/package_module.py <provider-directory> --check`
and supply a second output-file argument to package; inspect with `--inspect`.
The vendored packager preserves upstream format/API v1 audio authoring validation
and adds the Natune kind-specific capability rules. The builder also packages
`natune/` into the separate `natune-extensions.json` catalog.

Optional syntax check: `node --check <provider.js>`. CI runs
`node --test addons/modules/tests/providers.test.js` for synthetic provider and
crypto fixtures. The existing app unit-test task depends on
`:modules:testDebugUnitTest`, which covers readable/encrypted input, checksum,
typed settings, minimum-version/features and upstream registry URL parsing.

## Validation and playback risks

On the implementation VM: nine packages passed check/package/inspect, JS passed
syntax checks, repository light checks passed, and the allowed iOS Python suite
passed 26 tests. No Gradle/JVM build, lint, unit-test execution, device test, live
account/network probe, desktop/iOS compilation or cross-app installation was
performed. JVM and JS runtime fixtures require the orchestrator's CI run. Extension-specific
checks, commits and remaining gaps are recorded in
[the extension implementation report](modules-extensions-report.md).

The largest integration risk is source preparation timing: module-enabled tracks
may perform a source-chain preparation before standard Media3 resolution; a
standard fallback can repeat an earlier provider attempt. Module-disabled tracks
skip that preflight. Inline DASH/HLS, authorization callbacks, resumed downloads,
offline manifests, source switches, cancellation and physical audio need device
validation. Modules are opt-in, existing sources remain in their established
chain, and module failure continues to the existing fallback. Pure JS crypto,
provider API dialects and user-instance response shapes require CI/live testing
before claiming service parity. The portable module variants intentionally do
not replace native DRM/decryption/queued-job features.

### R3 iOS source increment (unverified)

The iOS manager installs bounded readable API-v1 `.arch` audio packages and executes the retained
`module-host.js` through the already-declared QuickJS dependency. Typed configuration is kept in
Keychain. The ordinary source chain consumes supported progressive/HLS answers. Inline DASH,
signed/native extension APIs, raw legacy text import, module lyrics and catalog update browsing
remain unavailable; no Apple compilation, provider success or device execution is claimed.
