# Releasing Natune

The `NatuneGroup/Natune` repository has two release channels:

| Channel | Branch | Trigger | Release |
| --- | --- | --- | --- |
| Stable | `main` | Every push or manual dispatch | GitHub Release titled `X.Y.Z`, tagged `vX.Y.Z` |
| Nightly | `dev` | Every push or manual dispatch | Prerelease titled and tagged `N<yyyyMMdd>` using the UTC date |

Every successful release run builds the Android flavor matrix, Windows x64, Linux
x64 and arm64, macOS x64 and arm64, and an unsigned iOS IPA. The release is
published only after all platform jobs finish successfully. Stable and nightly
builds do not commit version changes to the branch.

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
DMGs and the iOS IPA are best-effort on their GitHub-hosted legs, so a leg that
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

## Manual ARM64 release candidate (artifact only)

`Build Pull Request` has one opt-in, same-repository manual mode that builds a
signed ARM64 Release APK for storage and update-over-install testing without
publishing anything:

```sh
gh workflow run build_pull_request.yml \
  --ref <reviewed-branch> \
  -f android_release_candidate=true \
  -f candidate_version_code=<reserved code>
```

- `candidate_version_code` is mandatory. It must be an absolute code allocated
  above the source `trustedUpgradeVersionCodeFloor`, above the measured
  N20261103 ARM64 baseline `1793753723`, and above the newest `at-build:` marker
  in this repository's published release bodies read at dispatch. A
  blank/default value or an unreadable marker fails before checkout. The
  dispatcher must also confirm that the reserved code exceeds any newer target
  installed on the test device but not yet published, which CI cannot observe.
- The mode requires all four historical signing secrets. Unlike the release and
  nightly paths, a missing or incomplete set fails the job before the build
  rather than leaving an unsigned APK that could be mistaken for a candidate.
  The keystore is decoded to a runner-temporary file inside the signing step and
  removed on every exit; no key, password or certificate subject is printed or
  uploaded.
- The job checks out the dispatched `github.sha` with recursive pinned
  submodules, credential persistence off and an exact-HEAD assertion, then
  builds only `:app:assembleGmsMobileArm64Release` with JDK 21, compileSdk 37,
  NDK `30.0.16248370`, `-Pautomix=false -PslimTdlib=true` and the standard
  Nightly build identity (`IS_NIGHTLY_BUILD=true`,
  `NIGHTLY_BUILD_HASH=<sha>`, `PULL_REQUEST=false`). It supplies the same
  provider/client BuildConfig mappings as the release Android leg.
- Signing follows the release leg: zipalign, the historical keystore with v1
  signing disabled, `apksigner verify`, and a v2/v3 certificate match. Before
  the pin is trusted, the job downloads the public N20261103 baseline into
  `RUNNER_TEMP`, verifies its exact bytes and SHA-256
  (`287096e15d568c8ad6e52c8a91dd55c04b5f904ea53d15a2d595ce4e16fb94bc`,
  20,306,627 bytes) and versionCode `1793753723`, and lets
  `apksigner verify --print-certs` confirm its certificate SHA-256
  `6db8326a550861f0f96b9adcb706207d75ac4480de454fb8af95daa5d6c7f9d9`.
  The candidate must present the same certificate. That pin was derived from
  the pinned APK's legacy v2/v3 certificate block; the local parse is not a
  signature verification, so the in-CI `apksigner` check is what validates it.
  The baseline is never uploaded. If the pinned release asset is no longer
  available, the job fails closed; re-pin from an archived, verified copy rather
  than skipping the comparison.
- The final signed file is re-checked for package
  `moe.rukamori.archivetune`, non-debug, minSdk 24, only `arm64-v8a` and the
  reserved version; `scripts/apk_size_report.py` measures it. The artifact
  contains exactly one APK plus `SHA256SUMS`, `candidate-metadata.json` and
  `apk-size.json`, is named as a candidate, is retained three days, and is not a
  published Nightly or Stable release.
- The whole job has a 30-minute timeout. One reservation costs at most 30 wall
  minutes times the Linux x64 16x equivalent-minute multiplier, i.e. 480
  equivalent minutes; reserve it inside the remaining free balance and do not
  treat that balance as a provider-enforced cap. Upload or quota failure fails
  the job; there is no release, tag, external store or update-feed fallback.
- `android_release_candidate` cannot be combined with `desktop_app_image`. When
  only the candidate flag is true, the ordinary Debug/KMP `build` and
  `desktop-compile` jobs are skipped so one Android job runs.

Device installation, in-place update, playback and installed-storage changes
are not established by a successful artifact upload. `python3 -m unittest
scripts.test_candidate_workflow -v` covers the candidate gates, unchanged
defaults, fake SDK/`gh`/`curl` contracts and metadata checks without a JVM,
Gradle, network or real signing.

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
exact-head packaging run — Blacksmith for the Android, Windows and Linux legs,
GitHub-hosted `macos-26` for the macOS and iOS legs:

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
  resolving to the GitHub `macos-26` image's 26.4.1) and builds only the
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

## Run by hand

1. Open **Actions → Stable release → Run workflow**, select `main`, and dispatch.
   The workflow computes the next stable version; it skips if that tag already
   exists. A push to `main` runs the same pipeline automatically.
2. Open **Actions → Nightly release → Run workflow**, select `dev`, and dispatch.
   Set `force` only to rebuild the same commit. A push to `dev` runs the nightly
   pipeline automatically.
3. Wait for the platform matrix and publish jobs to finish, then review the
   release assets and signing status on the generated release.
