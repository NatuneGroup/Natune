# Releasing Natune

The private `NatuneGroup/ArchiveTune-KMP` repository has two release channels:

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
same-date nightly replaces its prior release and tag. Unless `force` is selected
for a manual run, the workflow skips when the current `dev` commit matches the
target of the latest nightly release.

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
- Android nightly: the same seven variants with `-nightly.apk` names for the
  in-app updater.
- Windows x64: MSI, EXE installer, and portable ZIP.
- Linux x64 and arm64: DEB and AppImage.
- macOS x64 and arm64: DMG.
- iOS: unsigned IPA containing `Payload/ArchiveTune.app`.

Non-Android release assets use
`natune-<platform>-<arch>-<version>.<ext>` (the iOS name is
`natune-ios-<version>.ipa`). If any Android signing secret is missing, the APKs
are published unsigned and the release notes identify that state.

The workflows also publish `stable.json` and `nightly.json` to the fixed
`update-feed` prerelease in this repository. The manifest links point to this
repository's release assets. The app's current updater repository constants
still name `NatuneGroup/ArchiveTune`; private KMP releases are not publicly
downloadable, so updater endpoint and access behavior must be resolved before
using that feed for public updates.

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

## Native build prerequisites still open

The release matrix publishes only when every platform job succeeds. These host
recipes are not complete yet, so the matching jobs fail until they are added:

- Windows x64: `desktopApp/native/scripts/build-native-deps.sh` now also builds
  under MSYS2 MinGW-w64 (`mingw64` OpenSSL target, w32threads for FFmpeg).
  `stage-windows-package.py` walks the PE import table, stages the prefix DLLs and
  refuses anything outside the system-DLL allowlist. The engine builds with MinGW
  (`MinGW Makefiles` generator, statically linked libgcc/winpthreads) while
  `archivetune_system` and `archivetune_browser` stay MSVC with the static CRT, so
  no vcruntime/msvcp DLL is needed. The static OpenSSL prefix `build-tdjni.ps1`
  reads through `TDLIGHT_OPENSSL_PREFIX` comes from the same deps run
  (`openssl-static`, produced for every host).
- macOS: `stage-macos-package.py` installs the engine, stages the FFmpeg closure
  as `@rpath/<name>` dylibs with an `@loader_path` rpath and audits `otool -L`
  output, so `-PdesktopNativeDirectory` is no longer needed on any host. The x64
  leg runs on an arm64 runner through a x64 JDK under Rosetta 2; the dependency
  closure and tdjni build under `arch -x86_64`.
- Linux arm64 runs on the `blacksmith-8vcpu-ubuntu-2404-arm` label; if the
  organisation has no ARM pool the leg waits and must be relabelled.
- iOS: `scripts/build_ios_native_core.sh` has not run on a Mac yet.

Linux x64 and arm64 use the same recipe; the scripts choose the OpenSSL target and
the `linux-x64` or `linux-arm64` resource directory from the host architecture.
All Linux packages register `archivetune://` with the desktop: the app image's
`.desktop` entry gains `Exec … %u` and `MimeType=x-scheme-handler/archivetune;`
inside `createDistributable` (jpackage's `--linux-shortcut` writes neither), and the
AppImage's hand-written entry declares the same pair in the workflow.

## Run by hand

1. Open **Actions → Stable release → Run workflow**, select `main`, and dispatch.
   The workflow computes the next stable version; it skips if that tag already
   exists. A push to `main` runs the same pipeline automatically.
2. Open **Actions → Nightly release → Run workflow**, select `dev`, and dispatch.
   Set `force` only to rebuild the same commit. A push to `dev` runs the nightly
   pipeline automatically.
3. Wait for the platform matrix and publish jobs to finish, then review the
   release assets and signing status on the generated release.
