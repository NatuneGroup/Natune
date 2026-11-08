# Librespot audio implementation plan

Research completed 2026-11-08, within the 25-minute research budget. Base:
`integration/r3`, `1f93faac`. Scope: the audio engine and its source-chain adapter;
Spotify catalog/home/library/playlist mapping and Modules settings stay outside this change.

## Decision and current evidence

Use **Rust librespot core + metadata through a small JNI library**, compiled per
Android ABI on CI. Media3 remains the player, decoder, cache and output owner.
Do not embed librespot's player, a second output engine, or its discovery stack.
Desktop and iOS native packaging are follow-ups, documented below.

Evidence read online on 2026-11-08:

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
cached Cargo inputs under existing trust rules and select only each build's ABIs.
Expected native size is several MiB per ABI; **actual APK/library size and build
minutes must be measured in CI**, not asserted from source. Kotlin-only unit-test
jobs need no native library; APK assembly must require the selected ABI libraries.

Desktop would need JNI library staging for Linux/macOS/Windows plus a decrypting
transport into the existing playback port. This Rust choice intentionally does not
pretend the JVM branch now plays; retain source-unavailable fallback and document it.
iOS needs a C/Swift bridge, per-target static libraries and AVFoundation byte transport;
JNI is not an iOS solution. Existing OAuth on both hosts stays intact.

## Validation and outstanding evidence

Local execution is limited to `python3 scripts/check_repository.py --worktree` and
`python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`, plus source/diff inspection.
No local Gradle/JVM, lint, Kotlin/Rust build, emulator, Spotify login or playback probe.
CI must compile Rust for all shipped ABIs and Android/shared Kotlin tests, then
assemble release-shaped APKs and measure their closure. Device QA must cover the
registered OAuth app, an eligible Premium account and an affected #1649 account,
96/160/320 availability, seeks, cache replay, network loss/reconnect, token rotation,
logout/account replacement, source fallback, low-data mode and concurrent prefetch.
A green compiler or fixture result is not device playback evidence.
