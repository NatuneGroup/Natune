# Foundation readiness

> Historical record for the independent foundation `c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`.
> The merged private `dev` keeps its `:shared` module inactive under `shared/` (its build
> script is `shared/build.gradle.kts.reference`) and does not run its workflow. Statements
> below about imported history, single-module scope and CI describe that foundation only.
> Current port readiness status is in [kmp-baseline.md](kmp-baseline.md).

## Actual stack and commands
Kotlin/KMP/serialization/Compose compiler 2.4.21; CMP 1.12.2; Android-KMP
library plugin 9.2.1; checksum-pinned Gradle 9.6.1; Temurin 21.0.13+9.
The candidate artifacts were available; no fallback or metadata-check suppression.
Local resource observation: 4 CPUs, 7.8 GiB RAM, 6 GiB swap, 35 GiB free initially.
One worker, conservative heaps; no legacy Android full build or VPS mutation.

Executed: `./gradlew --no-daemon --console=plain :shared:desktopTest :desktopApp:jvmJar`.
Nine tests, zero failures/errors/skips. Test-first run failed on missing model
symbols before implementation, then the real JVM compilation/test run passed.
The Android-KMP plugin imports with Android/JVM/two iOS targets. Android compile
and Apple SDK/runtime verification are separate, not inferred from JVM success.

Playback boundary is informed by source service isPlaying/playWhenReady distinction,
speed callbacks/discontinuities and lifecycle-bound player/lyrics observations.
It does not reproduce the old Player transition elapsed-time fallback or claim
that wall-clock interpolation is actual audio. Occurrence/discontinuity ownership,
freshness, pause/stall, remote authority, speed and duration bounds are explicit.
No playback adapter exists; the shared window displays that fact. The details
button allows later independent launch/interaction validation.

## Source, privacy and remaining obligations
Behavioral baseline: ArchiveTune dev `b7e4dd444fea202a5c32e96f7b1199a74fe3ae70`.
Legacy source/staged snapshot are read-only. This repository has no imported history,
provider keys, accounts, keystore, recovered binaries or production secrets.
Original GPL/other notices stay with source and must be retained at integration;
new Kotlin has GPL-3.0-or-later headers, wrapper has upstream Apache notices.
Metrolist remains credited private reference. This is not feature parity or signing.

Full dependency details and sanitized evidence are recorded outside product source:
`/home/factory-user/outputs/archivetune-kmp-planning/dependency-readiness.json` and
`.md`; downloads/manifests/logs under
`/home/factory-user/outputs/archivetune-kmp-readiness`.
Fetched native sources/artifacts do not establish linked ABI/runtime/physical QA.
Native engine, storage migrations, all feature surfaces, account flows, release
signing/upgrade, complete workflow parity and independent validation remain open.

## CI and free allowance
Manual readiness only; no push/PR/schedule trigger, publishing, external scans,
artifact uploads, notifications, cache writes or automatic retry. Separate later
validation agent owns real UI/runtime validation; readiness may compile/package.

| Native host | Label | Timeout | Multiplier | Maximum equivalent minutes |
| --- | --- | ---: | ---: | ---: |
| Linux | blacksmith-32vcpu-ubuntu-2404 | 12 min | 16 | 192 |
| Windows | blacksmith-32vcpu-windows-2025 | 5 min | 32 | 160 |
| macOS | blacksmith-12vcpu-macos-26 | 5 min | 40 | 200 |

All three would reserve 552 plus 48 headroom = 600 equivalent minutes, below this
task's 650 ceiling and user-reported 3,000 free allowance. This table is a bound,
not an assertion these jobs ran or a provider-enforced billing cap. Dispatch only
coherent needed native operations, serialize and reconcile actual run durations.
