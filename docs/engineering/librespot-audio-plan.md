# Librespot audio implementation plan

Scope: the audio engine and its source-chain adapter;
Spotify catalog/home/library/playlist mapping and Modules settings stay outside this change.

## Decision and current evidence

Use **Rust librespot core + metadata through a small JNI library**, compiled per
Android ABI on CI. Media3 remains the player, decoder, cache and output owner.
Do not embed librespot's player, a second output engine, or its discovery stack.
Desktop reuses the same cdylib through JNI; iOS packaging is a follow-up (see below).

Upstream evidence behind the decision (librespot pin `15e02e86`):

- [Rust repository](https://github.com/librespot-org/librespot) is active; the
  reviewed `dev` head is `15e02e86c72d96dd39d548e3749f00abe39ffdde`, dated November 7.
  [Latest release v0.8.0](https://github.com/librespot-org/librespot/releases/tag/v0.8.0)
  dates to 2025-11-10. Pin the reviewed source commit, not the moving branch.
- [librespot-java README](https://github.com/librespot-org/librespot-java/blob/52a8c24215e5a9b921da8e7219c49937ec87ed88/README.md)
  explicitly says development moved to go-librespot. Its latest commit is
  2025-11-07; [Maven metadata](https://repo.maven.apache.org/maven2/xyz/gianlu/librespot/librespot-lib/maven-metadata.xml)
  still advertises **1.6.5**, released 2024-12-31. Later login5 work is unreleased.
  A convenient JVM dependency would carry obsolete protocol code; maintaining
  our own Java fork or port would add more work than the native bridge.
- [Outify](https://github.com/iTomKo/Outify/tree/cd857ef04ac34627a920fbd3d6f7ca0c6a1f9119)
  is an active Kotlin Android client (2026-11-06 head). Its
  [Cargo dependencies](https://github.com/iTomKo/Outify/blob/cd857ef04ac34627a920fbd3d6f7ca0c6a1f9119/rust/librespot-ffi/Cargo.toml),
  [.gitmodules](https://github.com/iTomKo/Outify/blob/cd857ef04ac34627a920fbd3d6f7ca0c6a1f9119/.gitmodules)
  and [nightly build](https://github.com/iTomKo/Outify/blob/cd857ef04ac34627a920fbd3d6f7ca0c6a1f9119/.github/workflows/nightly.yml)
  show Rust JNI and an **iTomKo/librespot fork**, including an Android audio backend;
  it does not use xyz.gianlu's JVM library. It is evidence of Android integration,
  not proof Natune's own OAuth app or every Premium account works. The historical
  [librespot-android](https://github.com/devgianlu/librespot-android) example uses Java.
  Outify's gitlink names fork commit `2f13c2374beb78323e1d03d31232c5d6df535c8f`;
  reading that exact fork source returned HTTP 404/422. Its dependencies verify
  the Rust/JNI choice, but the fork's audio-key implementation was not inspected.
- [Session::connect](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/core/src/session.rs)
  accepts `Credentials::with_access_token`, receives reusable AP credentials,
  reconnects with them and retains their auth data for
  [login5](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/core/src/login5.rs).
  login5 obtains the spclient bearer from those stored credentials. Natune supplies
  only its registered PKCE grant; no catalog cookie/token or embedded OAuth secret.
  The library's protocol client identity remains its upstream default; it is not
  a substitute for an app-owned OAuth client or a quota workaround.
- [AudioItem](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/metadata/src/audio/item.rs),
  [formats](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/metadata/src/audio/file.rs)
  and [storage resolution](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/core/src/spclient.rs)
  still implement extended metadata and encrypted CDN audio. Vorbis 96/160/320,
  AAC and FLAC appear in the protocol definitions; that does **not** establish
  which files an individual account/track receives. Start with Vorbis (Media3
  already decodes it); unsupported/no-file results fail the source. No invented
  AAC fallback, lossless claim or guessed PCM sample rate.
- [Upstream warning added November 7](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/README.md)
  and [issue #1649](https://github.com/librespot-org/librespot/issues/1649) report
  newer accounts authenticate but Spotify denies legacy AP audio keys with
  `audio key error 0 1`; no known workaround exists. Premium is necessary but
  insufficient. Treat key denial as source failure; never feed encrypted noise
  to the decoder. This implementation cannot promise playback for affected accounts.
- [AP audio-key API](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/core/src/audio_key.rs)
  remains implemented. This pin has no PlayPlay replacement. Natune's retained
  PlayPlay pipeline belongs to the separate Unofficial account flow and is **not**
  a supported remedy for #1649. Reuse the existing AES-CTR byte/range decoding
  where valid, with the same IV and decrypted Vorbis header skip (0xA7).
- Rust [MIT license](https://github.com/librespot-org/librespot/blob/15e02e86c72d96dd39d548e3749f00abe39ffdde/LICENSE)
  and Java Apache-2.0 are compatible with Natune GPL-3.0. Keep the Rust notice in
  the repository and APK assets and record attribution in `docs/CREDITS.md`.
  No Outify code is vendored. Librespot is an unofficial protocol implementation,
  not Spotify's official SDK or an endorsement of this integration.

## Implementation contract

1. A pinned core/metadata-only Rust cdylib handles AP authentication, login5,
   playable track metadata, bounded relinking, Vorbis quality choice, audio key
   and CDN resolution. Disable protocol logging (upstream trace logs contain
   tokens); return only sanitized failure categories across JNI. No disk audio or
   reusable-credential cache. Fresh OAuth grants remain the session's only persistent
   credentials. Override the upstream CLI `exit(1)` on plan loss for embedding;
   reject non-Premium sessions in the adapter instead of killing the app process.
2. Session work is serialized, deadline bounded and generation guarded. Reuse a
   healthy session; rebuild an invalid one on a later playback request. Refresh
   through `SpotifyLibrespotAccount.accessToken()`. Logout, catalog replacement,
   engine change or Premium loss tears down the native session and private stream
   descriptors. No background reconnect loop. Use one spclient attempt; HTTP
   401/403/429 do not trigger a second metadata/search route.
3. Resolve the queued Spotify track id directly. Otherwise try a bounded ISRC
   lookup, then title/artist matching only when there is no exact recording id,
   using the Librespot OAuth bearer and the existing shared request cooldown policy.
   No catalog metadata restructuring. Add pure shared tests for id/URI, quality,
   engine admission and failure-to-fallback behavior; CI compiles/runs Kotlin tests.
4. An opaque `librespot-spotify://` reference holds no token, key or signed CDN URL.
   Memory-only descriptors feed an Android Media3 DataSource, reusing Echo's
   seekable AES-CTR implementation below the player cache. Tag cache entries with
   engine/quality/session identity so bytes cannot cross account or encoding changes.
   Resolve only through `resolveMultiSourceDataSpec` when effective engine is
   LIBRESPOT. No download/cast source and no switch to Unofficial on native failure.
5. Quality uses the existing audio quality preference: LOW → 96, HIGH → 160,
   HIGHEST → 320; AUTO → best available (96 under low-data mode). Prefer the
   requested ceiling, descending available Vorbis tiers; a higher-only file can
   fail a capped tier. Surface failures to the existing source fallback/recovery.

## Packaging and cost

CI cross-compiles using the existing pinned Android NDK, Rust, rustls with WebPKI
roots and JNI. Package a stripped, size-optimized .so per shipped ABI with 16 KiB
ELF page alignment. No new Maven playback dependency and no duplicate native decoder,
CPAL/ALSA, OpenSSL or discovery closure. Rust compilation increases CI cost; share
cached Cargo inputs and exact prepared libraries under existing trust rules and
select only each build's ABIs.
Expected native size is several MiB per ABI; **actual APK/library size and build
minutes must be measured in CI**, not asserted from source. Kotlin-only unit-test
jobs need no native library. After successful native preparation, APK assembly
requires the selected ABI libraries and CI audits their packaged bytes. Native-toolchain
failure is tolerated: without an exact valid cache, remove partial JNI, warn, and
assemble an APK with this optional source unavailable. A green APK job alone is not native-build evidence.

Desktop loads the same cdylib through the shared `SpotifyLibrespotNative` JNI object
(`DesktopLibrespotLibrary`), and `DesktopSpotifyLibrespotAudio` adapts it to the desktop
source chain with the existing native AES-CTR/FFmpeg byte recipe. `.github/actions/build-desktop-librespot`
runs `native/librespot/build-desktop.sh` for linux-x64, linux-arm64 and windows-x64; other hosts
(macOS) and failed native builds ship without the library and fall back to the configured sources.
iOS needs a C/Swift bridge, per-target static libraries and AVFoundation byte transport;
JNI is not an iOS solution, so iOS keeps OAuth sign-in only and reports Librespot unavailable.

## Validation and outstanding evidence

CI must compile Rust for all shipped ABIs and Android/shared Kotlin tests, then
assemble release-shaped APKs and measure their closure. Device QA must cover the
registered OAuth app, an eligible Premium account and an affected #1649 account,
96/160/320 availability, seeks, cache replay, network loss/reconnect, token rotation,
logout/account replacement, source fallback, low-data mode and concurrent prefetch.
A green compiler or fixture result is not device playback evidence.

## Source file map

| Area | Files / behavior |
|---|---|
| Native | [JNI/session](../../native/librespot/src/lib.rs), [Cargo lock](../../native/librespot/Cargo.lock), [embedding patches](../../native/librespot/embedding.patch), [ABI recipe](../../native/librespot/build-android.sh) |
| Policy/tests | [Audio policy](../../shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/source/SpotifyLibrespotAudioPolicy.kt), [common tests](../../shared-domain/src/commonTest/kotlin/moe/rukamori/archivetune/shared/domain/source/SpotifyLibrespotAudioPolicyTest.kt) |
| Android session | [Audio lifecycle/descriptors](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot/SpotifyLibrespotAudio.kt), [JNI worker](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot/SpotifyLibrespotNative.kt), [OAuth search](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot/SpotifyLibrespotTrackSearch.kt) |
| Data source | [Guarded Media3 source](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot/SpotifyLibrespotDataSource.kt), [reused Echo decryptor](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/echo/EchoDecryptingDataSource.kt) |
| Source-chain adapter/cache | [SpotifyAudioResolver](../../app/src/main/kotlin/moe/rukamori/archivetune/playback/SpotifyAudioResolver.kt), [SpotifyAudioCache](../../app/src/main/kotlin/moe/rukamori/archivetune/playback/SpotifyAudioCache.kt), [MusicService](../../app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt) |
| OAuth binding | [Account](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/SpotifyLibrespotAccount.kt) persists a newly verified catalog binding even when returning a cached token; [streaming account](../../app/src/main/kotlin/moe/rukamori/archivetune/spotify/SpotifyStreamingAccount.kt) exposes its existing effective-engine calculation |
| Packaging | [Gradle ABI gate](../../app/build.gradle.kts), [R8 JNI keep](../../app/proguard-rules.pro), [MIT asset](../../app/src/main/assets/licenses/librespot-MIT.txt), [CI action](../../.github/actions/build-librespot/action.yml) |
| Desktop | [JNI loader](../../desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/DesktopLibrespotLibrary.kt), [source adapter](../../desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/DesktopSpotifyLibrespotAudio.kt), [host recipe](../../native/librespot/build-desktop.sh), [CI action](../../.github/actions/build-desktop-librespot/action.yml) |
| CI callers | Android assembly/lint and ARM64 candidate in [Build Pull Request](../../.github/workflows/build_pull_request.yml), shipping matrix in [release artifacts](../../.github/workflows/_release-artifacts.yml), [emulator QA](../../.github/workflows/android_emulator_qa.yml) |

The binding supplies the actual Android API level (sysinfo can be unknown) and
keeps the upstream OAuth StoredCredential/Keymaster profile. Native cancellation
uses a watch signal in addition to generation checks, dropping in-flight futures
on logout/cancellation. Entry checks also reject native work queued before logout.
Client-token authentication/throttle failures and login5 protocol timeout/throttle
responses do not dispatch another native request; challenge exchange stays bounded.
Cache admission is outside both playback caches, with a
separate guard on resolved private streams. Vorbis decoding reuses the existing
CTR implementation at a 32 KiB read size; the Unofficial default remains 16 bytes.
Cache tags are validated under the read guard's captured account/engine/quality;
read-admission failures re-prepare through the source chain at the current position.
Shared tests cover cache account/engine/quality separation and malformed renditions.
No Maven dependency or extra output engine is involved.

The implementation deliberately does not advertise or control a Connect receiver.
It opens a protocol audio session for local playback; no Spirc/player/discovery
backend is linked. iOS still returns source unavailable while using Librespot.
SOCKS and authenticated HTTP proxy configurations fail the source rather than
bypassing the selected proxy. An upstream audio-key-denied account remains
unsupported even with Premium; it falls through to the next source.

## End-to-end status

| End-to-end requirement | Current source state / evidence still missing |
|---|---|
| Native library / APK | `build_pull_request.yml` builds universal Debug and ARM64 candidate; `nightly.yml`/`release.yml` delegate to `_release-artifacts.yml`; emulator QA also prepares JNI. Mobile universal uses arm64-v8a/x86_64; TV universal also uses armeabi-v7a/x86. Per-ABI flavors keep their own ABI. Exact JNI/Cargo caches and APK-byte audits are wired; no compiled/library/APK result yet. |
| JNI names | Five Rust exports match the Kotlin object's externals by source inspection, with an R8 keep rule. CI compares actual dynamic exports on fresh builds; JNI loading/minified invocation remains unrun. |
| Stored OAuth grant | `SpotifyLibrespotAccount.accessToken()` returns the fresh cached/refresh grant; AP `Credentials::with_access_token` yields in-memory reusable credentials for login5. Catalog cookie/token is used only for binding checks. Registered client/redirect and real Premium login remain unverified. |
| Audio key / decrypt | AP key request and CDN URLs feed opaque descriptors, then AES-128-CTR with the existing IV and decrypted 0xA7 Vorbis header skip. Key denial returns source failure. Real decrypted/Ogg audio is unverified. |
| Media3 seeking | Logical positions add the header skip, align the CDN range to 16 bytes and derive the counter from the absolute block position. Reported length removes the skipped prefix. Nonzero/near-end seeks and cached replay need device evidence. |
| Quality | LOW/low-data 96, HIGH 160 then 96, HIGHEST/AUTO 320 then lower available Vorbis tiers. Pure tests exist; Kotlin tests and account-specific file availability are unrun. |
| Reconnect | Healthy sessions reused; rotated token/invalid session rebuilt on a later request, with deadline/cancellation/generation guards. Network-loss/token-rotation QA is pending. |
| Fallback | Missing JNI, metadata/key/format/auth/throttle failure yields no source candidate; the configured chain continues. Read-admission failure rejects Spotify and preserves position during re-prepare. Device fallback remains unverified. |
| Sign-out | Grant deletion closes JNI at the edit; native watch/generation and Kotlin read epochs revoke queued/in-flight work and descriptors. Account replacement, engine change and service destruction also invalidate. Actual device cleanup is unverified. |
| Desktop / iOS | Desktop resolves through the shared JNI object where CI staged a host library (linux-x64, linux-arm64, windows-x64); playback is unverified. iOS keeps OAuth only and needs a C/Swift/static-library/AVFoundation range transport. |

The remaining acceptance work is CI compilation and physical playback validation,
not another replacement engine. Affected upstream #1649 accounts remain unsupported,
even with Premium.
