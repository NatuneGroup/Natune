# iOS source status

The iOS app is one shared Compose tree inside a SwiftUI shell (see
[iOS shell](ios-swiftui-parity.md)). The `iOS compile` workflow type-checks Swift and
compiles Kotlin/Native for the simulator. Full native app linkage, simulator launch,
device audio and live authentication are still unverified (`unverified-on-linux-host`).
Kotlin metadata on Linux catches binding mistakes, but it
does not establish compilation against an actual Apple SDK or link Swift and Objective-C++.
The inactive foundation `shared/` module is not used.

The Gradle cinterop, the static framework export, the Xcode framework build phase, the
iOS quickjs-kt dependency and the native closure producer
(`scripts/build_ios_native_core.sh`) are present in source but have not run end to end on
an Apple host.

## Adapter inventory

Paths below are relative to the repository. Every row retains the unverified Apple boundary.

| Adapter | Implementation | Behavior and remaining limits |
| --- | --- | --- |
| Shared UI entry | `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/MainViewController.kt` | `ComposeUIViewController` renders the `root` it is given. `IosHost` passes `IosMountedRoot` (`shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/`), which mounts the bound `ArchiveTuneRoot(host = iosHostBindings(...))` once `IosSessionGraph` is open, and an explicit unavailable boundary when settings or the graph cannot open. The parameterless placeholder remains only as the default argument. No duplicated player screen, and no second renderer: commonMain `SafeAreaPadding` owns the window insets inside `ArchiveTuneTheme`. |
| Host lifetime | `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/IosHost.kt`; `iosApp/ArchiveTune/ArchiveTuneApp.swift`; `iosApp/ArchiveTune/ArchiveTuneSession.swift` | One application-owned `IosHost` per process, created by `ArchiveTuneSession` in `App.init` before the first activation. SwiftUI controller detach and recreation re-present the same single controller and close nothing. The session observes `didBecomeActive` once for foreground delivery and `willTerminate` for the one idempotent final `shutdown()`. Settings, vault, Japanese pack, challenges, player and credentials are therefore never opened twice, so the single-owner settings guard cannot fire on a view rebuild. Not verified on an Apple device or simulator; no Apple compilation is claimed. Root must still consume these same services through integrator-owned composition. |
| Playback | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosPlaybackPort.kt`; `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosStreamLease.kt`; `IosHttpRangeSource.kt` (same directory); `iosApp/Native/ATNativeAudio.{h,mm}` | Same portable FFmpeg core in manual-output mode, native `AVAudioSourceNode` stereo float callback to `AVAudioEngine`. Existing `EnginePlaybackMapper`/`PlaybackSnapshot` supply occurrence identity, completed seeks, buffering and discontinuities. No timer advances position. `PreparedAudio` enters only through `IosPlaybackPort.submitPrepared`: an anonymous header-free `Progressive` stream is opened by the engine from its URL, an anonymous header-bearing one is read through the host byte-source lease described next, and a personal `Progressive` stream (an account scope other than `public`) is admitted against its account before its first byte and read through a lease bound to that entry, and every request of that lease rereads the account before it leaves, as described under Design notes. `Native` and `TidalManifest` are admitted against their account and prepared to plaintext by `IosNativeAudioRoute` over the common `NativePlaybackRoute`, then played as `PlaybackLocator.PreparedPlaintext` through this port's own transport; every ciphertext request of that transport rereads its admitted entry and cookies before it leaves. Personal `Progressive`, `Native` and `TidalManifest` audio is never fetched as an anonymous URL, and it is refused until the account store is attached. A `DeezerEncrypted` stream (`PreparedAudio.DeezerEncrypted`, converted by `toPlaybackLocator()`) is read through the same host lease by `IosDeezerStreamSource`, which decrypts it with the common `DeezerChunkByteSource` keyed by `DeezerCrypto.deriveKey(trackId)`; the engine receives plaintext, and the ciphertext is never fetched as a URL or staged on disk. Header-bearing audio uses the host lease: `at_audio_load_stream` hands `ae_stream_io` callbacks to `ae_engine_load_stream`, and `IosStreamLease` serves them from `IosHttpRangeSource`, which issues ranged `NSURLSession` requests on one ephemeral session per hop. Redirects are followed under the lease policy and only to the primary origin; headers and cookies travel only on that origin; 416 is end of stream; unknown length and seekability are reported to the engine. Interrupt only cancels in-flight work; close runs once after the engine retracts the lease and releases the connection and the StableRef. No plaintext file or loopback broker is used. Teardown drains in-flight render callbacks and waits until the owning source node deallocates before freeing the decoder; an overrun of that bound leaks the decoder rather than freeing it live. Requires the M8 realtime callback symbol (`ae_engine_render_realtime`, M8 `bee7a838`, dev `5c35a8c`), the host lease ABI (`ae_engine_load_stream`, dev `51accca`) and the M7 header locator (`PlaybackLocator.Remote(url, headers)`, dev `4468c98`), all now on dev, plus the M7 typed Deezer locator and decryptor (`PlaybackLocator.DeezerEncrypted`, `PreparedAudio.DeezerEncrypted`, and the `DeezerChunkByteSource` withholding of partial encrypted chunks; `fafb969`, on dev as `074dc5b`) and its static native closure. |
| Audio session | `iosApp/Native/ATNativeAudio.mm` | Playback category, explicit activation, interruption freeze/conditional resume, pause on disconnected output, error on media-services reset requiring recreation. The playback category and activation go through one shared session policy that also serves recognition capture (`at_audio_session_begin_recording` and `at_audio_session_end_recording` in `ATNativeAudio.h`): an output holds a claim while it should be audible, every session change happens under one lock outside the render callback, a recording window keeps play-and-record for playback started inside it, and a claimed output the system stops is restarted on the main thread. Observer and restart callbacks capture a per-output lifetime gate rather than the output address, so a callback queued before destroy cannot act on a later output that reuses the address. Snapshot advancement is masked on stopped/stale output or zero decoded frames. Fixed 48 kHz stereo float mixer is **not bit-perfect**; physical DAC latency and AirPlay/Bluetooth presentation are unmeasured. |
| Recognition capture | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosRecognitionCapture.kt`; `iosApp/ArchiveTune/Info.plist` and `iosApp/ArchiveTune/en.lproj/InfoPlist.strings` (microphone prompt) | `IosRecognitionCapture` implements the committed M4 `RecognitionPermissionPort` and `RecognitionCapturePort`. Permission is read from `AVAudioSession.recordPermission` and, while undetermined, requested once through `requestRecordPermission` on the main thread (iOS 16 API; no `AVAudioApplication`). Each capture records exactly `RecognitionCaptureSpec` (16 kHz, 8,400 ms, 134,400 mono PCM16 samples) from a real `AVAudioEngine` input tap in the hardware format, averages channels and box-filter resamples to 16 kHz. Partial buffers fill the window across callbacks; nothing is padded or synthesized, and no sample is written to disk or logged. One window runs at a time; close is idempotent; each window is deadline-bounded and fails closed on an all-zero window, an input configuration change, an interruption, a media-services reset or missing input. The window claims the shared session through the native policy in `ATNativeAudio.mm` (speaker output, Bluetooth headset input and A2DP output allowed). Playback started inside the window keeps play-and-record. The release restores the category for the current playback intent, or the one from before the window, and never deactivates the session. Authored, not compiled, not run on any Apple host; no permission, hardware or audio success is claimed. Integrator obligations are listed under Host/build integration. |
| Now Playing | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosNowPlaying.kt`; `IosNowPlayingArtwork.kt` (same directory) | Publishes audio media type, occurrence-keyed title/artist/album/duration and artwork from the queue's `ContentItem`. A common change projection ignores position-only polls; transport changes reanchor elapsed time/rate, metadata corrections and late artwork reanchor once to avoid replaying an old elapsed value. Artwork is fetched once per current media/source key over a cookie-free ephemeral session (15 s, 2 MiB maximum), downsampled by ImageIO to a 512-pixel edge and held in a four-entry memory cache. Superseded loads are cancelled and generation-checked. Remote play/pause/toggle/stop/seek/next/previous retain queue ownership and stale-occurrence guards; seekability changes update the seek command alone. Can-next/previous command availability is still not integrated. Apple compilation and Lock Screen/Control Center behavior remain unverified. |
| Credentials | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosKeychainCredentialStore.kt` | Generic-password Security Keychain; length-prefixed provider/scope/name account ID; device-only after-first-unlock accessibility; no synchronization, shared access group, plaintext or preferences fallback. Not-found differs from OS failure. Catalog, streaming and Pool are separate items. |
| Settings | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosSettingsStore.kt` and `IosPreferencePayload.kt`; `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/settings/IosVaultSettings.kt` | Ordinary M6 `SettingsStore` persisted as device-only Keychain items in two bounded slots: a head written last publishes a SHA-256-verified payload, and a guard refuses credential-bearing names before any write. M6 `VaultSettingsStore` is composed over the real `IosKeychainCredentialStore` with opaque UUID references and no plaintext or memory fallback. `IosHost.settingsState` reports Opening, Ready or Unavailable. Typed consumers, sessions and root binding are not bound here. Depends on committed M6 `SettingsStore`/`VaultSettingsStore` and M7 `ContentDigest`. |
| External OAuth | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosBrowserAuth.kt` | `ASWebAuthenticationSession`, strongly retained presentation anchor, ephemeral preference, exact redirect and single-state checks, bounded timeout and cancellation. Ephemeral preference is not proof of provider/OS cookie isolation; live account verification remains required. No tokens are logged/persisted by the browser. |
| Embedded OAuth | same file | Fresh nonpersistent `WKWebsiteDataStore` per provider/scope attempt; HTTPS-only navigation, exact callback validation, retained delegates, cancellation and cleanup. Cookie-only provider login/private-context verification is **not implemented** by M7's callback-URI contract. No claim of YouTube/Spotify embedded-cookie-login parity. |
| Files | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosPlatformFiles.kt` | UIKit document picker, opaque Base64 bookmark handles, scoped/coordinated I/O, bounded reads, atomic NSData replacement, coordinated delete, explicit stale/revoked bookmark failure. Uses iOS bookmark flags, not the macOS-only security-scope creation flag. File-provider atomic-write behavior needs Apple tests. |
| Custom artwork files | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosCustomArtworkFiles.kt` | `CustomArtworkFiles` over the app-private Application Support folders `custom_artwork` and `custom_canvas`, the same folder names the Android and desktop hosts use. Lists regular files only, refuses a symbolic-link folder, accepts only plain file names, writes through POSIX `fopen`/`fwrite`/`fclose`, replaces a version with a same-folder POSIX `rename`, and checks decodability through UIKit's `UIImage` (header-level, no cache file). `uriOf` returns a `file:` URL. Authored but UNRUN: not compiled and not run on an Apple host. `pickIosArtworkFile` supplies the source through the document picker and `BoundCustomArtworkEditDialog` mounts over it. |
| Japanese tokenizer / pack | common `JapaneseLanguagePackManager`, `KuromojiIpadicArchive`, accepted tokenizer; iosMain `IosJapanesePackIo` / `IosJapanesePackDownload` | Prepared common lifecycle plus bounded iOS ephemeral URLSession writer, app-private storage, raw zlib and atomic rename. Exact archive digest precedes ZIP parsing; all eight data pins and actual tokenizer probe precede readiness. Existing UTF-16/unknown/timed-word behavior is unchanged. `IosHost` creates one pack manager on the real vault snapshot with `iosNetworkAvailable`, forwards real activations through `foregrounded()`, closes it with the host, and exposes `japaneseLanguagePack` for settings rows. **dictionary host/settings/root composition remains required** for integrator root binding; no Apple compilation/runtime assurance is claimed. |
| Challenge | `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosChallengeEvaluator.kt`; `IosHost.challenges` | Platform `ChallengeEvaluator` over a fresh `quickjs-kt` 1.0.10 runtime per evaluation, mirroring the desktop bounds: engine-enforced time, memory, stack and output limits, no host bindings, Atomics or SharedArrayBuffer, and at most two concurrent runtimes. Failures are sanitized. The iosMain dependency on `libs.quickjs.kt` is declared in `shared-data/build.gradle.kts`; iOS arm64 and simulator availability of the artifact is unverified here. |
| Capability diagnostics | `shared-domain/src/iosMain/kotlin/moe/rukamori/archivetune/shared/domain/platform/IosCapabilities.kt` | Machine-readable adapter/dependency IDs, explicitly unverified; CarPlay, Android raw USB, physical bit-perfect proof and protected-media PCM are unavailable. IDs are not user-visible translated labels. |

