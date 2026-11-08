# Desktop parity follow-up

Branch `feat/r3-desktop-parity`, based on `origin/dev` at `c289e50f`.
CI has compiled an intermediate branch revision; newer edits still need exact-head CI.
No local Gradle/JVM build, lint, JVM tests or Windows/Linux device playback is claimed.

## Spotify

Desktop playback now admits the existing catalog-bound Premium Librespot OAuth
grant through the existing account owner, calls the pinned `native/librespot` Rust
JNI resolver, and feeds its encrypted Vorbis stream through the shared Spotify
CTR preparation and account-scoped plaintext lease. Android's JNI coroutine worker
is reused through generated desktop sources; only packaged-library discovery differs.
Spotify remains streaming-only. Unsupported native proxy modes decline the source.
Unofficial playback retains its separate-account policy and the selected Echo method.

The release and desktop-compile workflows prepare optional JNI on native Linux x64,
Linux arm64 and Windows x64 runners. Exact recipe/host/checksum caches are validated;
only trusted dev/main producers save caches. Failed native preparation omits the
engine explicitly, reports a CI warning, and retains the configured source fallback.
Packages validate prepared JNI and ship the retained Librespot GPL notice.
Native compilation, packaging and authenticated playback remain unverified.

The other unofficial Spotify methods use Android WebView/MediaDrm Widevine.
The JVM host has no licensed desktop CDM binding, so these remain unavailable;
the stored method is never silently changed to Echo.

## Offline downloads

Desktop downloads now read the player's plaintext lease for progressive, TIDAL
DASH, supported HLS/AES-128, Deezer, Amazon CENC and QQ preparations. Spotify
remains excluded and protected Apple/FairPlay remains unsupported. Request-owned
staging, bounded reads, cancellation interruption, complete-length checks and the
existing atomic file/index commit are retained. Podcast episode refs are accepted
by the download/player menus and retain their kind in the portable stored row.
The only commonMain change is allowing downloaded EPISODE rows in that existing
index serializer; songs and cache rows keep their previous contract.
Playback/export of these files still needs CI and Windows/Linux runtime validation.

Automatic song caching now fills bounded complete plaintext files after successful
playback resolution, records them in the existing CACHE index and reuses them before
network resolution. It serializes fills and honors the song-cache size setting;
Spotify and episodes are excluded from automatic caching. Explicit cache removal
deletes both the index row and its file. A separate cache fill can duplicate network
traffic while the player reads; it does not yet tee decoder reads. Runtime eviction
and offline playback remain unverified.

## Tagged exports

Both item-menu and bulk export now reuse Android's AudioTagger and its pinned
pure-Java jaudiotagger library on a request-owned file with a detected extension,
before atomic publication. Title/artist/album/ISRC and available artwork are tagged;
the current song's loaded lyric document is embedded when available. Artwork is
bounded and best-effort; unsupported container tags are reported in diagnostics.
Offline artwork and lyrics for songs absent from the current library/document are
not invented. Source downloads are never modified. Container/device validation is
unverified. No commonMain change was needed for the tag writer.

## Lyrics

Desktop has a bounded persistent provider lyric cache and separate durable user
edits. The shared HostLyrics owner gained optional host persistence and extra-provider
callbacks (additive defaults preserve existing hosts). Desktop reuses Android's direct
YouTube Music/subtitle implementations through generated sources. The desktop lyric
pane mounts editing plus shared AI translate/romanize/cancel/undo actions, and shared
LyricsTranslationPersistence commits successful automatic/manual translations through
the same revision-checked store, refreshing the displayed document. Saved lyrics are
also available to offline export. Account Apple/TIDAL/Deezer now use small additive lyric methods on the existing
shared repositories, preserving their catalog matches and authorization. TIDAL/Deezer
can use the existing pooled leases; experimental Musixmatch reuses its pure-JVM
module and remains opt-in. Compatible module lyrics are also wired. Ordinary non-AI translation is not
mounted. Persistence, generated adapters and AI rendering remain CI/runtime unverified.

## Pitch and silence

