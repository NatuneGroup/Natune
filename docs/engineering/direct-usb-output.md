# Android direct USB bit-perfect output

Natune's **Bit-perfect USB output** switch (`BitPerfectUsbOutputKey`) keeps its
Android platform route. The **Direct USB driver (experimental)** sub-option
(`DirectUsbDriverKey`) is default OFF and enabled only under the parent switch.
Both use the same restart prompt. With the sub-option off, startup installs no
USB receiver, requests no USB permission and creates no direct sink wrapper;
pre-Android-14 support semantics stay the same as dev. The existing exclusions
for DSP, normalization, offload, crossfade and automix remain authoritative.

The port follows upstream ArchiveTune `38670c8b6ecef127d9d5a059986b9635fce2023a`.
The read-only reference's later commits through `e7eb6db` contain no USB fixes.
Natune already had Android 14 preferred-mixer negotiation, lossless integer-container
conversion, an AAudio exclusive route, bit-perfect runtime state and processing guards.
Those remain the fallback/adjacent architecture; the upstream settings and service
implementation are not copied wholesale.

`DirectUsbAudioSink` wraps Natune's existing platform sink and chooses USB before
Media3 1.10.1 converts integer PCM to float. It preserves 16/24/32-bit integers,
native sample rates and channel counts. The shared `PcmSamples` converter handles
USB subslots; float input is rounded at the DAC's advertised valid depth and the
status explicitly distinguishes rounded float from exact integer PCM. Nonidentity
channel maps and gapless trim metadata use the platform route.

`UsbManager` enumerates audio streaming interfaces and requests Android permission
only while both switches are enabled. Denial retains ordinary playback without
repeated dialogs. Descriptor parsing accepts one configuration, UAC1 discrete or
continuous rates and UAC2 terminal clock references. Implicit-feedback, raw-DSD
quirks and unsupported descriptors fall back. Native code verifies UAC1 endpoint
rate read-back or the UAC2 clock's current rate. Unlike Linux's tolerant UAC1
handling, a DAC that stalls sampling-frequency `GET_CUR` is rejected by the
direct driver and uses the platform fallback. Rate read-back is mandatory here;
this compatibility restriction does not relax feature-unit volume verification. Full/high-speed USB supports up to
eight PCM channels; SuperSpeed companion descriptors and clock selector graphs are
not implemented. There is no resampler, channel mixing, DoP or raw DSD transport.

`native/usbdirect` drives bounded isochronous USBFS URBs on one worker thread using
an Android-authorized duplicated file descriptor. It claims only the control and
streaming interfaces, selects the matching alternate setting, tracks completed PCM
frames from successful packets' actual lengths and handles explicit asynchronous
feedback. An isolated bad packet is tolerated; URB errors, eight consecutive bad
URBs, missing feedback and timeouts stop the direct route. Failed packets never
advance the music clock. OUT packet lengths need not match requested lengths;
separate accepted-frame drain accounting prevents packet loss from blocking EOF.
Flush generations exclude cancelled pre-seek transfers. Closure cancels URBs,
returns the interface to alternate zero, reattaches a displaced kernel driver and
closes the descriptor before freeing transfer buffers. One device lease prevents two
Natune players from claiming the DAC simultaneously.

Hardware feature-unit volume is mandatory. Startup never raises an existing level;
it initially attenuates to at most -40 dB, then applies the lower of each channel's
original level and Android music volume multiplied by player volume, capped at 0 dB.
Initial sink callbacks and passive settings notifications retain that ceiling;
only subsequent volume changes may raise it. Every write is read back. Explicit mute emits silence without software gain. System
volume changes are observed and served away from the playback thread. Control
transfers serialize with close on a separate lifetime lock, leaving PCM writes
free to run while a volume transfer or read-back waits on the bus. Original
levels are restored when the bus still works. Unsupported or unverifiable controls
use the platform route; no fixed/full-volume opt-in is added. Unlike upstream's idle
suspension/replay buffer, Natune retains its direct stream while paused and releases
it at end of stream, reset or service destruction. This avoids introducing a second
pause/replay policy in the existing player.

The native library vendors no USB dependency. It uses `-Os`, section collection,
hidden visibility, a JNI-only version script, stripping and 16 KB load alignment.
The old worker's libusb subset was uncommitted, LGPL-2.1-or-later, approximately
750 KB of source; it was removed with its CMake target and `.gitignore` exception.
Upstream-derived notices and GPL/LGPL licence texts are retained in source and in
About's bundled notices.

## Unverified on hardware

Claiming the USB interface can detach `snd-usb-audio` and remove Android's routed
audio device. Both primary and secondary players keep Media3's
`setHandleAudioBecomingNoisy(true)` protection; there is no separate app noisy
receiver. The noisy broadcast carries no reliable USB-device identity or cause,
and a real unplug can race the native open. A timed pause-suppression window
cannot safely distinguish those events, so it is not installed. Whether opening
the direct driver pauses playback, and the ordering of kernel detach, noisy
broadcasts and physical-unplug recovery, remain unverified on hardware. The
driver remains experimental and default off until that behavior is established.

Validation commands are targeted and every Gradle invocation uses the shared lock:

```sh
flock -w 5400 /data/workspace/tmp/gradle.lock ./gradlew \
  :app:compileFossMobileArm64DebugKotlin \
  :app:externalNativeBuildFossMobileArm64Debug \
  :app:testFossMobileArm64DebugUnitTest \
  --tests 'moe.rukamori.archivetune.playback.usbdirect.*' \
  --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1536m
```

The local Gradle attempt (offline to avoid large downloads) failed before compilation:
no Android SDK/NDK is configured and the checkout's uninitialized `lyrics` submodule
leaves `lyrics/betterlyrics` missing. Gradle was stopped under the lock. The 19
pure-Kotlin JUnit tests plus three PCM-output short-write/flush/mute tests ran with the
installed standalone Kotlin compiler. All ten Android playback components also
typechecked against Android API 36, the pinned Media3 1.10.1 libraries and XML-derived
resource-ID declarations; this is bounded API coverage, not an SDK 37 app build. Host CMake
transport tests and a host JNI build passed; the stripped host JNI library exported
only seven entry points before the drain-accounting addition (now eight JNI entry points) and had 16 KB load alignment. Those results do not establish
Android ARM64 linkage, APK size or Android app compilation. GitHub's PR builds remain
required. No physical DAC, USB permission UI, bus timing, rate switching, unplug
recovery, volume keys, suspend/resume or actual bit-perfect output was verified.