## System media and routing

Playback selects `AVAudioSessionRouteSharingPolicyLongFormAudio` with the existing playback
category, default mode and zero category options. Recognition keeps play-and-record with the
default sharing policy, because long-form audio sharing requires the playback category. The
first recording window snapshots the prior sharing policy as well as category/mode/options;
the last release restores that policy, or long-form playback when a playback claim remains.
Route sharing, interruptions and other apps' audio interaction still require device verification.

`createIosAudioRoutePicker()` returns an `AVRoutePickerView` as a `UIView` through the existing
`ArchiveTuneShared` framework. The iOS player supplies it through the optional `outputButton`
slot; the existing Apple Music player's device button hosts it with `UIKitView`. Native taps and
VoiceOver traverse the OS control; video devices are not prioritized. AVKit is linked explicitly.
No `AVPlayer` or second audio owner is created. Other hosts keep their existing output action.
Routing AVAudioEngine through this picker still requires an AirPlay receiver and a physical device.

Recognition opts into AirPlay alongside the existing speaker/Bluetooth/A2DP choices while its
play-and-record window is open. Only `OldDeviceUnavailable` pauses output (including headphone
unplug); category, override, new-device and route-configuration changes do not request a pause.
An actual AirPlay receiver removal can still pause, and receiver-switch notification sequences
need device verification.