Desktop now honors independent pitch using FFmpeg asetrate plus compensating
atempo, preserving the requested transport speed and source clock. The player
pitch control is enabled. Silence skipping consumes sustained PCM below -50 dB
after 150 ms, advancing the source clock for skipped frames and preserving the
existing ring/segment bookkeeping. The shared stage plan gates both features.
These native changes require CI and audible playback/seek/crossfade validation.

The desktop decoder now feeds stereo float PCM into the existing shared DspGraph
on its producer thread, before the native ring/mixer. The shared EQ/preamp, clarity,
DC blocker, limiter and dither run once; FFmpeg retains tempo/pitch and the native
mixer retains output volume/transitions. JNI holds reusable block buffers and frees
them after joining the decoder. Load/seek generations reset shared DSP history.
Normalization consumes reported ReplayGain/peak tags, with a bounded five-second
PCM-head RMS/peak fallback (-18 dB RMS, gain capped to -12/+6 dB). Silent/inconclusive
heads stay at unity; this is not whole-track loudness analysis. All DSP/pitch/native
changes remain uncompiled and audibly unverified pending CI/runtime testing.

## Modules and extensions

Desktop mounts the retained .arch package reader, catalog reader and QuickJS
API-v1 host through generated JVM sources. Only Android assets/clock/image helpers
and Keystore storage are adapted; module state/settings use the existing OS vault
without plaintext fallback. A desktop settings page installs packages/catalog entries,
inspects manifests, enables/disables sources and edits/removes packages. Updates can
replace the same installation through the owner API; packages start disabled.
Progressive module audio joins the existing source order and download path through
revocable admissions; compatible script lyric extensions join HostLyrics.

The concurrent module follow-up mounts manifest-driven settings, signed-session
browser/callback controls and metadata browse pages. Module HLS now reuses the
player's bounded clear/AES-128 preparation for playback and downloads; native child
URLs retain the module's declared domain policy, and explicit stream headers stay
on their original origin. Generic module DASH and binary native-extension loading
remain incomplete.
Android DEX/ABI extensions cannot be loaded as desktop JNI. This is Partial module
parity, not a claim that every Android extension works. The shared settings list adds
one desktop-only Modules row/resource; existing hosts keep their defaults. QuickJS
compilation, archive fixtures, secure storage and live providers remain unverified.

## ListenBrainz and DNS

The desktop graph submits opt-in ListenBrainz playing-now and listened events using
the existing shared scrobble timer (buffering/pauses do not count). The existing
integration toggle and vault-backed token drive the submitter; network failures are
bounded and reported. Desktop HTTP and core YouTube now consume the selected DoH
provider through Android's existing factory, including its custom HTTPS endpoint.
Proxy settings retain their existing routing. Neither integration has live-network
validation for this branch.

## QQ home

The desktop home source switcher now offers QQ for an accepted personal account.
The existing shared QQ repository gained an additive GetAll/GetDetail chart method
using Android's endpoints and bounded concurrent requests; the desktop mounts the
existing QqHomeScreen and queues canonical QQ chart songs through the one queue.
The feed is refreshed on account changes and respects admission cancellation.
Chart network responses and playback are unverified. Failed chart details currently
omit that section rather than retaining Android's preview fallback.

## Appearance editors

Desktop mounts palette and seed-based theme creation with shared MaterialKolor
schemes, preview swatches, persisted color/style and bounded theme JSON import/export.
Custom application images update the live window/task-switcher icon; package/pinned
launcher icons still follow the installer. Android's four-seed theme codec and color merge are now reused through generated
desktop sources. The desktop theme creator edits all four seeds and reads/writes
the Android preference and JSON formats. The full launcher icon pack is not ported. The settings list adds desktop-only editor links
and the icon path key is additive in the shared preference registry. UI compilation
and OS icon behavior remain unverified.

## External downloader

The enabled item/player action now hands a public YouTube content URL to the user's
installed yt-dlp executable after a destination-folder choice. ProcessBuilder receives
argument values directly; no shell interpolation or streaming credentials are used.
A missing executable reports failure. Launch means handoff, not a completed download;
Android package/plugin intents are not a desktop protocol. The remaining episode
menu restriction is removed, and cache clear/limit actions now reconcile cache rows
with removed files. Process/UI/runtime validation remains unverified.

## Automix analysis

