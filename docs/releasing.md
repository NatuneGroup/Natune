# Releasing Natune

The `NatuneGroup/Natune` repository has two release channels:

| Channel | Branch | Trigger | Release |
| --- | --- | --- | --- |
| Stable | `main` | Every push or manual dispatch | GitHub Release titled `X.Y.Z`, tagged `vX.Y.Z` |
| Nightly | `dev` | Every push or manual dispatch | Prerelease titled and tagged `N<yyyyMMdd>` using the UTC date |

Every successful release run builds the Android flavor matrix, Windows x64, Linux
x64 and arm64, macOS x64 and arm64, and an unsigned iOS IPA. The Android and
Windows/Linux legs are required for publication; the macOS and iOS assets are
best-effort and attach after the release exists. Stable and nightly builds do not
commit version changes to the branch.

## Versioning

Stable versions come from the highest existing Git tag matching `vX.Y.Z`. The
next stable version increments the minor component and sets patch to zero. When
the next minor would be 8, the major increments and the minor resets to zero.
With no matching tag, `baseVersionName` (`15.0.0`) is the starting point:

- `v15.0.0` → `15.1.0`
- `v15.6.0` → `15.7.0`
- `v15.7.0` → `16.0.0`
- No matching tag → `15.1.0`

The rule is implemented in `.github/scripts/next-version.py`. Stable releases
are skipped if their calculated tag already exists.

Nightly tag, release title, Android `versionName`, desktop display version and
artifact names use `N<yyyyMMdd>` in UTC (for example, `N20261026`). The iOS
`CFBundleShortVersionString` must be numeric, so the IPA carries the package
version there and the allocated build code as `CFBundleVersion`. A
same-date nightly replaces its prior release and tag. After each successful
nightly publish, only the newest five `N<digits>` releases are kept, ordered by
publish time; older ones are deleted together with their tags. Stable `v*`
releases, the `update-feed` release and `local-*` tags are never touched. Unless
`force` is selected for a manual run, the workflow skips when the current `dev`
commit matches the target of the latest nightly release.

Desktop package formats require numeric versions. Stable installers use the
stable version; nightly installers use the next stable version as their package
version while retaining `N<yyyyMMdd>` in the release title and artifact name.

`VERSION_CODE_OVERRIDE` is allocated from the current UTC epoch second (Unix
seconds) plus `trustedUpgradeVersionCodeFloor` in `app/build.gradle.kts`. Stable
and nightly use this same formula, and the allocated code is shared by all
artifacts in that workflow run. It is strictly monotonic — an epoch-minute
formula let two runs in the same minute publish identical version codes — is
independent of the Actions run ID and this repository's commit count, and fits
Android's positive `Int` range until roughly the year 2100. The app build
rejects malformed, non-positive overrides and signed version codes at or below
the trusted floor.

## Artifacts

- Android stable: `app-gms-mobile-{arm64,armeabi,x86,x86_64,universal}-release.apk`,
  `app-foss-mobile-universal-release.apk`, and `app-gms-tv-universal-release.apk`.
- Android nightly: six variants, excluding the Stable-only GMS mobile x86 APK,
  with `-nightly.apk` names for the in-app updater.
- Windows x64: MSI, EXE installer, and portable ZIP.
- Linux x64 and arm64: DEB and AppImage.
- macOS x64 and arm64: DMG.
- iOS: unsigned IPA containing `Payload/ArchiveTune.app`.

Non-Android release assets use
`natune-<platform>-<arch>-<version>.<ext>` (the iOS name is
`natune-ios-<version>.ipa`). If any Android signing secret is missing, the APKs
are published unsigned and the release notes identify that state.
The publisher checks each expected file by name instead of one fixed total.
The Android APKs and the Windows/Linux installers stay required; the two macOS
DMGs and the iOS IPA are best-effort on their Blacksmith macOS legs, so a leg that
fails or never picks up leaves a partial release whose notes list the missing
names. A complete run still carries 16 files for Nightly or 17 for Stable,
including the ten non-Android assets. The Actions handoff artifacts (including
the update-feed manifest) expire after three days; the published release assets
remain.

Workflow-artifact storage is metered separately from release assets, so a full
artifact quota must not break a run whose builds succeeded. Each build job tries
`actions/upload-artifact` first; when that upload fails, the same file set is
parked on a per-run prerelease tagged `staging-<run_id>-<job>` (for example
`staging-40906373528-android-gms-mobile-arm64`) and the publish job restores the
missing files from those tags with `gh release download`. The publish job deletes
the per-variant staging releases once the real release is published. The
`publish-update-feed` job runs after every outcome of a publishing run and removes
every `staging-<run_id>-*` release and tag of that run, including the update-feed
hand-off once it has read the manifest. That covers a successful publish, a
publish refused for a stale head and a failed run. A `publish: false` smoke run
keeps its staging releases so they stay downloadable; delete those by hand once
they are no longer needed.