`at_audio_output_latency` reads the larger of `AVAudioSession.outputLatency` and the output node's
`presentationLatency`, not their sum, since the estimates overlap. The iOS adapter publishes the
nonnegative estimate as optional `PlaybackSnapshot.outputLatencyMs`. Invalid estimates stay unknown.
The lyric clock subtracts this wall-time delay at the playback speed in addition to the existing
user offset/lead/tuning; row timestamps, tap-to-seek, queue positions and `PlaybackClock` are unchanged.
This is OS-reported latency, not a physical DAC measurement or proof that AirPlay network buffering
is fully included. Pure projection/cache-key, latency conversion/clock and output-slot regressions
are authored but unrun; Apple compilation and device sync remain unverified.

### Canonical audio graph and crash containment

The `-10868` (`kAudioUnitErr_FormatNotSupported`) create failure came from forcing
interleaved 48 kHz stereo through `AVAudioUnitTimePitch`/EQ before session activation.
`ATNativeAudio.mm` now activates the shared session first, refuses zero/nonfinite rates
and invalid channel layouts, and uses standard Float32 **non-interleaved** audio.
The portable decoder/manual blend ABI remains 48 kHz interleaved stereo; fixed-size native
scratch deinterleaves it at the source, and a source mixer converts to mono/stereo at the
current hardware rate before pitch/EQ. Asset formats are never imposed on the output node.

Every playback graph mutation, start and teardown runs behind an Objective-C
`@try`/`@catch` boundary, including the blend pump through `ATAudioGraph.{h,mm}`.
A refused tone graph gets one plain canonical graph retry without pitch/EQ. Stored controls
survive graph replacement, but optional tone is bypassed while using that fallback.
No AVPlayer playback adapter exists in this port; if the plain graph is also refused,
`IosPlaybackPort` reports occurrence/attempt-bound `OUTPUT_FAILED` to the existing queue
error/skip policy, without bypassing account admission, source order or the skip-on-error setting.

Route/configuration changes invalidate the old graph and rebuild against the new active
route. Paused/interrupted outputs defer rebuilding until resume; headphone removal still
pauses. Queued notifications are lifetime/generation-bound. Native rebuilds keep the decoder
and leases, and blends retain only unplayed PCM. Callback/node retirement is drained before
reusing scratch or freeing the decoder; a drain overrun retains memory rather than risking UAF.

`ios_compile.yml` now syntax-checks the actual Objective-C++ bridges against the simulator
SDK and runs native format/conversion and deterministic NSException-containment regressions,
plus the iOS source contracts. Its default Gradle step also compiles the shared-data
`iosTest` sources with `:shared-data:compileTestKotlinIosSimulatorArm64`; test-only changes
under `shared-data/src/iosTest/**` trigger the workflow. That target compiles test sources
only: it does not link a test binary, execute tests, build the native closure, run the
simulator app or create an IPA. Common tests cover the bounded constructor fallback. These
checks do not prove physical audio or route recovery: verify mono/stereo, 44.1/48 kHz,
Bluetooth/AirPlay, unplug, interruption, recognition-window changes and the original crash
on the exact installed candidate. No device validation is claimed here.

The default hosted macOS job also device-links the real `archivetune_device.c` translation unit
against the pinned miniaudio header and the CoreAudio/AudioToolbox/CoreFoundation frameworks, with
a negative `-DMA_NO_COREAUDIO` compile that must fail on the guard diagnostic. That is a host
compile/link check only: it opens no audio device, builds no FFmpeg/TDLib closure, packages
nothing and does not prove heard output or desktop runtime behavior.

