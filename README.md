<div align="center">

  <img src="assets/ic_launcher-playstore.png" width="160" height="160" alt="Natune app icon">

  <h1>Natune</h1>

  <p><strong>A Kotlin Multiplatform music player for Android, Windows, Linux, macOS, and iOS.</strong></p>

  <p><a href="README.md">English</a> · <a href="README_JA.md">日本語</a></p>
</div>

## Project overview

Natune (formerly ArchiveTune) is a music-player project targeting Android, Windows, Linux, macOS, and iOS. This repository contains the Kotlin Multiplatform port of the existing Android application. The port and platform integrations are incomplete; the current implementation state and outstanding work are recorded at the end of this document.

The visible app name is Natune. Existing Android application, iOS bundle, Kotlin package, and persistent-data identities are retained for compatibility.

## Existing Android feature set

The following describes the Android product that this port preserves; it is not a claim of feature parity across the KMP targets.

- Playback supports multiple accounts, background listening, local files, playlists, liked songs, and subscriptions. Ordered audio-source selection includes Tidal, Qobuz, Deezer, Amazon, QQ Music, JioSaavn, Telegram, and other providers, with YouTube as the final fallback. Disabled sources are skipped for playback, prefetch, crossfade, Cast, and downloads.
- Audio features include EBU R128 loudness normalization, tempo, pitch and speed controls, crossfade and automix, system equalizer integration, and spatial audio integration.
- Lyrics and discovery include synced lyrics, word-by-word karaoke, TTML, translation, AI translation, romanization, music recognition, and listening statistics.
- Account and social features include Spotify playlist import, YouTube Music account integration, Last.fm scrobbling, ListenBrainz history sync, and Discord rich presence.
- The interface uses Material 3, album-art dynamic colors, 17 player styles, 8 player-background styles, liquid-glass surfaces, predictive back, system animation settings, responsive layouts, and playback/interface customization.

## Android distribution