The workflows also publish `stable.json` and `nightly.json` to the fixed
`update-feed` prerelease in this repository. The manifest links point to this
repository's release assets. The app's current updater repository constants
still name `NatuneGroup/ArchiveTune`; whether this repository's own feed should
serve app updates is a separate product decision, so those endpoints stay
unchanged until it is resolved.

## GitHub Actions secrets

No user-created secret is required for a workflow to run or publish; the
workflows use the repository-scoped `GITHUB_TOKEN` with `contents: write` only
for release and update-feed publication.

The following four secrets are needed for signed Android release APKs and
updates that retain the existing Android signing identity:

- `KEYSTORE` — the existing release keystore, base64 encoded.
- `KEY_ALIAS`
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`

They are optional to the workflow. If one or more are absent, Android APKs are
left unsigned; a partial set does not fail the build.

Provider credentials are also optional. Each is mapped from a GitHub secret of
the same name. An absent secret is passed as an empty string and leaves its
feature not configured:

- `ARCHIVETUNE_LASTFM_API_KEY`
- `ARCHIVETUNE_LASTFM_API_SECRET`
- `LYPRIO_BASE_URL` (an Actions variable, not a secret) and `LYPRIO_HMAC_KEY` — the built-in Lyprio lyrics server pair
- `ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY`
- `ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN`
- `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID`
- `ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET`
- `ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY`
- `ARCHIVETUNE_POTOKEN_REQUEST_KEY`
- `ARCHIVETUNE_TIDAL_CLIENT_ID`
- `ARCHIVETUNE_TIDAL_CLIENT_SECRET`
- `ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID`
- `ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET`
- `ARCHIVETUNE_TIDAL_CANVAS_EMBED_TOKEN`
- `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID`
- `ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET`
- `ARCHIVETUNE_JIOSAAVN_DES_KEY`
- `ARCHIVETUNE_LISTENER_PRESENCE_URL`
- `ARCHIVETUNE_TELEGRAM_API_ID`
- `ARCHIVETUNE_TELEGRAM_API_HASH`
- `ARCHIVETUNE_SOURCE_PROVIDER_URL`
- `ARCHIVETUNE_SOURCE_PROVIDER_KEY`
- `ARCHIVETUNE_POOL_CLIENT_KEY`
- `ARCHIVETUNE_FEEDBACK_API_URL`
- `ARCHIVETUNE_FEEDBACK_INGEST_KEY`

These are build-time client values and are extractable from shipped binaries;
they must not be server-administrator credentials.

## Native build prerequisites and validation

The release matrix publishes while the required legs deliver; the macOS and iOS
legs are best-effort, so a failed or unscheduled one leaves a partial release
instead of blocking the rest. Workflow and recipe changes still require an
exact-head packaging run — Blacksmith for every host, with
`blacksmith-12vcpu-macos-26` for the macOS and iOS legs:

- Windows x64: `desktopApp/native/scripts/build-native-deps.sh` now also builds
  under MSYS2 MinGW-w64 (`mingw64` OpenSSL target, w32threads for FFmpeg).
  `stage-windows-package.py` walks the PE import table, stages the prefix DLLs and
  refuses anything outside the system-DLL allowlist. That walk and the allowlist live
  in `windows-native-deps.py`, which also writes `native-dependencies.txt` from the
  staged import tables: Windows has no rpath, so the loader preloads that order before
  `System.load` reaches the engine. The packaging job re-runs the script in `--check`
  mode against the packaged app image, so an unbundled import, a non-x64 DLL or a
  missing/stale manifest fails the build instead of a user's playback. The engine builds with MinGW
  (`MinGW Makefiles` generator, statically linked libgcc/winpthreads) while
  `archivetune_system` and `archivetune_browser` stay MSVC with the static CRT, so
  no vcruntime/msvcp DLL is needed for those adapters. Release TDLight also uses
  MSYS2 MinGW through `build-tdjni.sh`, with `TDLIGHT_OPENSSL_PREFIX` pointing at
  `build/native/openssl-static` and `TDLIGHT_ZLIB_PREFIX` at `build/native/prefix`.
  The latter contains both zlib's DLL/import library and its static `libz.a`.
  Do not feed these MinGW archives to the separate MSVC PowerShell recipe.
  zlib uses upstream's `win32/Makefile.gcc install` recipe (which builds
  `libz.a`, `zlib1.dll`, `libz.dll.a` and `zlib.pc` without the example programs)
  and every dependency build runs under the MSYS build of `make`, whose recipes
  are POSIX. A failing dependency prints the end of its own log, so a red leg
  names its cause instead of only an exit status.
  Downloads retry bounded connection/TLS failures without changing checksum or
  FFmpeg signature verification. The WebView2 SDK argument is the extracted
  package root, not its `build/native` child.
- macOS: `stage-macos-package.py` installs the engine, stages the FFmpeg closure
  as `@rpath/<name>` dylibs with an `@loader_path` rpath and audits `otool -L`
  output, so `-PdesktopNativeDirectory` is no longer needed on any host. The x64
  leg runs on an arm64 runner through a x64 JDK under Rosetta 2; the dependency
  closure and tdjni build under `arch -x86_64`. CMake and the stager also receive
  the packaging JVM architecture explicitly, the stager prints the `lipo`
  architecture it audits, and `lipo` takes the file before `-verify_arch`, so it
  rejects mismatched dylibs instead of reading the path as an architecture flag.
  The browser install task owns only its library output, so Gradle cannot clean
  the playback engine and media closure as stale browser outputs.
- Linux x64 and arm64 install `gperf` for TDLib source generation. The arm64 leg
  uses `blacksmith-8vcpu-ubuntu-2404-arm`.
- iOS release CI explicitly expects the pinned Xcode 26.4 (`setup-xcode`,
  resolving to the macOS 26 runner image's 26.4.1; the Blacksmith image's exact
  Xcode build is unverified until a run selects it) and builds only the
  `iphoneos` native closure. It retains the producer's strict version check
  rather than allowing an arbitrary/unverified Xcode, and keeps code signing
  disabled for the IPA. The job installs gpg like the desktop macOS legs, and the
  producer verifies the FFmpeg tarball against the pinned release fingerprint
  inside a short-lived temporary `GNUPGHOME` (macOS caps unix socket paths, so the
  keyring cannot live under the deep work tree) and prints the gpg log on failure.
  This is not yet a successful device-link/package result.

Nightly preparation runs `scripts.test_release_workflow` and
`scripts.test_native_release_packaging` with Python's standard-library unittest.
These use fixture files and fake SDK/GitHub/native tools to cover argument
boundaries, six/seven-variant inventories, missing signing secrets, private
manifest URLs, stale-head refusal, download integrity and native staging.
They do not compile an APK, native library or JVM code, contact GitHub, or prove
runtime behavior. New regression tests and packaging changes are unexecuted
until the orchestrator runs the candidate on Blacksmith.

Linux x64 and arm64 use the same recipe; the scripts choose the OpenSSL target and
the `linux-x64` or `linux-arm64` resource directory from the host architecture.
All Linux packages register `archivetune://` with the desktop. jpackage writes the
`--linux-shortcut` entry only while it builds a .deb or .rpm, never into the app
image, and without `%u` or a MimeType. `addLinuxBrowserDebDepends` adds
`Exec … %u` and `MimeType=x-scheme-handler/archivetune;` to the entry in the .deb
it already unpacks, `addLinuxBrowserRpmRequires` passes jpackage an overriding
`Natune.desktop` template through `--resource-dir`, and the AppImage's
hand-written entry declares the same pair in the workflow.