The same workflow now also carries an opt-in app-target link, off by default. Its
`ios_simulator_app` (`workflow_dispatch`) input builds the simulator closure with
`scripts/build_ios_native_core.sh iphonesimulator` and then runs `xcodebuild` on
`iosApp/ArchiveTune.xcodeproj` (scheme `ArchiveTune`, Debug, `iphonesimulator`, code signing
off). That is the only path that compiles and links both Swift sources and all three ObjC++
bridges into an `ArchiveTune.app`: it requires five object files, a defined text entry point
per bridge and an `ArchiveTuneShared` Kotlin symbol in the executable. It is not on the
pull-request trigger because the closure build alone measured 3 h 09 m on the device leg (run
41735716057, job 126264425424, GitHub-hosted `macos-26`) and an `actions/cache` entry written by one pull request is not
readable from another, so a per-pull-request gate would need a closure cache produced on `dev`
- a larger change than this workflow edit. The pull-request trigger therefore still stops at
the framework link and the `swiftc -typecheck`, and the opt-in link has not run yet: the
app-target link and the closure linkage through `Native.xcconfig` stay **UNVERIFIED**.
Both paths now run on `blacksmith-12vcpu-macos-26`; the 45-minute default and 330-minute opt-in
ceilings bound a run at 1,800 and 13,200 equivalent minutes respectively at the provider's 40x
macOS multiplier. Xcode 26.4 selection and actual duration on that image remain unverified.
The opt-in closure may restore the validated pinned-download cache. A per-platform
dependency-prefix cache is deferred: the current evidence does not prove the Mach-O platform
(device versus simulator) and every static-archive member, so the dependency build and portable
core always run from source and the closure is always reassembled and verified. PR paths restore
caches only; explicit saves run for trusted `push`/`workflow_dispatch` on this repository, and the
separate Kotlin/Native `~/.konan` entry follows the same restore-only PR policy. If a release
dispatch selects an older source commit without the cache helper, the closure runs uncached and
the workflow emits one notice; it does not compute a key, restore or save.

## Host/build integration

1. Present in source: `shared-ui/build.gradle.kts` declares the static `ArchiveTuneShared`
   framework on both iOS targets and exports `:shared-domain` and `:shared-data`. The Xcode
   target runs `./gradlew :shared-ui:embedAndSignAppleFrameworkForXcode` from a build phase
   and links `-framework ArchiveTuneShared` through `Native.xcconfig`. Swift imports that
   framework, never `Shared`. Unverified on an Apple host.
2. Present in source: `shared-data/build.gradle.kts` registers the `archivetuneAudio`
   cinterop (`src/nativeInterop/cinterop/archivetuneAudio.def`) on each iOS main compilation,
   with the `iosApp/Native` and `desktopApp/native/include` include directories. No common
   ABI/queue DTO is replaced. Unverified on an Apple host.
3. The M8 core (`bee7a838`) declares `ae_engine_render_realtime`; the existing
   `ae_engine_render` may wait on prebuffer and is **not safe in the output callback**.
   The bridge uses only the non-waiting entrypoint, never a local simulation or fallback.
4. Producer in source, never executed: `scripts/build_ios_native_core.sh [iphoneos|iphonesimulator|all]`
   builds the static arm64 closure into `shared-data/build/ios-native/<PLATFORM>/lib`, or into
   `ARCHIVETUNE_NATIVE_ROOT` for one platform. The closure is the portable core compiled from
   `desktopApp/native/src` (JNI and desktop system code excluded), FFmpeg 8.1.3 (LGPL v3 set, no
   programs), OpenSSL 3.5.9, libxml2 2.15.4 and zlib 1.3.2. The pins match
   `desktopApp/native/scripts/build-native-deps.sh`. `closure.txt` beside `lib/` records the pins,
   toolchain and SHA-256 of every archive. Prerequisites: an Apple Silicon Mac with Xcode 26.4
   selected (`ARCHIVETUNE_ALLOW_OTHER_XCODE=1` builds another release and marks it UNVERIFIED).
   The iOS release job explicitly selects Xcode 26.4 with `maxim-lobanov/setup-xcode` and passes the
   same expected version to the producer; runner availability is not established by this source pin.
   The producer also requires the iphoneos and iphonesimulator SDKs, gpg, pkg-config, GNU make and a space-free repository
   path. The script refuses every host except macOS. No archive has been built, so the closure is
   **UNVERIFIED**. `Native.xcconfig` links `-lavfilter` and the other archives in the order the
   script lists them; iconv and libc++ come from the SDK.
5. [Done in the shell increment] `IosHost` opens `IosSessionGraph` over the Ready vault and the one account store,
   binds `IosHost.player` and the playback port to the portable queue owner and the shared library, history and
   lyrics owners, installs the playback retry and calls `installSystemControls` with the queue's metadata and
   next/previous. No demonstration queue exists.
6. `IosHost` creates ONE `iosJapaneseLanguagePackManager(scope, Dispatchers.Default,
   vault.snapshots, { edit -> vault.edit(edit); Unit }, { iosNetworkAvailable() })` only after
   the real M6 vault adapter opens; null settings are not defaults. Real activations reach
   `onForeground` through `foregrounded()`, not startup or background watchers. Integrator
   root/settings rows must bind `japaneseLanguagePack` state and its suspend install/cancel/
   remove actions, observe readiness/off/removal to invalidate renderer work and use
   `tokenizerOrNull()`. The host closes the manager before the settings owner. It uses the
   existing Japanese/off/attempted/removed keys and atomic editor, not another registry.
   Common lifecycle preserves persisted eligible primary-region JP consideration,
   offline consumption, explicit off/removal and manual behavior. The baseline explicit
   manual-install quirk (enable only if nullable choice is unchanged, including false)
   is characterized rather than silently changed. Automatic completion never overrides
   off/removal. No downloaded classes are loaded.
   Resource/credits owner must retain adjacent Apache-2.0 license, contributors and full
   IPADIC NAIST/ICOT notice in packaged credits. No new Gradle dependency is required.
   Shared Room/database and settings iOS factories depend on
   the corresponding committed M7/M6 persistence implementation; this lane does not
   invent a second schema or persistence implementation.
7. The host byte-source ABI (`AE_STREAM_IO_VERSION`, `ae_stream_io`, `ae_engine_load_stream`;
   dev `51accca`) and the M7 header-capable locator (`PlaybackLocator.Remote(url, headers)`,
   `DirectStream.headers`, `DirectStream.toPlaybackLocator()`; dev `4468c98`) must be integrated
   before `IosStreamLease.kt`, the header branch of `IosPlaybackPort` and `at_audio_load_stream`
   compile. Both are on dev (`51accca`, `4468c98`), so the header-bearing path is integrated on dev;
   no Apple compile has been run on this Linux host.
8. The M7 Deezer locator and decryptor (`PlaybackLocator.DeezerEncrypted`, `PreparedAudio.DeezerEncrypted`, and
   `PreparedAudio.DeezerEncrypted.toPlaybackLocator()`; M7 `fafb969`, which also makes `DeezerChunkByteSource` withhold
   partial encrypted chunks) must be integrated before the Deezer branch of `IosPlaybackPort`, `IosDeezerStreamSource.kt`
   and `IosStreamLease.loadDeezer` compile. They are on dev (M7 `fafb969` as `074dc5b`), and the Deezer lease was
   adopted on dev as `7e36ac5` (M9 `da94553`); no Apple compile has been run on this Linux host.
