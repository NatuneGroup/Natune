# AGENTS.md — Natune

Natune (`NatuneGroup/Natune`) is a Kotlin Multiplatform music player. It grew out of
ArchiveTune and keeps that lineage: the Android host still uses the
`moe.rukamori.archivetune` package. Audio comes from Tidal, Qobuz, Deezer, Apple Music,
Amazon, QQ Music, JioSaavn, Telegram, optional modules and an opt-in Spotify streaming
source, with YouTube as the final fallback. Spotify also supplies catalog metadata.

`dev` is the integration branch and builds the Nightly channel. `main` is Stable.
There are only these two channels; Canary is retired (see
[RELEASES](docs/claude/RELEASES.md)). Open PRs against `dev`.

## Layout

- `:app`: Android host. Kotlin sources live in
  `app/src/main/kotlin/moe/rukamori/archivetune/`.
- `:shared-domain`, `:shared-data`, `:shared-ui`: the KMP shared layers used by
  Android, desktop and iOS. `shared/` is an inactive foundation module (its build file
  is `shared/build.gradle.kts.reference`); do not add it to `settings.gradle.kts`.
- `:desktopApp` (+ `:desktopApp:tdlight-jvm`): the JVM desktop app.
- `iosApp/`: the Xcode project, the SwiftUI shell and the native audio/Telegram bridges.
- Library modules: `:spotifycore :canvas :jiosaavn :lastfm :musixmatch :shazamkit
  :modules` and `:lyrics:*`.
- Git submodules: `core` → `vossgraves/core`, `lyrics` → `4nx3b/lyrics`,
  `IconPack` → `NatuneGroup/IconPack`, `deobfuscator` → `rukamori/morideobfuscator`.
  Commit and push inside a submodule before you move its gitlink; never leave a dirty
  or unpushed pointer. Never point `core` at a `rukamori/core` revision: its
  `NetworkGatekeeper` blocks every request by default.
- Docs: product knowledge in [docs/claude/](docs/claude/), engineering procedures in
  [docs/engineering/](docs/engineering/), subsystem references in `docs/`, reusable
  agent workflows in [.factory/skills/](.factory/skills/).

## Invariants

- **Identity and signing.** `applicationId` stays `moe.rukamori.archivetune`. Release
  builds sign only when all four secrets `KEYSTORE`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`
  and `KEY_PASSWORD` exist, using the historical release keystore so updates install
  in place. Without them the APKs stay explicitly unsigned. The manual ARM64 release
  candidate in `Build Pull Request` is the exception: it fails without signing. Never
  restore a committed debug keystore, `Koiverse.jks*`, `ArchiveTuneKoiverseServer.txt`
  or `DataServer.txt`. Do not rotate the release key or read secret values.
- **Multi-source playback.** Providers live in `tidal/`, `deezer/`, `qobuz/`,
  `spotify/`, `telegram/`, `audiosource/` and `:modules`; the shared contract and source order live in
  `audiosource/` (`AudioSourceConfig.DEFAULT_ORDER`). Resolution goes through
  `resolveMultiSourceDataSpec` in `playback/MusicService.kt`; `tidal-dash://` and
  `telegram://` are routed there. Do not rewire playback around this chain.
- **YouTube extractor tiers.** `YTPlayerUtils` → InnerTubeX → NewPipe →
  `EchoStreamResolver`, run by `playback/stream/YouTubeFailsafeChain`. The order lives
  only in `ResolveAudioStreamUseCase.shippedFallbackTiers`. Echo stays last.
  Login failures (`InvalidPlaybackLoginContextException`,
  `LoginRequiredForPlaybackException`) skip every fallback tier. Manual client choice
  stays available. No Python or bundled yt-dlp: the external YTDLnis plugin is the only
  yt-dlp route. See [extraction](docs/extraction.md).
- **Two Spotify accounts.** The catalog account (`SpotifySpDcKey`,
  `Spotify.accessToken`) serves metadata, search and import. The experimental
  `AudioSourceType.SPOTIFY` source uses a separate streaming account (`SpotifyStream*`
  keys, `SpotifyStreamingAccount`). Streaming code never reads or sets the catalog
  token, and stands down when both sign-ins are the same account. See
  [Spotify streaming](docs/spotify-streaming.md).
- **Pool credentials** stay in their own Keystore-encrypted cache, behind the Playback
  Pool toggle, and never overwrite personal sign-ins.
- **Listen Together** uses LAN plus exactly the servers in
  `listentogether/ListenTogetherServers.kt` (vivi's JSON server on `hf.space` and the
  protobuf-only Meowery server). The retired `onrender.com` host is accepted only in
  old invite links and is never contacted. No koiverse endpoints anywhere.
- **Discord presence** uses the official Social SDK with OAuth PKCE and no user-token
  login. It is built in only with `-Pnatune.discordSdk=true` and the licensed AAR at
  `third_party/discord-social-sdk/android/discord_partner_sdk.aar`. Release builds
  fetch it through `.github/actions/fetch-discord-sdk`, and
  `scripts/check_discord_sdk_apk.py` fails a shipping APK without it. PR and debug
  builds stay SDK-free and never receive `DISCORD_SDK_TOKEN`. Never embed a client
  secret. See [Discord Social SDK](docs/discord-social-sdk.md).
