# ArchiveTune-KMP handoff — 2026-10-27

Repo: `NatuneGroup/ArchiveTune-KMP`. Integration branch: `dev`. Head: `3a104d3`.
Reference (port source of truth): `NatuneGroup/ArchiveTune` branch `dev`, HEAD `b7e4dd444`.

## Repo state

- All open slice PRs merged into `dev`. Last merges: #38 (CI split + emulator QA), #9 draft (sign-in parity: Qobuz, TIDAL PKCE, Deezer web login, iOS cookie capture), #45 (Spotify catalog search).
- Blacksmith CI on `dev` head `3a104d3`: ✅ `build` (GMS mobile universal debug + lint), ✅ `Compile and run unit tests`, ✅ `desktop-compile`.
- Merge work used `git merge-tree` pre-flighting; all conflict resolutions verified for 0 markers before commit.

## What is verified

- Android: Pixel 6 / Android 36 / x86_64 emulator (Linux KVM child session) — cold launch, onboarding pager, Home, Settings render, 0 fatal exceptions. Fix that made it work: `androidResources.enable = true` in `shared-ui/build.gradle.kts` (composeResources were stripped otherwise).
- iOS: iPhone 17 sim / iOS 27 — cold launch + relaunch, root nav, Library/Settings via `archivetune://` deep links.
- Desktop: compiles (`desktop-compile` job); catalogue surfaces fixed in #42.

## NOT verified / known gaps

- Real playback/streaming on any platform (simulators can't reach real sources reliably).
- iOS TLS cert-trust issue on the iOS test VM.
- Telegram login-dependent paths, `tgart:`/`file:` artwork loader.
- Tidal pool 401s expected without pool creds in debug builds.
- GitHub repo secrets were never written (no PAT was provided): `KEYSTORE*` signing secrets, provider keys (TIDAL device/PKCE clients, JioSaavn DES key, pool read key, Telegram api_id/hash, ArchivePool URL), all still empty. Debug builds run with built-in defaults where they exist.
- Listener-presence site (https://natune-listener-presence.vercel.app) is deployed and returns 200; clients use it via the `ARCHIVETUNE_LISTENER_PRESENCE_URL` build value — no action needed unless redeploying.

## Build architecture (for local work)

- JDK 21, Gradle wrapper 9.6.1, compileSdk 37. Modules: `:app` (Android host), `:shared*`, `:desktopApp` (jvmMain), `:iosApp`, plus `core`/`lyrics`/`IconPack`/`morideobfuscator` submodules.
- CI = compile signal: `.github/workflows/build_pull_request.yml` → `build` + `Compile and run unit tests` + `desktop-compile` on `blacksmith-*-ubuntu-2404` runners. `android_emulator_qa.yml` + `scripts/emulator_qa.py` = emulator smoke test (Linux KVM runner only; macOS VM has no HVF).
- `settings.gradle.kts` declares the GCS Maven-Central mirror BEFORE `mavenCentral()` — do not reorder (429s). JitPack is `exclusiveContent`-scoped; new `com.github.*` deps fail until their group is allow-listed.
- Lightweight check: `python3 scripts/check_repository.py --worktree` (no Gradle/JVM/network).

## Termux-safe workflow (no heavy builds locally)

```sh
git clone https://github.com/NatuneGroup/ArchiveTune-KMP && cd ArchiveTune-KMP && git checkout dev
# edit sources; never run ./gradlew assemble* in Termux
python3 scripts/check_repository.py --worktree          # fast static check only
git checkout -b devin/$(date +%s)-<slug> && git commit -am "..." && git push -u origin HEAD
gh pr create --base dev   # blacksmith CI compiles + tests; merge when green
```

To merge an open PR locally instead of GitHub UI: `git fetch origin pull/N/head:pr/N && git merge --no-edit pr/N`, resolve conflicts (`<<<<<<<`/`|||||||`/`=======`/`>>>>>>>` markers — union the sides, iOS conflict resolutions in `3a104d3`'s ancestors show the pattern), then `git push origin dev`.

## Invariants to keep (from AGENTS.md)

- Providers stay separated: Tidal / Deezer / Qobuz / Spotify catalog+streaming / Telegram; YouTube fallback chain `YTPlayerUtils → InnerTubeX → NewPipe → EchoStreamResolver` (Echo last).
- Source/provider logic must match `NatuneGroup/ArchiveTune` `dev` exactly — endpoints, method order, fallbacks — not official APIs.
- Listen Together = vivi JSON + Meowery protobuf only; no koiverse paths.
- Spotify catalog vs streaming = two accounts; streaming never reads catalog token.
- `applicationId moe.rukamori.archivetune` fixed; `Koiverse.jks*`, `ArchiveTuneKoiverseServer.txt`, `DataServer.txt`, committed debug keystore never restored.
- New `.kt` files: no license banner, package line first. User-facing strings in XML resources.
- Metrolist decompile = behavior reference only, no verbatim GPL-3.0 paste.

## Remaining parity work (highest value next)

1. Real playback verification on physical devices (Android + desktop easiest).
2. Telegram artwork loader + login-dependent chat paths.
3. iOS TLS/cert handling so the sim can reach HTTPS sources.
4. Fill GitHub secrets for release signing + provider keys; then a Nightly build exercises the full path.
5. Any unported Spotify import surfaces (see `HANDOFF-spotify-library-source.md` in repo history / child session for the slice-level list).
