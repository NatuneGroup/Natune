<div align="center">

  <img src="assets/ic_launcher-playstore.png" width="160" height="160" alt="Natune app icon">

  <h1>Natune</h1>

  <p><strong>A Kotlin Multiplatform music player for Android, Windows, Linux, macOS and iOS.</strong></p>

  <p><a href="README.md">English</a> · <a href="README_JA.md">日本語</a></p>
</div>

Natune (formerly ArchiveTune) plays music from Tidal, Qobuz, Deezer, Amazon, QQ Music,
JioSaavn, Telegram and local files, with YouTube Music as the final fallback.

## Features

- **Playback:** an ordered source chain you can reorder and switch off per source, multiple accounts, downloads, background play and Cast.
- **Audio:** EBU R128 loudness normalization, crossfade and automix, tempo, pitch and speed, equalizer, and bit-perfect USB output.
- **Lyrics:** synced and word-by-word lyrics from many providers, with translation and romanization.
- **Library and social:** Spotify playlist import, YouTube Music account sync, Last.fm and ListenBrainz, Discord presence and Listen Together.
- **Interface:** Material 3 with album-art colors, 17 player styles and a customizable layout.

## Platforms

| Platform | Status |
|---|---|
| Android | The full app, with Stable and Nightly releases. |
| Windows, Linux, macOS | In progress: runs the shared UI, with fewer features than Android. |
| iOS | In progress. |

The Android screens are moving into the shared Kotlin Multiplatform modules, so every platform will run the same app.

## Download

Android builds are published from the [ArchiveTune repository](https://github.com/NatuneGroup/ArchiveTune/releases):
**Stable** from `main` and **Nightly** pre-releases from `dev`. Both use the same signing key, and the in-app updater follows the channel you choose.
Stable is also listed on [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/NatuneGroup/ArchiveTune/),
[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/moe.rukamori.archivetune),
[OpenAPK](https://www.openapk.net/archivetune/moe.rukamori.archivetune/) and
[Unclouded](https://unclouded.app/apps/archivetune/). Other download sources are not verified.

> [!IMPORTANT]
> Where YouTube Music is unavailable, the first data fetch needs a VPN or proxy in a supported region.

## Building

You need JDK 21 and the submodules (`git clone --recursive`). Android also needs compile SDK 37 and NDK `30.0.16248370`.

```sh
./gradlew assembleGmsMobileUniversalDebug                                   # Android debug APK
./gradlew :app:testGmsMobileUniversalDebugUnitTest                          # Android unit tests
./gradlew :desktopApp:run                                                   # desktop app
./gradlew :shared-domain:allTests :shared-data:jvmTest :shared-ui:desktopTest  # shared modules
python3 scripts/check_repository.py --worktree                              # fast static checks
```

[CONTRIBUTING.md](CONTRIBUTING.md) has the full guide.

## Credits

Natune builds on [InnerTune](https://github.com/z-huang/InnerTune) by Zion Huang,
[Metrolist](https://github.com/mostafaalagamy/Metrolist) by Mostafa Alagamy,
[ArchiveTune](https://github.com/rukamori/ArchiveTune) by Rukamori and the 4nx3b ArchiveTune fork.
It also uses or learns from [SimpMusic](https://github.com/maxrave-dev/SimpMusic),
[BetterLyrics](https://better-lyrics.boidu.dev/),
[Material Color Utilities](https://github.com/material-foundation/material-color-utilities),
[Read You](https://github.com/Ashinch/ReadYou), [Seal](https://github.com/JunkFood02/Seal),
and the work of translators, testers and contributors.
[docs/CREDITS.md](docs/CREDITS.md) lists every project with its license.

## License and privacy

Natune is licensed under GPL-3.0; see [LICENSE](LICENSE).
[Privacy](PRIVACY.md) · [Issues](https://github.com/NatuneGroup/ArchiveTune/issues) ·
[Telegram](https://t.me/ArchiveTuneGC) · [Discord](https://discord.gg/XF2fpb9rTq) ·
[Translate](https://translate.codeberg.org/engage/archivetune/)

Natune is an independent client. It is not affiliated with Google LLC or YouTube and does not bypass YouTube's technical protections.

Copyright © vossgraves / Natune and contributors; earlier portions © their respective authors (see [docs/CREDITS.md](docs/CREDITS.md)).
