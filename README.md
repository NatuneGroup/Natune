# ArchiveTune KMP
## Status
Independent shared KMP foundation; full port in progress; legacy Android source import remains blocked; no feature parity/signing claim.

## Build and test
JDK 21 is required. Verified on Linux x64 with Kotlin 2.4.21, Compose Multiplatform
1.12.2, Android-KMP library plugin 9.2.1 and Gradle 9.6.1:

```sh
./gradlew --no-daemon --console=plain :shared:desktopTest :desktopApp:jvmJar
```

Nine deterministic common clock-boundary tests run on desktop JVM. The shared
module declares Android, desktop JVM, iosArm64 and iosSimulatorArm64; this is not
a JVM-only substitution. iOS is unverified. Android host integration is future work.
Launch command for later independent UI validation:

```sh
./gradlew :desktopApp:run
```

That window intentionally has no audio adapter; its details button is ordinary
shared UI, not a playback simulator.

## Source and attribution
Behavioral source of record: NatuneGroup/ArchiveTune **dev**
`b7e4dd444fea202a5c32e96f7b1199a74fe3ae70`. The original Android checkout and
blocked staged snapshot remain untouched. No legacy source/history is imported here.
Metrolist and MetrolistGroup are credited as private behavioral/reference material;
recovered builds are not dependencies and exact original KMP source is not claimed.
Keep the port private until the authorized Metrolist release/permission boundary.

New Kotlin files are GPL-3.0-or-later. Preserve ArchiveTune's inherited GPL,
copyright, third-party notices and source obligations when integration is legitimately
unblocked. Gradle wrapper files retain upstream Apache-2.0 notices. IconPack's
CC-BY-SA and other dependency licenses remain distinct; no legacy asset is included.

## CI and free allowance
Only manual readiness CI exists here, with read-only repository permission,
serialized jobs, pinned actions and no publishing, cache writes or notifications.
It does not replace the required final Stable/Nightly/build/test workflow parity.
Use only the user-reported free Blacksmith allowance, reserve weighted timeout
usage before dispatch and never automatically retry. See `docs/engineering/readiness.md`.
