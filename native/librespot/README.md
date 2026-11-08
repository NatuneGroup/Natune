# Librespot audio JNI

This library embeds only librespot-core and librespot-metadata. It returns a
playable Vorbis file's key, signed CDN URLs and reported track metadata; Android
decrypts below Media3's cache and Media3 owns decoding/output. No librespot player,
discovery, downloader, disk credential cache or second audio output is linked.

`upstream-revision` pins MIT-licensed librespot. `prepare.sh` fetches that exact
commit into an ignored recipe-owned directory and applies `embedding.patch`:

- Remove the CLI's process exit on Premium loss; the JNI adapter denies the session.
- Expose the existing upstream Keymaster constant for the OAuth StoredCredential
  profile documented in login5. No OAuth client id or credential is bundled.
- Supply the real Android API level from `Build.VERSION.SDK_INT` when sysinfo
  cannot report it; client-token/login5 must not receive an unknown Android version.
- Return HTTP 429 and Retry-After without an internal retry; Natune owns the cooldown.

The checked-in Cargo.lock pins the transitive graph for Rust 1.97.1. Lock generation
uses registry metadata only. The constrained implementation host has not compiled
this crate. Native compilation belongs to CI:

```sh
bash native/librespot/build-android.sh universal
# Other selections: arm64, armeabi, x86, x86_64, all, or a shipping variant.
```

The recipe requires Linux, Rust 1.97.1 and NDK 30.0.16248370. It builds API-24
libraries with 16 KiB ELF alignment, writes them under
`app/build/generated/librespot/jniLibs/<abi>/`, and reports ELF segments and size.
The composite CI action restores Cargo caches read-only on PRs; only main/dev
produce reusable caches. The Gradle packaging gate requires every selected ABI.

All JNI errors use fixed categories. Protocol logging and panic text are disabled:
upstream trace logs can expose tokens. OAuth bearer and AP reusable credentials
stay in memory. `nativeClose` revokes the generation and shuts down the session;
timeouts/cancellation cannot publish a result from an old generation. Reconnect
happens on a later playback request, never in a background retry loop.

The source is not an official Spotify SDK. In particular, the upstream account
restriction in [the implementation plan](../../docs/engineering/librespot-audio-plan.md)
still applies: audio-key denial falls through to the next configured source.
Desktop and iOS bindings/packaging are not implemented by this Android recipe.

Upstream's MIT notice is [LICENSE.librespot](LICENSE.librespot), also shipped in
Android assets. The Natune bridge is GPL-3.0; Outify was consulted as an integration
example and no Outify source is copied here.
