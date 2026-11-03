# AGENTS.md — ArchiveTune (vossgraves fork of 4nx3b/ArchiveTune)

Active development is on `dev`, the integration branch (feature PRs land there
first; it builds the Nightly channel, and `main` is Stable). Canary is retired: there
are only the Stable and Nightly channels. The fork tracks both upstreams, `4nx3b/ArchiveTune` and
`rukamori/ArchiveTune`, at `4nx3b/dev` and `rukamori/dev`. Any agent working here
must preserve the invariants below.

## Fork invariants — never break

- **Presentation**: Material 3 Expressive is the default interface style. The player and lyrics three-dot menu is the one default exception: it defaults to Apple Music, with Material 3 as an explicit `player_menu_style` option, and its effective style is the Apple menus surface. An unchosen menu style belongs to the default presentation, so a fresh install still reads Material 3 Expressive; a stored choice counts toward the indicator. Only explicitly applying the overall Apple Music preset coordinates player, library, menus, search field, default accent and tab bar in one atomic edit. Album and playlist detail pages always use the standard artwork-backed Material hero; presets must not replace it. Every individual Apple Music option changes only its own surface and never activates or locks the others; derive the experience indicator from the actual selections, showing Custom / mixed when they differ. Migrate old experience overrides once, then honor independent preferences. Playback providers and source selection remain independent. Compact mini-player and bottom-pill layouts are optional; the Apple Music tab bar itself is always the one-row layout (Library circle, mini player, Search circle). Bottom search and scroll-hidden navigation are optional; preserve settings and search anchors.
- **Tidal source**: `app/src/main/kotlin/moe/rukamori/archivetune/tidal/` (`TidalAudioProvider`, `TidalAccountManager`, `TidalInstanceHealthManager`, `TidalDns`, `TidalArtworkProvider`), Tidal settings/login UI, `utils/tidal/`; instance racing documented in `docs/instance-racing.md`. Live progressive-DASH streams use the `tidal-dash://` scheme routed in `MusicService`.
- **Multi-source audio**: providers live in top-level packages `tidal/`, `deezer/` (DeezerCrypto + Media3 decrypting DataSource), `qobuz/` (+ `QobuzBackupProvider` via the kouzu.in mirror), `spotify/`; shared contract (`DirectStream`, `TitleMatch`, source priority) in `audiosource/`. Playback resolution goes through `resolveMultiSourceDataSpec` in `playback/MusicService.kt`; YouTube is the final fallback. Do not rewire playback around this.
- **Audio authenticity**: decoded PCM analysis is opt-in and bounded. It reports spectral suspicion, never proof of studio provenance; missing, short, silent or inconclusive evidence remains unknown. Evidence uses reported sample rates, never quality-tier guesses, and cached evidence stays source-bound. Observed decoded PCM and Media3 AudioTrack configurations are track-scoped, not measurements of physical device/DAC output; custom bit-perfect output conversion remains unobserved. Preserve bit-perfect bypass and caller cancellation. A rejected attempt must not reuse its cached URL, discard playback position or bypass the established source chain.
- **Compact transitions**: `playback/automix/` supplies bounded local feature analysis to the existing smart-transition path. Standard packages contain neither ONNX models nor ONNX native libraries; `-Pautomix=true` explicitly includes the optional models/runtime. Keep analysis cancellation and source-aware cache bounds; do not add bundled Python.
- **Spotify catalog and Spotify streaming are two accounts**: `spotifycore/` + app `spotify/` provide metadata, artwork, search, playlist import and track-to-YouTube identification on the catalog account (`SpotifySpDcKey`, the global `Spotify.accessToken`). The experimental `AudioSourceType.SPOTIFY` source (ported from 4nx3b: `spotify/SpotifyWebStreamer` web-player/Widevine method, `spotify/echo/` desktop PlayPlay method) runs only on a separate streaming account (`SpotifyStream*` keys, `SpotifyStreamingAccount`) signed in through its own WebView profile. Streaming code must never read the catalog token or cookie for anything but the same-account refusal, never set `Spotify.accessToken`, and must stand down when both sign-ins are the same account. The source sits in `AudioSourceConfig.DEFAULT_ORDER` like every other source (just above YouTube, default off, and enabled only while a streaming account is signed in), goes through `resolveMultiSourceDataSpec` like every other source, and is not a download source. See `docs/spotify-streaming.md`.
- **Playback client policy**: `AutoChoosePlaybackClientKey` + `YTPlayerUtils` own bounded YouTube client selection/fallback and video-scoped PO tokens, and two extractor tiers are live behind them for playback and downloads: YouTube resolves shipped `YTPlayerUtils` → InnerTubeX → NewPipe → `EchoStreamResolver`, via `playback/stream/YouTubeFailsafeChain` (each tier bounded, one `PlaybackDiagnostics` line per tier; see `docs/extraction.md`). The extractor-tier order lives in `ResolveAudioStreamUseCase.shippedFallbackTiers` and nowhere else. Echo stays the very last resort — keep it there. Login/confirmation failures (`InvalidPlaybackLoginContextException`, `LoginRequiredForPlaybackException`) skip every fallback tier. Manual mode must remain available.
- **Telegram streaming**: `telegram/` (TDLib wrapper + `TelegramDataSource`), browse/settings/login UI. Playback routed by the `telegram://` branch in `MusicService`'s `SchemeRoutingDataSource`, independent of the multi-source chain. Channels materialise as local playlists (`LPtg<chatId>`); artwork via the Coil `tgart://` fetcher. api_id/hash via `BuildConfig` with public Telegram Desktop fallback.
- **Pool credentials**: the Playback Pool toggle gates account feeds, Pool discovery, reports and provider-local Pool caches independently of personal credentials. Empty or failed feed attempts remain throttled across restarts. Pool credentials stay in their separate Keystore-encrypted cache and must never overwrite personal sign-in preferences. An APK-delivery key is extractable and must never be reused as a server administrator, session or database secret.
- **Discord presence**: works the way Metrolist's does (`MetrolistGroup/Metrolist`, `music/discord/`), not through the Social SDK binary and not through a user token. PKCE OAuth (scopes `openid identify sdk.social_layer_presence`, redirect `discord-<APP_ID>:/authorize/callback`, registered on the app in the Developer Portal) yields an OAuth access token. `GatewayClient` sends it as `Bearer <token>` in a minimal IDENTIFY (`intents = 0`, no capabilities, gateway v10, no compression) and then op 3 presence updates. Keep IDENTIFY minimal: user-client intents/capabilities stream DMs and friend presence to the phone for nothing. Never use a raw user token (the old Kizzy/self-bot route) and never embed a confidential client secret. Updates are driven by playback only; do not add background Gateway retry loops.
- **CI signing**: release/nightly and release-shaped CI builds sign only when all four GitHub Secrets `KEYSTORE`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD` are configured, using the existing (historical) release keystore — same signing identity as all prior builds, so signed updates keep installing in place. Missing or incomplete signing configuration leaves explicitly unsigned APKs that still build, upload and publish, with a release-note warning; unsigned artifacts do not prove update-over-install compatibility. No workflow may fall back to a committed `app/persistent-debug.keystore`; the file was removed from the tree and must not be restored (the key material remains public in git history — accepted residual risk, maintainer decision 2026-08-26; do NOT rotate to a fresh key without an explicit user-facing migration announcement, it force-reinstalls every user). Local debug builds use AGP's default debug keystore.
- **Listen Together**: LAN plus exactly the public servers listed in `listentogether/ListenTogetherServers.kt` (vivi's JSON "Hugging Face Sync" at `devilmi-vivi-music-listen-together.hf.space` and Metrolist's protobuf-only The Meowery at `metroserverx.meowery.eu`) — nothing else. The retired `vivimusic-listen-together.onrender.com` is never contacted; it is only accepted as an invite-link host so old links still open. No koiverse REST/WS path and no `*.koiverse.cloud` / `raw.githubusercontent.com/koiverse/*` call anywhere in the tree. The Meowery never takes a JSON frame: the codec is pre-negotiated per host, the app sends no `client_capabilities` frame, `buffer_ready` is the catch-up mechanism, and the host has no chat relay (chat is JSON-server-only). Connection and room-request waits are bounded; stale socket callbacks must not alter a newer connection. Server hosting restrictions must be shown, never bypassed.
- **Protected files**: `applicationId` (`moe.rukamori.archivetune`) is fork identity — never adopt upstream changes to it. `Koiverse.jks*`, `ArchiveTuneKoiverseServer.txt`, `DataServer.txt` were removed and must never be restored.

## Modules & submodules

- Gradle modules: `:app :core :spotifycore :canvas :jiosaavn :lastfm :musixmatch :shazamkit :morideobfuscator :lyrics:*`.
- Submodules: `core` → **vossgraves/core** (NewPipeExtractor-based InnerTube client; a fork of rukamori/core that deliberately carries no `NetworkGatekeeper`, pinned on `feat/podcast-port` for the podcast models), `lyrics` → **4nx3b/lyrics**, `IconPack` → **NatuneGroup/IconPack** (fork of rukamori/IconPack; the icon-switcher pack), `morideobfuscator` → rukamori. Commit + push inside a submodule first, then pin the gitlink; never leave a dirty or unpushed pointer. Never move `core` to a `rukamori/core` revision — its `NetworkGatekeeper` defaults to `connectionBlocked = true` and throws on every request.

## Build & test

- JDK 21, Android SDK (compileSdk 37), Gradle wrapper 9.6.1.
- Flavors include gms/foss, mobile/tv and ABI choices; not every combination ships. PR coverage is GMS/mobile/universal Debug assembly (arm64-v8a + x86_64) plus lint. Nightly retains six shipping variants; Stable retains seven and its existing reproducibility job.
- Fork contract tests (`app/src/test/`): `./gradlew :app:testGmsMobileUniversalDebugUnitTest`. Keep `Build Pull Request` / `build` separate from `Unit tests` / `Compile and run unit tests`, running concurrently on the exact integrated head. Do not substitute APK assembly for test compilation. In constrained continuations, no local Gradle/JVM/LSP or per-feature CI: focused tests follow integration.
- Preserve GitHub-hosted PR runners, PR read-only caches and trusted cache producers. Use plain streaming diagnostics and local profiles/reports, not external build scans; do not change heap/KSP/configuration-cache policies without evidence.
- For this constrained readiness mission, run full app builds, lint, coverage, repeated tests and compiler-backed analysis on GitHub-hosted runners. Keep the authorized VPS to lighter checks; do not retry full CI builds there.
- New `.kt` files carry no license header and start with the package line. The KMP
  modules (`shared`, `shared-domain`, `shared-data`, `shared-ui`, `desktopApp`,
  `iosApp`) carry no banners; their lineage and credits are in `docs/CREDITS.md`.
  Outside them, existing banners stay unchanged, and header automation must never
  rewrite existing attribution. Third-party notices a licence requires stay in place.

## Lightweight quality and maintained documentation

- Run `python3 scripts/check_repository.py --worktree` without Gradle/JVM/network
  work. It checks maintained docs/skills, CI command consistency, dependency drift,
  source/module boundaries, file budgets and copyright preservation.
- Install the lightweight staged-content hook as documented in
  [quality gates](docs/engineering/quality.md); preserve an existing user hook setup.
- Kotlin/Java types use PascalCase, functions/properties camelCase, Compose UI
  functions PascalCase, constants UPPER_SNAKE_CASE and packages lowercase.
  Preserve serialized/public legacy names rather than renaming them as cleanup.
- Product knowledge stays in `docs/claude/`; engineering procedures stay in
  `docs/engineering/`; reusable agent workflows stay in `.factory/skills/`.
  Update only affected docs and add tests only for meaningful behavior/regressions.
- Use the [incident runbook](docs/engineering/incident-runbook.md) for playback or
  release failures. Missing build/device evidence must remain explicitly unverified.

## Current local follow-up boundaries

- Stable/Nightly are explicit choices on every build; build identity supplies only the unset default. Migrate stored Canary once without overriding a later Stable choice. Keep channel-bound candidates and no-downgrade checks.
- Settings descriptions use finite automatic marquee only for visible/resumed overflowing text with motion enabled. Keep page snapshots and running startup latches; one shared post-commit Restart now/Later modal owns restart actions, not per-row effects or timers.
- The acoustic graph in Developer (`settings/misc`, `acoustic_quality`) and shared player/song menus runs only after Run. Keep bounded, cancellable, source-bound memory-only results, ordinary seekable file/content and clear current-source support, stale-result rejection and heuristic/unknown language. Never mutate playback resolution, queue or source rejection settings for an audit.
- Existing DSP maintenance pins are Oboe 1.11.0, Signalsmith Linear 0.6.5 and retained libusb 1.0.30; Stretch remains 1.4.0 and soxr 0.1.3. No new output engine/default/preset policy. libusb/soxr are documented Android subsets; retained licenses/headers and Android build closure matter more than standalone upstream tooling.
- The unreachable decent USB module is removed, not the active bit-perfect/AAudio/LastWave routes. Keep its MIT license/credit, optional app USB-host feature and persisted DSP/preset formats.
- Unicode/phonetics, SimpMusic parity, AI/cache and lifecycle corrections are locally integrated. Preserve semantic joiners/raw text, conservative script-family exclusions, aligned provider words or honest line fallback, full-settings AI eligibility and changed-target invalidation on every translation surface. DeepL is translation-only; failed/partial AI responses are not success caches. Downloader staging is request-owned, delayed room retries are generation-bound, and Spotify account mutations share the refresh lock.
- Japanese data uses one persisted best-effort foreground consideration for primary system-device locale region JP. Wi-Fi/cellular are allowed; offline consumes the consideration but manual retry remains. Explicit off/removal prevents automatic reinstall; no IP lookup, continuous watcher or startup wait. Keep pinned data/digest, IO validation and manual install/cancel/remove.
- The earlier release is complete: PR #198 merged at `643a35fd09888769a4a2d588324398d68f0d6fb5` and published Nightly `N202610151104` / build 6833 after separate green build/tests (835 passed, 0 failed, 0 skipped). Inherited permissive lint was not lint-clean. No device QA is claimed; Canary stays frozen.
- The current YouTube follow-up is local, uncompiled source; focused tests, independent changed-path review and exact-head remote BUILD/TESTS are pending. Ordinary playlists use finite browse queues, occurrence-aware persistence and conservative unknown legacy recovery; strict whole-list completion gates insertion/import/sync and complete-list client shuffle (not exact server shuffle). Unknown/failed tails cannot seed radio. Preserve manual additions, repeat/filter/source policies and explicit RD/Mix/radio behavior.
- Login-only Music install recovery makes one guarded same-WebView official HTTPS return for the observed embedded `net::ERR_UNKNOWN_URL_SCHEME`, not a proven renderer crash. Preserve DOM storage/current jar; logout is YouTube-scoped. Keep candidates memory-only through private reusable-client final-context verification, then atomically commit owned identity/context. Stale attempts/refreshes must not overwrite newer sessions; no global playback-client staging or account-slot migration.
- InnerTubeX is the anonymous MetrolistGroup `v0.11.2` pin on every host, source `c863ddf15be01a1abfa4630329bdc0a1e95f1531`, GPL-3.0 unchanged, not the login/pagination fix. Its cipher is the pure-Kotlin QuackJS library; the anonymous tier keeps `UnavailableTokenProvider`, direct-audio capability guards and terminal auth errors. During this constrained follow-up gitlinks stay uninitialized/unchanged; no local JVM/build/device QA, per-feature CI or unrelated cleanup.

## Dependency gotchas

- `settings.gradle.kts` declares a GCS mirror of Maven Central **before** `mavenCentral()` — do not reorder (Maven Central 429-rate-limits CI).
- JitPack is scoped via `exclusiveContent` to an allow-list of `com.github.*` groups (TeamNewPipe, PRDownloader, jaudiotagger, MetrolistGroup…). A new `com.github.*` dependency fails until its group is added.
- The embedded Python/yt-dlp layer (Chaquopy) was removed on 2026-08-26: no Python is bundled, and nothing in the resolution path may grow a dependency on it. YouTube stream resolution runs in-process through the compiled InnerTube core in `YTPlayerUtils` (BotGuard/QuickJS PO tokens), then the InnerTubeX and NewPipe tiers, with `EchoStreamResolver` the last resort (`docs/extraction.md`). The external YTDLnis yt-dlp plugin APK is the only yt-dlp route, and never bundled. Upstream still carries Chaquopy — expect merge conflicts in `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `playback/stream/*` on sync; resolve them by keeping the fork's Python-free shape.

## Upstream sync (state as of 2026-09-27)

- **There is no automation.** `upstream-sync.yml`, `upstream-sync-merge.yml`, `mirror-4nx3b.yml` and the scripts they drove (`scripts/upstream_sync.sh`, `scripts/ai_resolve.py`, `docs/UPSTREAM_SYNC.md`) were deleted in 2026-09 (commit `699d1e390` and the mirror removal), so nothing merges or mirrors branches. The unused `SYNC_PAT`, `AI_API_KEY` and `API_BEARER_TOKEN` secrets were deleted from the repository; the in-app updater needs no key.
- Upstream changes land by **manual comparison**: the rukamori line shares no merge base, so read the upstream tree directly (`git show 4nx3b/dev:<path>`, `git show rukamori/dev:<path>`) and port the behaviour onto `dev`'s current code instead of pasting files over it.
- If any automation is ever put back, it must not tree-replace a branch: that would re-commit `app/persistent-debug.keystore` (still present in 4nx3b's tree), resurrect main's committed-keystore signing fallback, and wipe every deliberate fork divergence (yt-dlp removal, keystore removal, UI rework). Direction decision (merge-based 4nx3b sync vs release-tag mirroring only) is pending with the maintainer. When a fork feature or protected file changes, update the invariants in this file — it is the only description of sync policy left.

## Agent hygiene

- Never commit credentials, tokens, keystores or agent session state (`.swarm/`, `.opencode/`, `.jcode/` stay untracked).
- Local working notes (`ARCHIVETUNE_SESSION_NOTES.md`, `ARCHIVETUNE_CODE_MAP.md`) are scratch memory — never ship them.

## Private KMP workspace guidance (2026-10-24)

These instructions supersede conflicting historical public CI/branch and
constrained-checkout instructions above, in `CLAUDE.md`, and in inherited docs
**only for this private workspace**. Original source guidance and all application/
fork invariants remain unchanged.

- **Current:** destination **NatuneGroup/Natune** is private. Remote `main`
  and `dev` both point at the independent parentless foundation
  `c047a2002a7c35eb9ed30b6a8eec72951f7f2f48` (observed 2026-10-25). Local `dev` holds
  the port history rooted at the approved baseline (NatuneGroup/ArchiveTune `dev`
  `b7e4dd444fea202a5c32e96f7b1199a74fe3ae70`) and merges the foundation in a local
  merge commit that keeps both histories. Nothing from this workspace has been pushed,
  and local `main` is unchanged. The manual-dispatch `readiness.yml` is the only active
  workflow; the 13 inherited workflows stay quarantined. Focused local compile covers
  the three shared modules and `:desktopApp`; local tests cover the three shared
  modules only.
  Earlier local checks rendered the desktop window under Xvfb `:99`. Android, iOS, CI
  and signing/upgrade results are not validated. The full KMP port is not implemented;
  see [baseline provenance](docs/engineering/kmp-baseline.md).
- **Remote foundation constraints (merged 2026-10-25):**
  - The foundation's `:shared` module is not in `settings.gradle.kts`. Its files stay
    under `shared/`, and its build script is renamed to `shared/build.gradle.kts.reference`
    so it is neither a live Gradle module nor a checked build file.
  - Its `PlaybackSnapshot` clock differs materially from `:shared-domain`: it has a 1 s
    extrapolation cap, returns null on epoch mismatch, gates on visibility and saturates
    on overflow. The approved `:shared-domain` contract stays active. Reconciling the two
    is an orchestrator decision; neither is silently replaced.
  - Its manual matrix readiness workflow is parked as
    `.github/inherited-workflows/foundation-readiness.yml`, not activated. Its desktop
    `nativeDistributions` settings (Deb, Msi, Dmg; package `Natune`, version
    0.1.0) are not in the port's `desktopApp` build. M8 and M10 decide packaging.
  - Foundation CI runs (Linux `40152191476` and Windows `40152422286` passed; macOS
    `40152783751` failed) tested the foundation only. They are not port coverage.
  - Foundation guidance says "Only genuinely independent secret-free source is eligible
    for this repository." The inherited port history does not meet that wording. The
    merge is a policy conflict for the owner and security reviewer, not a waiver.
  - Foundation rules still in force: no fake clocks, no-op parity or advancing synthetic
    engine stand-ins; native-host builds are not physical audio, peripheral, account,
    signing or upgrade QA; no dead code is confirmed, and characterization stays distinct
    from bug fixes; do not suppress Kotlin metadata checks.
  - Superseded foundation settings: its 768 MiB Kotlin daemon ceiling and daemon-off,
    one-worker properties give way to the mission's 1536 MiB caps and the command-line
    `--max-workers=1 --no-daemon` flags. Its product invariants still apply: providers,
    library, downloads, podcasts, Telegram, DSP, USB, bit-perfect, transitions, social
    features, Listen Together, scrobbling, backup and Android system integrations. The
    full foundation text stays in commit `c047a20`.
  - No source deletion. New Kotlin starts with the package line, without a banner. User-visible strings belong in
    Compose resource XML.
- **Planned after legitimate gate resolution:** `main` (Stable/default) and `dev`
  (integration). Remote `main` and `dev` already exist at the foundation commit, not at
  the approved dev-derived snapshot, so establishing port-based branches needs separate
  authorization. Restore/adapt the original YAML workflow set and useful coverage for the
  private repo, including independent exact-head BUILD and TESTS. Do not import source
  main.
- Workflow quarantine in `.github/inherited-workflows/` is **temporary bootstrap
  safety**, not the final CI scope. All inherited source files remain unchanged.
  No workflow is moved/enabled/dispatched by this documentation update; do not
  newly activate the unused Telethon deployment helper or public/external
  notifications. Future publication/assets/feed links must be private-safe.
- The denied import requires legitimate human/security disposition. Do not retry
  unchanged, disable/bypass scanning, use another history/transport/upload path,
  automatically waive findings, or remove required provider features to get past it.
  A user merge approval is not a security waiver. The sync to private `dev` is a
  normal fast-forward from the then-current remote head, never a force-push, and waits
  for that disposition. Never push this work to public NatuneGroup/ArchiveTune or
  change its protected gates.
- **Pending upgrade goal:** retain historical release signing material through
  authorized secret configuration using `KEYSTORE`, `KEY_ALIAS`,
  `KEYSTORE_PASSWORD`, `KEY_PASSWORD`. Presence/material/certificate are unverified;
  do not read values, generate/rotate a key, recover removed-history keys or restore
  a committed keystore. No secret access or signing occurs in this update.
- Same signer alone is insufficient: keep `moe.rukamori.archivetune`, compatible
  certificate/legitimate lineage, schema/legacy queue/settings/files, and a greater
  versionCode. Later use one absolute increasing code above a trusted upgrade
  floor across signed workflows, not fallback 1500, new-root count or per-workflow
  run numbers. Debug's `.debug` package cannot prove update-over-install. Verify
  release-shaped installation without uninstall, clearing or downgrade flags.
- Authorized manual private APK download suffices for the requested upgrade test.
  No new GitHub-token updater, embedded PAT, credential-cloud service or server is
  automatically authorized. Preserve updater/source/account/provider features and
  no-downgrade behavior; do not turn planning suggestions into new requirements.
- The user reports **3,000 free equivalent Blacksmith minutes remaining** and the
  app installed for all organization repositories. **No paid overage is authorized**;
  this is not a verified provider-enforced billing cap. No CI is run in this update.
- Later heavy checks are orchestrator-controlled **coherent batches**, never a
  Blacksmith run for each small edit. Use high/max CPU, serialized reserved free
  batches, short whole-job timeouts, headroom and actual-duration reconciliation.
  Documented maxima: Linux/Windows 32 vCPU, macOS 12 vCPU; equivalent-minute
  multipliers: Linux x64 16x, Windows 32x, macOS 40x. Private security/compute/storage
  access must not silently enable paid features or claim unavailable checks green.
- No additional routine questions while the user sleeps. Stop at human-only gates
  and report gaps rather than inventing success or weakening approved scope.
- No local full JVM/Gradle builds, lint, tests or unbounded semantic indexing.
  Do not install hooks, alter global configuration, or mutate the offered VPS.
- The four submodules are initialized at the unchanged baseline gitlinks in this
  isolated workspace; this overrides the old uninitialized-checkout note.
  Use their existing public origins read-only. No child pushes or pin changes.
- Before implementation edits, trace actual callers, resource/navigation/manifest,
  variant, generated, reflection/serialization and native entry points as applicable,
  plus downstream behavior. An import search alone is insufficient.
- Preserve signing/application identity, protected-file restrictions, separate
  catalog/streaming accounts, source gates, database/schema/legacy readers, storage
  formats, all settings and menus, all 17 saved player-style meanings/order/selections,
  V4 player default, Material 3 Expressive interface default and liquid glass.
  Licenses and third-party credits remain intact; KMP-module banners are replaced by
  the lineage in `docs/CREDITS.md`, and banners outside the KMP modules stay.
- The approved full feature-preserving shared domain/data/UI and native-adapter
  port retains the Android host, adds Metrolist as another style and improves
  Enhanced lyrics. These are future deliverables, not completed setup outcomes.