- **Modules.** `:modules` hosts ArchiveTune API-v1 `.arch` modules. Keep format/API
  compatibility; the minimum compatible app version is the upstream contract floor
  `15.1.0`, not Natune's version. Never restore `NetworkGatekeeper` or hardcoded-key
  envelope decryption. See [modules](docs/modules.md).
- **Bit-perfect USB.** The existing Android `BitPerfectUsbOutputKey` owns direct USB and
  the preferred-mixer fallback. Keep exact PCM rates/channels/depth, verified hardware
  volume, feedback failure recovery and interface restoration. `native/usbdirect/`
  uses USBFS with no libusb tree; preserve stripped 16 KB-aligned libraries and limited
  JNI exports. No duplicate bit-perfect settings home. Unsupported devices/formats and
  permission refusal retain the existing platform route. See
  [direct USB output](docs/engineering/direct-usb-output.md).
- **Presentation.** Material 3 Expressive is the default style. Each Apple Music option
  changes only its own surface; only the full Apple Music preset changes several at
  once. Album and playlist pages keep the artwork-backed Material hero. Keep all saved
  player styles, their order and meaning, and every setting and search anchor.
- **One UI renderer.** Shared Compose owns every screen on Android, desktop and iOS.
  SwiftUI is only the iOS app/scene shell. The WidgetKit widgets and the CarPlay scene
  are the only system-surface exceptions, and they never render app content. See
  [iOS shell](docs/engineering/ios-swiftui-parity.md).
- **Enhanced lyrics** on Android follow upstream ArchiveTune's renderer, with
  `NatuneLyricsEnhanced` supplying providers and settings. Do not restore custom sweeps
  or scroll springs to it. See [lyrics](docs/lyrics.md).
- **Audio analysis** (authenticity check, acoustic graph, automix features) is opt-in,
  bounded and cancellable. It reports suspicion or unknown, never proof, and never
  changes playback resolution or the queue. ONNX models ship only with `-Pautomix=true`.
- **Removed code stays removed.** Read
  [removed components](docs/REMOVED_UPSTREAM_COMPONENTS.md) before restoring anything
  it lists. Delete live source only with maintainer sign-off.
- **Licensing.** New `.kt` files start with the package line, with no licence banner.
  The KMP modules carry no banners; their lineage is in [credits](docs/CREDITS.md).
  Keep existing banners elsewhere and any third-party notice a licence requires. Use
  Metrolist and other GPL sources as behavior references, not verbatim copies.

## Build and test

Toolchain: JDK 21, Gradle wrapper 9.6.1, AGP 9.2.1, Kotlin 2.4.21, compile/target SDK
37, min SDK 24, NDK 30.0.16248370 (generated details in
[toolchain](docs/engineering/toolchain.md)). Flavors combine `gms|foss`, `mobile|tv`
and an ABI.

```sh
./gradlew assembleGmsMobileUniversalDebug
./gradlew :app:lintGmsMobileUniversalDebug
./gradlew :app:testGmsMobileUniversalDebugUnitTest
```

Cheap checks that need no Gradle, JVM or network:

```sh
python3 scripts/check_repository.py --worktree
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s iosApp/tests -p 'test_*.py'
git diff --check
```

`check_repository.py` validates links in maintained docs, skill frontmatter, the Gradle
tasks above against the workflows, dependency drift, module boundaries and file
budgets. The optional hook is in `.githooks/pre-commit` (see
[quality](docs/engineering/quality.md)).

CI on pull requests to `dev`: `Build Pull Request` (`build`: assemble + lint
GmsMobileUniversalDebug and KMP compile; `desktop-compile`), `Unit tests`
(`Compile and run unit tests`, `Shared KMP and desktop suites`), `iOS compile`
(path-filtered) and `Android emulator QA`. A push to `dev` publishes Nightly; a push
to `main` publishes Stable. `Kotlin quality` and the release candidate are manual.
Workflows under `.github/inherited-workflows/` and `.github/private-workflows/` are
parked and inactive. Debug builds do not prove release shrinking, signing or device
behavior; keep build and test gates separate.

## Dependency gotchas

- `settings.gradle.kts` lists a GCS mirror of Maven Central before `mavenCentral()`.
  Keep that order; Maven Central rate-limits CI.
- JitPack is scoped with `exclusiveContent` to an allow-list of `com.github.*`
  groups. A new JitPack dependency fails until its group is added.
- On a host without the Android SDK (`natune.androidSdk=false`), the Android-only
  modules are skipped; shared and desktop builds still work.
- Upstream has no sync automation. Port changes by reading `4nx3b/dev` or
  `rukamori/dev` directly; never tree-replace a branch, which would restore the
  removed keystore, Python layer and other deliberate divergences.

## Working rules

- Never commit credentials, tokens, keystores, `local.properties` or agent session
  state (`.swarm/`, `.opencode/`, `.jcode/`, scratch notes).
- User-facing strings go in resource XML, never inline.
- Comments explain why, not what. Keep serialized and public legacy names.
- Kotlin naming: types and Compose functions PascalCase, functions and properties
  camelCase, constants UPPER_SNAKE_CASE, packages lowercase.
- Before editing, trace real callers, resources, navigation, manifest entries,
  variants and native entry points; an import search is not enough.
- Never force-push shared branches. Do not push `dev` or `main` directly.
- Report checks you ran, checks that failed and checks you skipped. Missing build or
  device evidence stays unverified. Use the
  [incident runbook](docs/engineering/incident-runbook.md) for playback or release
  failures.