Automix now analyzes complete local/download/cache files through a manual native
decoder at 11,025 Hz and the shared TrackFeatures analyzer. Work is opt-in, serial,
limited to six-minute songs and a 30-second budget, cancellable, and cached in a
bounded source-file/revision-bound memory store. The retained Android transition
planner is reused without changing its source notices. Its conservative fade/cue
answers feed the desktop handoff; an analysis revision can refresh an armed decision.
Beat/vocal features are heuristics. Advanced beat stretching, bass-swap/sweep
envelopes and optional ONNX models remain unavailable on this mixer, so higher
ambition tiers are not claimed. Native decoding, analysis and transitions remain
unverified. No commonMain change was needed for this mount.

## Module details and signed sessions

The module manager now opens manifest details (identity, author, license, provider
types, capabilities, runtime requirements and web links), validates typed settings,
and updates an existing installation only when its module ID matches. Updates are
disabled until reviewed again. Session verification uses the retained signed-grant
host and a separate isolated Chromium profile per installation; nested callback
state is checked before launching and again before exchange. Cancellation releases
the browser and callback listener. Clearing a session preserves module settings.
Android APK/DEX/native binary extension packages are explicitly unsupported; the
page links to the portable Spotify catalog, Echo and optional Librespot adapters.
No commonMain source changed. Live sign-in and desktop UI remain unverified.

The shared player's title and artist actions now reach the existing desktop album
route/item menu and artist picker. The existing capability-dispatch contract test
was updated for those callbacks; it has not been run locally. Output routing/Cast
remains unavailable in the player.

## Module metadata pages

Enabled modules now expose their declared search/home/library/album/artist/playlist
methods through the details page. The host projects bounded catalog fields into
readable rows and uses the one queue for playback. Audio-module track IDs preserve
the installation and the provider ID, and try that enabled module before the
ordinary fallback chain; metadata-only rows use normal title/artist matching.
Changed or disabled installations invalidate in-flight responses. Arbitrary JSON
and session fields are not rendered. Pagination and provider-specific catalog
schemas beyond the supported containers remain partial; live providers are
unverified. This addition changes desktop JVM sources only.

## Broader Playback Pool

Desktop retains encrypted Apple media-user-token and usable Amazon instance entries
in the existing isolated vault feed cache, with the existing toggle, throttle,
backoff and replacement handling. Apple lyrics can use a pooled token; Amazon
tries personal instances first, then pooled hosts with host-bound operator/JWT
material and revocable admissions. The shared Amazon JWT safety policy is reused.
Account-only Amazon entries still cannot stream on this host; Spotify pool
credentials are not mounted into its separately admitted streaming account.
Provider playback and refreshed pool entries remain unverified.

The shared root adds one optional saved-theme scheme callback for desktop's reused
Android codec. Its default is absent, so other hosts keep their existing behavior.
The codec/generator are extracted from the retained Android Theme.kt at build time
with its original notice, and MaterialKolor uses the existing version pin. This
source extraction and four-seed UI still need CI/runtime validation.

The only retained Android module change for native HLS is an additive permission
snapshot accessor on ModuleRuntime; its JS API and Android behavior are unchanged.
Theme source extraction normalizes line endings for the Windows build, and the
four-seed editor scrolls in smaller windows.

Local-audio sharing now copies a native file-list clipboard item in the item/player
menus and main player Share action, with Linux URI/clipboard variants. Users can
paste into compatible apps/file managers; missing files or unavailable clipboards
report failure. Download export remains the format-named, tagged file path. Clipboard
behavior is unverified on Windows/Linux. The existing missing-file contract test is
updated without running local JVM tests.

## Desktop navigation follow-up

Artist Latest release and release-grid cards now use an explicit album action,
preserving the provider browse ID even when a stored reference has a podcast kind
hint. A shared navigation UI regression uses the Daft Punk Drumless Edition title
and verifies the album destination, ID and provider. The reported N20261108
`f8893595` screenshot has not been reproduced on the follow-up build; that source
already typed YouTube releases as albums, so its original runtime cause remains
unverified.

Desktop title-bar and destination dropdowns permit outside clicks to reach the
underlying controls; the gear also clears the toolbar popup before opening
Settings. A pointer regression covers the single-click action. The current title
bar's list glyph toggles the sidebar; it does not own a dropdown. Phone destination
menus retain their existing focus behavior.