9. The M7 common native route and its account/plaintext surfaces (`PreparedPlaintextHandle`,
   `PlaybackLocator.PreparedPlaintext`, `NativePlaybackRoute`, `nativePlaintext()`,
   `ByteSourceAccount.accountScope`, `AccountAdmission.toByteSourceAccount()` and the
   `TidalDashPreparation.prepare(prepared, opener, admission, nowEpochMs)` signature) are committed on the M7 line as
   `e8410eb`, `a44042f` and `eef82ab`, and are on dev as `3f62ff8`, `3ba7ce8` and `54b692f`. The adopted
   `IosNativeAudioRoute` replaces the dev caller that passed a fixed public scope to `TidalDashPreparation`. The root
   constructs one real account store from the Ready vault and calls `IosHost.attachAccounts(store, store)` once.
   Until then personal progressive, native and TIDAL manifest audio is refused and nothing is read. The personal
   progressive path also reads `DirectStream.accountScope`, `AudioSourceType.accountProviderId()`,
   `ScopedCookieSource.cookiesForLease` and `DirectStream.toPlaybackLocator()` from the same M7 sources (dev
   `f20d8ad` at the first observation, and `9f17298` at a later one), so it must be integrated with them. Personal progressive audio must reach playback only
   through `IosHost.loadPrepared`. A `PlaybackLocator.Remote` built from a personal `DirectStream` and sent through
   `IosHost.player` carries no account binding and would play anonymously, so the root must never convert personal
   progressive audio to a remote locator. An account-bound locator in the common domain is an owner decision.

Recognition capture (`IosRecognitionCapture`) is wired by the integrator, not by this lane. Its obligations are:

- Construct one `IosRecognitionCapture()` for the process-owned `IosHost` and pass the same instance as both the
  `RecognitionCapturePort` and the `RecognitionPermissionPort` of the M4 `MusicRecognitionOwner`. Its single-flight
  guard only holds for one instance, so a per-request instance would bypass it.
- Call `IosRecognitionCapture.close()` exactly once from the same shutdown path that closes `IosHost`. It fails an open
  window and makes later calls refuse. The M4 owner's own `close()` cancels its request but does not close the adapter.
- [Superseded in the audio-session coordination increment: while a window is open the session is in play-and-record.
  `iosApp/Native/ATNativeAudio.mm` reapplies `AVAudioSessionCategoryPlayback` on every `start_output`, so a playback start
  during a window changes the input route and fails that window as a configuration change, which is fail-closed. The root
  must either keep playback from starting during a window (a product decision) or accept that honest failure. The adapter
  never deactivates the session, and native deactivation in `at_audio_destroy` is unchanged.]
- Audio-session coordination (current): playback is neither blocked nor refused by a window. Playback and the window share
  one native session policy in `iosApp/Native/ATNativeAudio.mm`, reached through `at_audio_session_begin_recording` and
  `at_audio_session_end_recording` in `iosApp/Native/ATNativeAudio.h`. The root adds no playback gating for recognition and
  must not add a second session owner. A genuine route or configuration failure still fails the window honestly. Native
  deactivation in `at_audio_destroy` is skipped while a window or other playback holds the session.
- The microphone prompt is `NSMicrophoneUsageDescription` in `iosApp/ArchiveTune/Info.plist` and in
  `iosApp/ArchiveTune/en.lproj/InfoPlist.strings`. The Xcode project must keep both files.
- Capture is foreground-oriented. A background window is refused or fails when the session cannot be activated; no
  background recognition is claimed. Android and GMS capture are not part of this adapter.

## Entitlements and platform differences

- `Info.plist` declares background `audio`; real background behavior still requires
  device testing. No microphone, broad ATS exception or arbitrary file permission
  is requested. [Superseded in the recognition capture increment: `NSMicrophoneUsageDescription` now declares the
  microphone prompt for recognition; no ATS exception or file permission was added.] Document access is granted
  through Files, not a sandbox escape.
- Default app-private Keychain requires no extra shared group. Cross-app keychain
  groups require legitimate provisioning/entitlements and are **not configured**.
  Device-only credentials are intentionally not portable backup material.
- CarPlay requires Apple's approved audio entitlement and a separate scene/UI
  implementation; neither is present. Android Auto/widgets/raw USB integrations are
  not represented as iOS no-ops. Protected content needs legitimate platform/provider
  capability, not a claim that FFmpeg supplies DRM.
- The registered callback schemes are `archivetune`, `natune`, the Discord app's
  `discord-<id>` redirect and `vnd.youtube`, whose links open a YouTube watch
  through the shared classifier. Universal links need an `associated domains`
  entitlement and a hosted apple-app-site-association file on each share-link
  domain (YouTube, Spotify, Telegram and the Listen Together invite hosts are all
  third-party domains), so no such entitlement is declared: https intake stays
  scheme-only until the project owns a domain it may claim. A share extension
  would need its own app-extension target and entitlements; none is declared, so
  shared-text intake stays out of scope.
- The project has no development team, signing keys or provisioning profile. Local
  unsigned simulator checks do not prove signing/distribution rights or App Store
  compatibility. Existing GPL/third-party obligations still apply.

## Verification boundary

- `iosApp/tests/test_source_contracts.py` checks host/plist/resource/native source
  relationships. These are source-contract tests, **not Apple API tests**.
- `shared-domain/src/iosTest/.../IosCapabilitiesTest.kt` and
  `shared-data/src/iosTest/.../Ios{AuthCallback,CredentialIsolation,KeychainRoundTrip,PlatformFiles}Test.kt`
  cover redirect/state rejection, account separation, real Keychain round trips and
  file-grant/read-bound rejection. **Authored but UNRUN** on Linux.
- Common Kotlin (HLS parsing, Japanese tokenizer, queue and lyric owners) is covered by
  JVM tests in `Unit tests`; those do not execute iosMain.
