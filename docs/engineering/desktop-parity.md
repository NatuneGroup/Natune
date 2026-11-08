# Desktop parity follow-up

Branch `feat/r3-desktop-parity`, based on `origin/dev` at `c289e50f`.
Implementation is source-reviewed only until the orchestrator integrates it and runs CI.
No local Gradle/JVM build, lint, tests or Windows/Linux device playback is claimed.

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
also available to offline export. Account Apple/TIDAL/Deezer, experimental Musixmatch
and module lyrics still need their host providers. Ordinary non-AI translation is not
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

Module manifest audio and metadata-surface mounting, signed-session browser/callback
UI, complete catalog update UI and binary native-extension loading remain incomplete.
Android DEX/ABI extensions cannot be loaded as desktop JNI. This is Partial module
parity, not a claim that every Android extension works. The shared settings list adds
one desktop-only Modules row/resource; existing hosts keep their defaults. QuickJS
compilation, archive fixtures, secure storage and live providers remain unverified.
