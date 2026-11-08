# Librespot audio JNI

This library embeds only librespot-core and librespot-metadata. It returns a
playable Vorbis file's key, signed CDN URLs and reported track metadata; Android
decrypts below Media3's cache and Media3 owns decoding/output. No librespot player,
discovery, downloader or second audio output is linked, and no disk credential
cache is configured.

`upstream-revision` pins MIT-licensed librespot. `prepare.sh` fetches that exact
commit into an ignored recipe-owned directory and applies `embedding.patch`:

- Remove the CLI's process exit on Premium loss; the JNI adapter denies the session.
- Expose the existing upstream Keymaster constant for the OAuth StoredCredential
  profile documented in login5. Natune PKCE still requires its registered,
  build-time public OAuth client id; the upstream protocol profiles are separate.
  No OAuth secret or user credential is bundled.
- Supply the real Android API level from `Build.VERSION.SDK_INT` when sysinfo
  cannot report it; client-token/login5 must not receive an unknown Android version.
- Return HTTP 429 and Retry-After without an internal retry; Natune owns the cooldown.
- Stop on client-token authentication/throttle failures and login5 protocol
  timeout/throttle responses. The required challenge exchange remains bounded.

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
The composite CI action first restores an exact prepared-JNI cache. Its manifest
binds library checksums to the native recipe and Kotlin JNI contract; cached
libraries work without installing Rust again. Cache misses compile with Cargo
input/target caches. Only main/dev produce reusable caches; PRs only restore.
The mobile universal APK selects arm64-v8a and x86_64. TV universal also includes
armeabi-v7a and x86, matching AGP's combined device/flavor ABI filters. Per-ABI
shipping APKs retain their corresponding libraries.

Fresh builds check exported JNI symbols against Kotlin externals, ELF architecture
and 16 KiB load alignment. CI sets `requireLibrespotAudio=true` after successful
preparation, and checks the assembled APK contains the exact prepared bytes for
every selected ABI. Cargo strips the library once; AGP keeps those stripped bytes.
Debug, the ARM64 candidate, emulator QA and the shared Stable/Nightly Android
release workflow all use these checks. Debug also receives the registered public
OAuth client id from the repository variable (a blank variable still disables login).

If Rust/toolchain/native preparation fails without an exact cache, the action
deletes partial libraries, warns in the job summary and sets the requirement false.
The APK still builds with Librespot unavailable; the runtime load guard falls
through to the configured source chain. An APK check confirms JNI is absent in
that case. A green APK job alone therefore does not prove native compilation:
inspect the Librespot action's availability result. Partial ABI closures fail
packaging, and Kotlin compilation/unit tests do not require JNI.

All JNI errors use fixed categories. Protocol logging and panic text are disabled:
upstream trace logs can expose tokens. OAuth bearer and AP reusable credentials
stay in memory. `nativeClose` revokes the generation and shuts down the session;
timeouts/cancellation cannot publish a result from an old generation. Reconnect
happens on a later playback request, never in a background retry loop.
JNI checks the generation captured before queuing each operation, so a pending
connect cannot resurrect a session after logout.

The source is not an official Spotify SDK. In particular, the upstream account
restriction in [the implementation plan](../../docs/engineering/librespot-audio-plan.md)
still applies: audio-key denial falls through to the next configured source.
Desktop and iOS bindings/packaging are not implemented by this Android recipe.

Upstream's MIT notice is [LICENSE.librespot](LICENSE.librespot), also shipped in
Android assets. The Natune bridge is GPL-3.0; Outify was consulted as an integration
example and no Outify source is copied here.