- Still needed on macOS: run `scripts/build_ios_native_core.sh all` with Xcode 26.4 and
  check `closure.txt` and every archive; build the framework and cinterop, build the Xcode
  project, run iosSimulatorArm64 tests, exercise Files providers and cancellation, live
  OAuth isolation, lock-screen controls, pause/buffer/seek/route/interruption/EOF
  snapshots, then physical-device background audio and output timing. The producer's
  `nm` export check is necessary but does not prove the closure links into the app.

## Design notes

- **Personal audio admission.** A `DirectStream`, `Native` or `TidalManifest` stream whose
  account scope is not `public` is never read anonymously. `IosNativeAudioRoute` admits the
  account before the first byte, and every request of its lease (probe, range, seek,
  redirect and reopen) rereads the admitted entry first. A changed or unreadable account
  refuses with `ACCOUNT_NO_LONGER_ADMITTED` or `ACCOUNT_UNREADABLE` before anything is
  sent. Cookies go only to the request origin; cross-origin hops stay credential-free.
  Such streams are refused until `IosHost.attachAccounts` supplies the store, and they
  enter only through `IosHost.loadPrepared`.
- **Header-bearing and Deezer streams** are read through the host lease
  (`IosStreamLease`, `IosHttpRangeSource`). A read waits at most 500 ms before answering
  RETRY. Deezer ciphertext is decrypted by the common `DeezerChunkByteSource` and is never
  staged on disk.
- **HLS.** Clear and `METHOD=AES-128` identity-key media playlists are expanded by the
  host (`shared-data` `stream/hls/`). Variant playlists, `SAMPLE-AES`, non-identity key
  formats (FairPlay included), live playlists and non-HTTP(S) resources are refused. The
  lease's permitted origins cover the playlist and every named resource, bounded at 256.
- **Audio session.** One native policy in `ATNativeAudio.mm` owns category and activation
  for playback and recognition capture, under one lock outside the render callback.
  Queued observer and restart callbacks capture a per-output gate, so a callback queued
  before destroy cannot act on a later output that reuses the address.
- **Telegram.** `scripts/build_ios_native_core.sh` builds TDLib from the tdlight pin named
  in `ATNativeTelegram.h` into `libarchivetune_telegram.a`; the iOS bot chat
  (`telegram/bot/<id>`) and Telegram sign-in page remain unbound.
- **Known gaps.** No associated-domains entitlement, so https links cannot open the app;
  no share extension; DNS-over-HTTPS is refused because NSURLSession has no DNS hook;
  Apple FairPlay-class DRM is refused.

## Feature coverage

The iOS ordered audio chain now registers Spotify Echo. The retained desktop device/pair,
metadata/storage and PlayPlay key pipeline and Android protobuf/key codecs are adapted into
`shared-data/commonMain/provider/spotify`; existing desktop and Android call sites are untouched.
The dedicated streaming account is verified and admitted through the vault; the catalog is read
only for collision refusal. Streaming stays opt-in and excluded from downloads. Xcode must supply
`ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID`, `ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID` (optional) and
`ARCHIVETUNE_SPOTIFY_AUDIO_IV`. No credentials are bundled. Librespot and web-player methods
remain unavailable and fall through without changing the selected engine or method. Live Spotify
success and device playback have not been verified.

Android archive restore now accepts `settings.xml` and legacy `settings.preferences_pb`, and
validates schema 36–39 `song.db` archives with journal siblings before converting the library.
The Android table mapper is shared with the retained desktop Path entry point. Accounts/settings
retain the existing export policy, font payloads are bounded but not installed, and local audio
paths from an Android backup do not acquire iOS file access. The existing journaled iOS restore
stages the converted database and applies chosen sections on restart. Android-format export is
still absent. Real archives, Apple compilation and restore/device recovery remain unverified.

Podcast browse/search and show episode pages now use the existing InnerTubeX transport and
shared Compose podcast screens. Local follows persist in vault-backed settings and are labelled
as local subscriptions. Episodes keep their own content kind and use the normal finite queue and
progress store. Header parsing shares the provider header parser; date/description extraction and
remote subscription mutation are still incomplete. No live podcast/device evidence is claimed.

The iOS download queue now admits episodes and prepares Deezer, TIDAL DASH, QQ/Amazon native
recipes and supported clear/AES-128 HLS through the existing playback plaintext route. The
optional `PreparedDownloadTransfer` contract is additive; the desktop progressive transfer
keeps its behavior. iOS writes validated plaintext to task-owned staging, resumes by plaintext
byte position, checks length/expiry, closes every preparation, and commits through the existing
stored-song transaction. Protected Apple FairPlay remains refused and falls through. Telegram
still belongs to TDLib's cache; streaming Spotify stays excluded. Native transfer, device offline
playback and compilation remain unverified. No background download entitlement is claimed.

The iOS graph now starts the existing `LyricsSurfaceRuntime` and both AI lyric coordinators,
including automatic and manual romanization/translation. Manual edit and undo, guarded AI
translation persistence, per-song ±30 s timing writes and actual PNG share sheets are mounted
on the common song menu/player surfaces. A source-bound atomic iOS lyric store caches provider
text (256 bounded entries) and preserves edits across restarts. `HostLyricsStorage` and guarded
raw-document replacement are additive commonMain hooks; other hosts keep their default behavior.
Direct/account-bound and module lyric providers remain incomplete. AI service configuration,
Apple compilation, share sheets and offline/device behavior remain unverified.

Spotify catalog search (tracks/albums/artists/playlists) and QQ track search are mounted in the
shared search page. Spotify GraphQL Home and QQ Toplist GetAll/GetDetail charts use the existing
shared Spotify/QQ Home variants and honour saved home-source choices. Spotify uses the catalog
token only and reuses the existing track-to-YouTube queue mapping; QQ shelves use the ordinary
source chain. The retained iOS REST client was extracted without changing desktop sources.
Other provider searches and live feed/provider/account validation remain incomplete/unverified.
The small additive QQ home RPC method is in shared-data/commonMain.

AI top mixes now have a user-triggered generation action in the shared library header. The
existing common plan parser/binder validates model output against actual listening-history songs;
success creates durable local playlists and honours the saved hide preference and ten-minute
refresh bound. It does not fabricate ids, generate on page visit or silently call AI. New-track
recommendations are not resolved in this increment. AI/provider and device behavior is unverified.