Android Stable and Nightly releases are published by the public [Natune Android project](https://github.com/NatuneGroup/ArchiveTune/releases). This KMP repository has no verified KMP release.

- Android Stable downloads are also listed on [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/NatuneGroup/ArchiveTune/), [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/moe.rukamori.archivetune), [OpenAPK](https://www.openapk.net/archivetune/moe.rukamori.archivetune/), and [Unclouded](https://unclouded.app/apps/archivetune/).
- [GitHub Stable releases](https://github.com/NatuneGroup/ArchiveTune/releases/latest) and [Nightly pre-releases](https://github.com/NatuneGroup/ArchiveTune/releases) are available from the public Android project.
- **Stable** is built from `main` and published as regular releases.
- **Nightly** is built from `dev` and published as `N`-tagged pre-releases.
- Stable and Nightly use the same signing key. The former Canary channel is retired; final Canary `C202610141722` shipped at `a1a04ea02`, the `canary` branch is frozen, and stored Canary selections migrate to Nightly.
- The in-app updater reads `stable.json` or `nightly.json` from the `update-feed` release first. It does not need a GitHub key for the manifest and falls back to the GitHub API only when the manifest is unavailable.

> [!IMPORTANT]
> **Geographic availability:** If YouTube Music is not supported in a region, a VPN or proxy set to a supported region is required for initial data fetching.

Use the official download sources above; other download sources are not verified by the project.

### Historical Android release result

PR #198 was merged at `643a35fd09888769a4a2d588324398d68f0d6fb5` and shipped as Nightly **N202610151104 / build 6833**. The exact-head Android build and unit-test gates reported **835 passed, 0 failed, 0 skipped**. Inherited lint reported 318 errors, 745 warnings, and 43 hints; it was not lint-clean. No device QA was claimed. These historical Android results do not validate the KMP port or later source changes.

### Android features delivered in N202610151104

- Stable and Nightly remain explicit user choices on every build; build identity supplies only the unset default. Offers retain their channel association and require a known newer version code.
- Visible overflowing settings descriptions scroll for finite passes, respecting lifecycle and reduced-motion settings. Restart-scoped edits share a **Restart now / Later** dialog after persistence; Later never restarts automatically.
- The acoustic heat graph is available in Developer settings and shared player/song menus. Opening it does not analyze audio; Run/Cancel/Retry owns a bounded, source-bound excerpt. Seekable local file/content, supported clear current streams, and complete source-bound cache are supported; DRM or inaccessible inputs can be unavailable. Spectral suspicion is heuristic, not proof of original losslessness or physical DAC output.
- The 2026-11 DSP passes removed the unreachable parts of the Tryptify stack (the libusb USB Audio Class driver, the Signalsmith/Stretch pitch shifter, the varispeed stage nothing enabled, the unused USB router helpers and the orphan USB-pin diagnostics) and then the engine layer itself, once one shared DSP chain took the standard route: the Tryptify bus and its native target, the LastWave engine with vendored soxr, and the float-DSP stage. The one remaining DSP pin is Oboe 1.11.0; no new engine or default audio policy was added. Active bit-perfect and AAudio-exclusive USB output remain; the earlier-removed decent USB module keeps its MIT license.
- Lyrics changes preserve semantic Unicode, use conservative script-family exclusions and metadata/kana-aware phonetics, and give SimpMusic the shared built-in/provider/AI policy with shaped, source-spaced timed text. Unaligned readings fall back to a line rather than invented word timing; this is not exact language detection or universal pronunciation.
- AI translation accepts complete validated results, keeps failures retryable, and binds caches/results to settings and the current lyrics target. DeepL has explicit target codes and does not claim romanization support. Active translation surfaces invalidate changed targets; automatic calls retain exclusion gates. Downloader requests own separate staging/latches; Listen Together retries retain connection/request identity; Spotify refresh/sign-in/sign-out serialize account mutation.
- Japanese dictionary automation uses primary system-device locale region JP, never IP geolocation. One persisted best-effort foreground consideration may download over Wi-Fi or cellular; offline consumes that consideration, with manual retry available. Explicit off/removal prevents automatic installation. Download/checksum/tokenizer work stays off the startup/render thread; the pinned 13,343,016-byte IPADIC pack is optional offline-romaji data, not UI translation or a universal language engine.

The release includes focused production-tokenizer/IPADIC fixtures. No device, visual, USB, or all-language validation was claimed.

### Android source follow-up (uncompiled and unreleased)

The current YouTube follow-up is local source and remains uncompiled and unreleased.

- Ordinary playlists use a finite browse queue distinct from RD/Mix and explicit radio. Paging preserves entry occurrences, selected position, manual additions, and existing filtering/repeat policies. Recommendations require confirmed exhaustion; missing legacy state or a failed page cannot silently become radio.
- Whole-playlist add/play-next/import/sync operations require a strictly complete list before insertion or replacement. Failed, stalled, or bounded loading is incomplete, not partial success. Ordinary shuffle loads the complete finite list and uses client-side shuffle; it does not claim the exact server shuffle sequence.
- Login recovery addresses the observed embedded `net::ERR_UNKNOWN_URL_SCHEME` for a Music store intent, not a proven renderer crash. Narrow official targets get one guarded HTTPS return in the same WebView. Candidate account context remains private until verification succeeds; only a current verified attempt commits it, and stale callbacks cannot overwrite a newer session. Logout remains YouTube-scoped.
- The anonymous MetrolistGroup InnerTubeX `v0.11.2` source pin is `c863ddf15be01a1abfa4630329bdc0a1e95f1531`. It is separate from the playlist and login changes. Manual primary-client choice and the `YTPlayerUtils → InnerTubeX → NewPipe → Echo` fallback order remain; no personal/pool cookie bridge or new HLS/SABR/bounded-range transport is enabled.
- A prior README checkpoint recorded 113 newly authored test methods. The later review-fix integration checkpoint records 134 focused methods (121 original plus 13 regressions); these are authored counts, not passing results, and remain locally unexecuted. Five confirmed source-review corrections await targeted recheck and separate exact-head remote BUILD/TESTS. No live Google login, YouTube page-envelope, rendered WebView, or audible playback validation is claimed. Historical green results do not establish new success.

## Development and verification

The documented Android environment uses JDK 21, Android compile SDK 37, NDK `30.0.16248370`, the checked-in Gradle 9.6.1 wrapper, and recursive submodules. Android build/lint and unit-test commands are:

```sh
./gradlew assembleGmsMobileUniversalDebug lintGmsMobileUniversalDebug
./gradlew :app:testGmsMobileUniversalDebugUnitTest
```

KMP desktop run and shared-module verification commands are listed in the port-status section below. **Verification for the current code-only phase is deferred by user direction (`deferred_by_user`).** No build, test, lint, repository checker, app launch, screenshot, signing, or upgrade check was run for this branding change. This deferral applies to this constrained phase, not to ordinary contributor setup.

## Credits

- **InnerTune** by Zion Huang: [InnerTune](https://github.com/z-huang/InnerTune).
- **Metrolist** by Mostafa Alagamy: [Metrolist](https://github.com/mostafaalagamy/Metrolist).
- **ArchiveTune** by Rukamori: [ArchiveTune](https://github.com/rukamori/ArchiveTune), the original project this fork builds on.
- **SimpMusic** by [maxrave-dev](https://github.com/maxrave-dev/SimpMusic) for the lyrics API provider.
- **4nx3b ArchiveTune** for the upstream base and the Bini, Genius, LRCRed, and Megalobiz lyrics providers.
- **SukiSU-Ultra** (GPL-3.0), [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix) (Apache-2.0) and [Kyant backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0) for the liquid glass navigation bar, the About page background and the glass rendering library.
- **Clash-Projects LastWave-Native** for the bit-perfect USB output and Last.fm dashboard.
- **Marcelo Silva** for the formerly vendored decent-usb-audio-driver (MIT); its license and attribution are retained.
- **Google Oboe** (Apache-2.0) remains pinned at 1.11.0; the LastWave engine that linked it was removed with its Tryptify and soxr companions.
- [echo-spotify-extension](https://github.com/brahmkshatriya/echo-spotify-extension) for reference on reading Spotify's recently played list.
- [BetterLyrics](https://better-lyrics.boidu.dev/), [Material Color Utilities](https://github.com/material-foundation/material-color-utilities), [Read You](https://github.com/Ashinch/ReadYou), [Seal](https://github.com/JunkFood02/Seal), translators, beta testers, contributors, and community members.

See [docs/CREDITS.md](docs/CREDITS.md) for per-project attribution, licenses, and file-level details.

## Privacy and licensing

- [Privacy information](PRIVACY.md)
- [Build and contribution guide](CONTRIBUTING.md)
- [Public project support](https://github.com/NatuneGroup/ArchiveTune/issues)
- [Telegram](https://t.me/ArchiveTuneGC) · [Discord](https://discord.gg/XF2fpb9rTq)
- [Localization project](https://translate.codeberg.org/engage/archivetune/)

Natune is an independent third-party client. It is not affiliated with Google LLC or YouTube and does not bypass YouTube's technical protections. The source is licensed under GPLv3; see [LICENSE](LICENSE) for the terms.

Copyright © vossgraves / Natune and contributors; earlier portions © their respective authors (see docs/CREDITS.md)


## Private KMP work in progress

This NatuneGroup/Natune workspace starts from
NatuneGroup/ArchiveTune `dev` commit
`b7e4dd444fea202a5c32e96f7b1199a74fe3ae70`. It remains private until the
official Metrolist KMP release. Recovered Metrolist builds are reference material,
not possession of the exact original KMP source. Metrolist and all inherited
contributors retain their credit, copyright and licenses.

**Current (code-only integration, unverified):** the private remote's `main` and `dev`
both point at an independent, parentless KMP foundation,
`c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`. Local `dev` merges that foundation with the
port history in a local merge commit that keeps both histories. Nothing has been pushed,
so remote `dev` and `main` are unchanged. The foundation's `:shared` module is preserved
under `shared/` but is not in the active build; the approved `:shared-domain` clock
contract stays active. The shared Compose root `ArchiveTuneRoot` takes a complete
`ArchiveTuneHostBindings` set. The desktop entry point (`desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/Main.kt`)
opens one `DesktopSessionGraph` after its single-instance lock: the ordinary settings file and
the platform credential vault behind one `VaultSettingsStore`, one provider account store, one
portable database v5, the library, artist-block and listening-history repositories, one playback
port and player, one queue owner with its checkpoint store, and one listen-history driver. It
mounts `ArchiveTuneRoot` over that graph through `DesktopHostBindings`. Window visibility and
resume feed the root's inputs; hiding the window never pauses audio. Closing the window hides it to
the tray only when the observed hide-to-tray preference is on and a tray is available; otherwise the
close exits. The library and playlist pages, with liked songs and My Top reached through their
routes, deep links and the Library's More menu, open from the shell over the graph's own library,
block and listen owners, and Alt+Left goes back. The Playlists create dialog saves a new local
playlist through the library owner and opens it when the save completes. Their rows play,
play next, add to queue, or start a shuffled copy of a list in the one queue, and the player's
previous, next, repeat, seek and like controls drive the same graph; like reads and writes the owner's
liked list for the current song. Audio sources resolve through the
ordered chain. The public Qobuz backup mirror is composed over one HTTP client and stays off until
it is enabled in settings. Every other source answers as unavailable until its account or decoder
owner is connected. YouTube and local files are not connected, and a queue load that no source
answers fails with the attempts it made. Item, news and player artwork load over that same client as public http or https images, sent with no cookie or account header, and a failed or oversized image keeps its placeholder frame. Not yet connected on desktop: the Library mixed section's shortcut cards, spotlight, recent songs, top mixes and Spotify rails (it lists playlists, saved artists and saved albums), the queue shuffle toggle,
the volume control, custom background images, song-menu rows other than play next and add to
queue, the desktop settings row for the hide-to-tray preference, the native sign-in and Telegram owners, Listen Together,
Last.fm, Drive backup, and custom artwork. The iOS entry point still calls the host-pending
placeholder root, because its durable settings store and native owners are not integrated yet.
No sample or stub owner stands in for them.

Desktop run, not executed in this phase: `./gradlew :desktopApp:run --max-workers=1 --no-daemon`
with JDK 21. Session data lives in `~/.archivetune/desktop`; a JVM started directly can point it
elsewhere with `-Darchivetune.instance.directory=<dir>`. Credentials are written only to the platform
credential store (Secret Service on Linux, the keychain on macOS, DPAPI on Windows), never to
plaintext. The window shows the unavailable boundary when a stored credential reference exists and
the store cannot return it. With no stored credential the graph still opens, and a credential write
that needs the store fails explicitly.

The foundation's manual readiness workflow is parked in
`.github/inherited-workflows/`. The only active workflow is the manual-dispatch
`.github/workflows/readiness.yml`; the 13 inherited workflows stay quarantined. The port's
11 adapted delivery workflows are staged inert in `.github/private-workflows/`; they move
into `.github/workflows/` only after the import gate clears and final CI is authorized.
The iOS shared-data cinterop, the static `ArchiveTuneShared` framework export, the iOS
quickjs-kt dependency and the iOS native closure producer are source-present. The desktop
and iOS roots still wait for the owner bindings named above. The first push waits for
legitimate human/security disposition of the import security gate.

**Verification is deferred by user direction.** Nothing in the integrated source has been
compiled, tested, linted, launched, screenshotted, signed or upgraded in the code-only
phase. Android, iOS, CI, signing and in-place upgrade results are unverified. Foundation
CI results test the foundation alone and are not port evidence. Inherited Android
instructions and historical results above are not current port proof.

The iOS native closure producer, `scripts/build_ios_native_core.sh`, is source only. It
refuses to run anywhere except macOS, and it has never built an archive. Its output
(`shared-data/build/ios-native/<iphoneos|iphonesimulator>/lib`) is UNVERIFIED until it runs
on an Apple Silicon Mac with Xcode 26.4; see [iOS source status](docs/engineering/ios-status.md).
The in-place upgrade verifier `scripts/verify_inplace_upgrade.py` (UPGRADE-001..005) and the
release measurement tool `scripts/measure_release_and_perf.py` are source only too. Each is
evidence only when it runs in the final phase against authorized inputs.

When verification is authorized, the documented checks are
`./gradlew :shared-domain:allTests :shared-data:jvmTest :shared-ui:desktopTest --max-workers=1 --no-daemon`
and `python3 scripts/check_repository.py --worktree`, run with JDK 21. The iOS producer runs
on macOS as `scripts/build_ios_native_core.sh [iphoneos|iphonesimulator|all]`. Credits and
notices for the Japanese romanization data, the CC-BY-SA-4.0 icon pack, Metrolist KMP and
kotlin-footguns are listed in [docs/CREDITS.md](docs/CREDITS.md).

**Planned after legitimate gate resolution:** `main` (Stable/default) and `dev`
(integration), with equivalent private-safe original CI/workflow behavior. Changing
remote `main`, or creating port-based branches, needs separate authorization. Retain the
historical release signer and package identity for a verified in-place upgrade with a
greater versionCode and preserved data, without uninstall/clearing/downgrade flags. This
remains pending; a Debug `.debug` APK or matching signer alone is not upgrade proof.
Authorized manual private APK download suffices for that test; no new token-updater or
cloud service is implied. See [baseline provenance](docs/engineering/kmp-baseline.md)
and the private operational guidance in [AGENTS.md](AGENTS.md).