## Runner cost

Apple CI runs on Blacksmith's `blacksmith-12vcpu-macos-26` (12 vCPU Apple silicon,
macOS 26). Blacksmith documents macOS 12 vCPU at 40 equivalent x64 2-vCPU minutes
per elapsed minute (6 vCPU is 20) and a free tier of 3,000 equivalent minutes per
month. The owner reports those 3,000 minutes as remaining; this is not a
provider-enforced cap and no paid overage is authorized.

The 2026-11-03 nightly (run 41735716057) is the measured baseline. Its macOS jobs
ran on the GitHub-hosted `macos-26` image, so these are durations, not Blacksmith
speed claims:

| Job | Duration | Equivalent minutes at 40x for the same duration |
| --- | ---: | ---: |
| Unsigned iOS IPA | 3 h 09 m 33 s | about 7,580 |
| Desktop macOS x64 | 1 h 56 m 48 s | about 4,670 |
| Desktop macOS arm64 | 44 m 50 s | about 1,790 |

A same-speed iOS release leg alone would exceed the reported balance. The iOS
compile check is the bounded trial: a `workflow_dispatch` with the default
`ios_simulator_app=false` has a 45-minute ceiling (1,800 equivalent minutes at
40x), while `ios_simulator_app=true` raises it to 330 minutes (13,200). The
release workflow's dispatch defaults `build_ios=false`; a release or nightly with
`build_ios=true` runs the 3-hour-class iOS job. Neither the opt-in app link nor a
`build_ios=true` release/nightly may run until the owner explicitly approves the
spend. Xcode 26.4 selection and actual Blacksmith durations remain unverified.

## Run by hand

1. Open **Actions → Stable release → Run workflow**, select `main`, and dispatch.
   The workflow computes the next stable version; it skips if that tag already
   exists. A push to `main` runs the same pipeline automatically.
2. Open **Actions → Nightly release → Run workflow**, select `dev`, and dispatch.
   Set `force` only to rebuild the same commit. A push to `dev` runs the nightly
   pipeline automatically.
3. Wait for the platform matrix and publish jobs to finish, then review the
   release assets and signing status on the generated release.