Scheduled folder backup and folder synchronization rows now use persisted, security-scoped Files
bookmarks and coordinated atomic child writes. An installed Google Drive Files provider can be
picked as a folder; this is Files-provider synchronization, not a new Drive HTTP folder client.
The driver follows the selected daily/weekly/monthly/custom day at 02:00 local while the process
runs, records successful writes, and cannot run while iOS suspends/terminates the app. The existing
shared playlist CSV/M3U import and CSV export owners are also mounted in Backup settings.
No BGTaskScheduler registration or background execution guarantee is claimed. File-provider
access after restart, scheduling and Apple compilation remain unverified.

Appearance now mounts an independent seed-color palette/theme editor and native alternate-icon
selection using the retained Android launcher artwork in an iOS app-icon asset. The full Android
IconPack remains absent. The shared Liquid Glass settings route is mounted and keeps refraction
capability false, so unsupported optical controls remain disabled. Discord's shared experimental
translator/button editor is mounted over the existing presence settings. Updates now use the real
bundle version/build and release-stamped channel, share the feed/commit-history checker with
Changelog, and offer an explicit release-page handoff while retaining the stored channel choice.
Notification controls are omitted because iOS has no scheduler or notification-center delivery;
iOS cannot install an arbitrary package in process. Asset-catalog alternate-icon merging, Apple
compilation and all new controls remain unverified.

Readable `.arch` API-v1 audio packages now have an iOS manager and bounded QuickJS execution,
using the retained Android `module-host.js` API (fetch, URL, timers, base64 and typed settings).
The installer verifies format/API/version, script checksum, bounds and safe paths; package updates
rename atomically. Configuration stays in Keychain and modules remain opt-in in the ordinary
playback/download source order. Progressive and ordinary HLS URL answers are consumed by the
existing host. Inline DASH, signed/native extension APIs, raw legacy text modules, module lyric
provider mounting and catalog update browsing remain incomplete. Native extension packages are
refused, not treated as ordinary modules. Package/runtime/device/Apple compilation is unverified.
The source settings now advertise the Echo transport while preserving engine/account readiness.

The managed iOS song cache now prefetches complete files for the active song through the existing
portable preparation/transfer and a distinct CACHE stored-song row. Byte-count checks gate offline
reuse; source pins/rejections can bypass a cached source. Cache limits stop oversized transfers and
evict oldest entries, and song-menu removal/storage clearing remove real files and rows. Prefetch
is an additional request rather than reuse of the playback response; streaming Spotify and TDLib
tracks are excluded. Lyrics cache usage/clearing is wired and clearing preserves durable edits.
The stored-song writer now accepts episodes as well as songs, completing episode file commits.
Device/offline behavior and new-source Apple compilation remain unverified.

### API-v1 audio modules

The iOS module page installs bounded readable `.arch` audio packages (4 MiB compressed,
5 MiB stored, 32 installed modules), verifies script/icon checksums and API/host features,
and validates settings before writing them exclusively to the personal Keychain scope.
Providers receive API-v1 `{value: ...}` settings and run on a confined QuickJS worker
with memory, stack, timer, request/body and execution limits. A separate HTTP client
prevents the app's account cookies or default headers from entering module requests.
Modules resolve in the existing configured audio-source order. Per-module errors stand
down for the next candidate/source and appear as sanitized status in the manager.
Signed/authentication callbacks, permission-bearing extensions, native Android binaries,
lyrics/metadata modules, and inline DASH/DRM streams are explicitly unsupported in UI.
Direct progressive and ordinary HLS URLs continue through the existing iOS preparation.
Local Python checks are structural evidence; CI compilation and device playback remain
separate gates.

### WidgetKit controls and system surfaces

The app now embeds a WidgetKit extension for small/medium home-screen playback controls.
Links foreground the app and issue one bounded command after its authoritative session
and persisted queue are ready; the extension does not own a player or account credentials.
The simulator CI gate type-checks the extension separately. Full extension/app linkage,
provisioning and home-screen interaction still require the existing Apple build/device gate.
Live artwork, listening insights and lock-screen/Dynamic Island lyrics need ActivityKit
integration and are not implemented.
Android custom always-on-display controls now explain the iOS lock-screen limitation.

The song menu now mounts the shared provider-canvas source picker. A selected bounded download
is saved through the existing versioned custom-canvas repository and its validation/commit path.
Local imported audio now shares its real app-container file through the system activity sheet.
Provider source/network preferences still gate canvas requests. No live canvas/share-sheet or
new-source Apple compilation evidence is claimed.

Direct Spotify color-lyrics is now an optional provider of the shared lyric runtime, using only the
catalog account and conservative track matching. Its HTTP/metadata decoder reuses the portable
Spotify clients, and missing lyrics/authentication remains a failed provider result. The common
host accepts additive provider factories/names without changing other hosts. Remaining direct
Musixmatch/QQ/Netease and module lyric providers are still incomplete; live Spotify access and
new-source compilation are unverified.

### AVAudioEngine DSP coverage

Ordinary output and both blend decks now apply the selected shared clarity preset's six
filter sections, a 5 Hz high-pass approximation when DC removal is enabled, independent
pitch, user EQ/preamp and any supplied normalization gain. Twenty-four native EQ slots
retain all sixteen user bands plus the additional contour/DC filters. EQ-off does not
silently disable the separately selected contour. No Kotlin work runs in the realtime
callback. This is partial clarity support: stereo width, bass-side processing, exciter,
shared limiter/dither and exact shared DC-filter transfer are not implemented by these
Audio Units. Track loudness measurement is not supplied, so its gain stays unity.
The iOS playback page explains these limits and disables settings with no native reader:
silence skip, normalization measurement, Android offload/PIP/wakelock/task handling,
Bluetooth auto-start and source rejection based on unmeasured acoustic evidence. Saved
preferences are retained. Optional Audio Units still have the existing route-refusal
bypass; device sound, full-app linkage and parity with Android DSP remain unverified.