Generic album details and the dedicated album screen display aligned occurrence
numbers and available track durations. Unknown durations stay absent, and the
repeated-track UI regression verifies that numbering preserves selection identity.
These fixes change shared-ui commonMain presentation/navigation only. The earlier
four-seed scheme callback also lives in commonMain. No iosMain or iosApp files were
edited. All new UI regressions await the orchestrator's follow-up PR CI; only the
permitted repository check ran locally.

Remaining material boundaries include module DASH/live/master or protected
manifest forms, Android binary extensions, protected Apple audio, raw USB host
control, Cast/output routing and voice capture. They need additional platform or
provider adapters. Scheduled backups still require a running desktop process, and
advanced Automix/refraction remain limited by the current mixer/renderer. Live
accounts, clipboard integration, physical output and the reported nightly UI
behavior need Windows/Linux runtime validation.

## Remaining desktop boundaries

- Other unofficial Spotify methods and protected Apple playback require licensed
  WebView/MediaDrm/CDM hosts absent from this JVM adapter. Echo/Librespot remain
  independently selected, and refusal keeps the established source fallback.
- Optional exclusive output now uses WASAPI exclusive on Windows, direct ALSA
  `hw:` on Linux, and Core Audio hog ownership with native rate/physical-format
  selection on macOS. The decoded integer/float lane bypasses software stages;
  rate/format changes reopen the sink, and device refusal has a visible shared
  fallback. Output settings retain the device picker and publish sink diagnostics.
  The raw lane drains the negotiated hardware queue after its last PCM before
  closing on EOF or a format change. Stop/seek still discard obsolete output.
  Native/WAVE speaker masks are passed to the sink; mismatched, custom or unknown
  channel maps fall back to shared output rather than silently mix/reorder PCM.
  Core Audio physical-format readback must match the rate, signedness, endian and
  integer alignment of the direct lane before the hogged sink is accepted.
  Physical DAC behavior remains unverified; raw USB-host control is not implemented.
- Google Cast and speech search have no portable receiver/STT host in the retained
  dependencies. OS routing and microphone recognition are existing separate features.
- Folder/Drive schedules remain process-bound. A standalone OS service/task entry
  point with isolated database, vault and lifecycle ownership is not implemented.
- Generic module DASH needs a source-preserving shared native recipe; Android
  binary extensions require desktop binaries/load contracts. Script packages, HLS,
  signed-session controls, settings and metadata pages are mounted.
- Advanced Automix stretch/bass/sweep execution and optional models remain
  unsupported by this mixer. Account-only Amazon and Spotify pool streaming tiers
  remain outside their existing desktop account contracts.
- Launcher icon packs, refraction, Cast/output actions and Android-specific live
  pipeline diagnostics are not implied by the working desktop editors/menus.

## Validation

`python3 scripts/check_repository.py --worktree`: zero findings.
`python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`: 28 passed.
The observed [desktop/Android compile workflow](https://github.com/NatuneGroup/Natune/actions/runs/42488983487),
[unit workflow](https://github.com/NatuneGroup/Natune/actions/runs/42488983501) and
[iOS compile workflow](https://github.com/NatuneGroup/Natune/actions/runs/42488983516)
passed at intermediate `192942d3`. This does not establish exact-head compile
success, optional Librespot binaries, native desktop packaging or audible/device QA
for later four-seed theme, module HLS and clipboard edits. The orchestrator owns
the next coherent CI batch.

Common changes are additive: downloaded-episode index admission; account lyrics
and QQ chart methods on existing repositories; optional HostLyrics persistence/extra
providers and root saved-theme callbacks; desktop-only settings links/resources;
and the desktop icon preference key/registry. Android module source gained only
the additive immutable stream-permission accessor. No iOS source set was edited by
this implementation work.

Module streaming also binds the retained per-installation encrypted cookie jar to
its accepted stream scope. Only that module's cookies can reach its origin; catalog
and unrelated provider cookie jars remain separate. Librespot honors Android's
global quality cap/low-data policy, verifies ISRC matches, and uses the shared
failure policy to avoid reconnecting a healthy session after audio-key denial.
These final adapters remain source-checked pending exact-head CI.