CarPlay now has a CPTemplateApplicationScene delegate and registered scene configuration,
with bounded library/queue lists and selection dispatched to the sole shared Kotlin player/queue.
The project includes an audio-app entitlement declaration; Apple approval, a matching provisioning
profile, full app linking and CarPlay device execution remain required/unverified. The shared
host exports only credential-free snapshots and bounded queue commands. CPTemplate and WidgetKit
are documented system-only renderer exceptions in AGENTS.md; ordinary app content stays Compose.

The existing WidgetKit controls now read real title/artist/play state from a credential-free
shared-host snapshot in an App Group. The app publishes only when the configured group container
is actually available, and coalesces timeline reloads; the extension retains the controls-only
fallback when no snapshot exists. App and widget entitlements declare the same configurable group.
Provisioning, full extension/app linkage, timeline delivery and home-screen behavior are unverified.
Listening-insight and ActivityKit/lock-screen lyric widgets remain incomplete.

### Negotiated USB PCM rate

The USB-rate preference requests 88.2/96/176.4/192 kHz through AVAudioSession only
on a USB route. After activation, the rate iOS actually accepted configures the
retained native decoder and the source node; it is no longer forced through 48 kHz
PCM before conversion. The current track retains its decoder, position and lease.
A later load recreates the output when the preference, USB route or accepted rate
changes. Native getters show decoder configuration and the graph-reported device
rate separately. A refused request uses the accepted lower rate, and non-USB routes
retain the standard 48 kHz decoder configuration. Recording windows retain their
existing session policy. High-rate PCM uses ordinary handoff because the blend pump
is still 48 kHz; guards prevent a high-rate buffer from entering that timebase.
This is shared-system USB output: exclusive access, bit-perfectness, original-source
provenance and physical DAC output remain unverified or unsupported. Native fixture tests run in the Apple CI gate.

### CarPlay and widget signing configuration

App and widget targets now share `Configuration/SharedSigning.xcconfig` for their
public development-team/App Group metadata and optional ignored `Signing.xcconfig`
overrides. The widget does not inherit provider credentials from the app's native
configuration. Xcode target capabilities declare CarPlay audio on the app and App
Groups on both targets; each target keeps its own entitlement file and bundle id.
For a signed build, copy `Signing.xcconfig.example` and supply the registered team
and group. Both explicit provisioning profiles must grant that same group, and the
app profile must grant Apple's CarPlay audio entitlement. The local helper
`iosApp/scripts/check_signing_capabilities.py` checks these fields in decoded
profiles with `--app-profile`, `--widget-profile`, `--app-group` and optional `--team`.
It does not authenticate a profile, grant approval or sign an app. The existing CI
release remains explicitly unsigned; approved provisioning, signing and CarPlay/
widget device execution are still separate owner-run gates.

### Player occurrence and navigation actions

The shared player now binds iOS like/palette observations to the engine's occurrence,
including while the queue cursor has advanced during handoff. Likes carry their content
reference through asynchronous reads, so an old result cannot mark the new track liked.
Title taps open the known album or the song menu; artist taps open the artist or a shared
Compose selection dialog. Output actions have a Compose dialog over the native route
picker, and local-song Share uses the imported file rather than requiring a public URL.
These actions use the existing graph, routes and menus; they create no second player or
native app renderer.

### Per-song source chooser

The song menu now opens the existing shared source dialog through a source-only host
contract. It refreshes enabled choices from the live source settings, persists the
existing `songId=SOURCE` pin format, and reloads a playing occurrence at its current
position through the queue's normal ordered resolver. Explicit pins reach that resolver
instead of silently playing a downloaded/cache file from another source; Automatic
restores stored-file-first behavior. The dialog wires bounded YouTube search/radio and
marks other provider search filters unavailable. Global source order, credentials and
native stream admission remain with their existing owners.

### Shared lyrics header and bottom chrome

The full-screen shared lyrics sheet now shows the current artwork next to its title
and artist. Artwork height follows the measured text block, including font scaling;
only menu and return-to-player controls occupy the footer. No heart is added to the
lyrics header/footer. The shared shell omits its bar/compact/rail controls and frosted
recording gate while the expanded player covers it, rather than retaining invisible
bottom controls. The shared changes also apply to every portable host using that sheet.

### Shared module preferences

The iOS module manager now uses a commonMain grouped detail screen with provider
activation, connection/account fields, a separate quality group, earlier/later
priority controls and collapsible package information. Text and quality choices
open individual dialogs, and secret summaries never show their values. Configuration
and per-provider activation use separate Keychain entries. A bounded atomic manager
order file drives the real resolver order within the Modules source and survives
restarts; removal clears the provider's manager credential as well as its settings.
The API-v1 quality parser now understands scalar option `id` values used by the
merged TIDAL/Qobuz packages, and normalized metadata can be validated again when
listed. Automatic account authorization and module-specific Playback Pool/account
lanes remain explicitly unavailable; declared package connection modes and personal
credentials still work.

### Shared library/search/discovery: Spotify catalog details

Spotify album and artist routes now retain the actual catalog identity and artwork
instead of switching to a YouTube page before rendering. Albums use the shared album
screen with artist navigation, local bookmarks and the complete track list; completion
requires the declared total, unique continuation traversal and the existing time/request
bounds. Artists use the shared artist screen with real popular songs and paged album/
single shelves; the shared artist-section screen handles further releases. Album ids,
artist credits, explicit flags and ISRC now survive Spotify track parsing in library,
search and discovery rows. The existing catalog-to-audio matching path still starts
playback, and catalog tokens remain isolated from streaming credentials. Matching
cancellation propagates, and row-list cache retention is capped at 32 collections.
YouTube, QQ discovery/search, podcasts and local-library screens were already shared
and remain wired. QQ artist/album details, Spotify radio continuations and direct
catalog-song downloads are not supplied by these adapters. Real provider and device
execution remain unverified.

The saved default search source now also selects Apple Music, using the same
personal media-user-token or Playback Pool admission the Apple playback source
uses; the developer bearer is still fetched from the existing token owner. A host
without a usable Apple account shows the search's failed state instead of silently
searching YouTube. QQ search and the QQ home charts still use the anonymous RPC,
and Apple search returns songs only. Live Apple/QQ catalog access and new-source
compilation are unverified.
