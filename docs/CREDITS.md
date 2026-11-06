# Credits

Natune is the Kotlin Multiplatform port of ArchiveTune. It is free software under the
GNU General Public License, version 3 (see the root `LICENSE`). Everything below is
GPL-3.0 unless stated otherwise.

## Project lineage

Each project below is a fork of, or is built on, the one before it.

| Project | Author | Copyright | Licence |
| --- | --- | --- | --- |
| [InnerTune](https://github.com/z-huang/InnerTune) | [Zion Huang](https://github.com/z-huang) | Copyright © Zion Huang and InnerTune contributors | GPL-3.0 |
| [Metrolist](https://github.com/mostafaalagamy/Metrolist) | [Mostafa Alagamy](https://github.com/mostafaalagamy) | Copyright © Mostafa Alagamy and Metrolist contributors | GPL-3.0 |
| [ArchiveTune](https://github.com/rukamori/ArchiveTune) | [Rukamori](https://github.com/rukamori) | Copyright © 2024–2026 Rukamori and ArchiveTune contributors | GPL-3.0 |

- **InnerTune** is the original YouTube Music client that Metrolist was forked from.
- **Metrolist** is the base framework ArchiveTune builds on.
- **ArchiveTune** is the Android application this repository carries in `app/` and the
  library modules: player, library, queue, playback service and settings shell.

## Kotlin Multiplatform port

| Project | Author | Copyright | Licence |
| --- | --- | --- | --- |
| Natune (ArchiveTune KMP) | [vossgraves](https://github.com/vossgraves) / [NatuneGroup](https://github.com/NatuneGroup) | Copyright © 2026 vossgraves / Natune and contributors | GPL-3.0 |

The port adds the shared modules (`shared-domain`, `shared-data`, `shared-ui`), the
desktop host (`desktopApp`), the iOS host (`iosApp`) and the independent foundation
under `shared/`, and adapts the Android host to them. Kotlin files in these modules
carry no per-file licence banner; this file records their attribution. Files in the
port that keep a third-party notice or a provenance note keep it as a comment at the
top of the file.

Portions of the port come from:

- **Rukamori** (ArchiveTune): the code ported from the Android application into the
  shared modules.
- **4nx3b** ([4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune)): the media info
  screen in `shared-ui` (`ui/screens/mediainfo/`).

## Upstream components and submodules

| Project | Author | What this repository takes from it |
| --- | --- | --- |
| [rukamori/core](https://github.com/rukamori/core) → [vossgraves/core](https://github.com/vossgraves/core) | Rukamori, forked by vossgraves | The InnerTube client (`core` submodule), a NewPipeExtractor-based fork without `NetworkGatekeeper`. |
| [NatuneGroup/IconPack](https://github.com/NatuneGroup/IconPack) (fork of [rukamori/IconPack](https://github.com/rukamori/IconPack)) · [rukamori/morideobfuscator](https://github.com/rukamori/morideobfuscator) | Rukamori (upstream); Natune (IconPack fork) | The icon pack for the icon switcher, with Natune artwork replacing or redrawing the icons that carried the old ArchiveTune mark, and the media obfuscator (the `deobfuscator` submodule; upstream `rukamori/morideobfuscator`). |
| [4nx3b/lyrics](https://github.com/4nx3b/lyrics) | 4nx3b | The `lyrics` submodule: enhanced and word-synced LRC parsing and the lyrics providers. |

## Icon artwork licence (CC-BY-SA-4.0)

The icon pack in the `IconPack` submodule (NatuneGroup/IconPack) is licensed under
**CC-BY-SA-4.0** (see `IconPack/LICENSE`), not GPL-3.0. Upstream icon sets keep their
authors' rights and attributions as listed in `IconPack/README.md`. Redistributed icon
artwork keeps this attribution and the share-alike terms.

## Solar icon artwork (CC BY 4.0)

Glyph drawables copied from the Solar icon set by 480 Design (CC BY 4.0,
[solar-icons.vercel.app](https://solar-icons.vercel.app)) ship in the app's
`res/drawable` directory and in the shared UI's `composeResources/drawable`
directory (the `m4hv_icon_*` home-variant glyphs and the `m9_media_info_solar_*`
media-info glyphs). Each copied file keeps the header comment
`Solar Icons by 480 Design (CC BY 4.0)`, which records the author, licence and source.

## Ported or adapted work

| Project | Author | What this repository takes from it |
| --- | --- | --- |
| [YumaPlayer](https://github.com/MuwMx/YumaPlayer) | [MuwMx](https://github.com/MuwMx) | The opening animation (`ui/component/splash/*`), the Spotify integration (catalog, home feed, playlist queue, profile cache), the glass surfaces (`GlassSurface`, `GlassScaffold`), the settings glass cards (`ui/theme/YumaModifiers.kt`) and the shared Spotify home layout. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | Navigation-bar and pill appearance work, the BitChord navigation bar (`ui/component/BitChordNavBar.kt`), player-style refinements, the playlist UI, several performance fixes (frosted-backdrop gating, activity-level refresh rate, menu z-order), the media info screen, and the SukiSU liquid glass port: the Liquid Glass customisation page and tuning keys, the throttled glass backdrop. |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | kushagrasinghx | The floating bottom bar whose glyph set, glass edge, stretch-squash selection pill and drag gesture the navigation bar reproduces; the BitChord home presentation and the lyrics HTTP and payload layer, re-implemented in the shared modules. | The automix analysis pipeline in `shared-domain/.../automix/` (compact feature analysis, cache policy, transition models) derives from the same port, which itself derives from Orchard (AGPL-3.0-or-later, combined into this GPL-3.0-or-later work per AGPLv3 section 13). |
| [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) | SukiSU-Ultra authors | GPL-3.0. The SukiSU liquid glass components in `ui/component/sukisu/`, reached through 4nx3b/ArchiveTune's port. The About page no longer uses SukiSU surfaces. |
| [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix) | compose-miuix-ui authors | Apache-2.0. The blob-field background shader and its light and dark colour presets, which SukiSU-Ultra's About background mirrored. The About page no longer ships that shader. |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Kyant backdrop) | Kyant0 | Apache-2.0. The `com.kyant.backdrop` library behind every liquid glass surface, used unmodified; the inner shadow and drag-to-slide animation of the liquid glass navigation pill follow it through SukiSU-Ultra's adaptations. |
| [adam-adrian/ArchiveTune](https://github.com/adam-adrian/ArchiveTune) | adam-adrian | The Editorial (V10) player adaptations. |
| [amll-dev/applemusic-like-lyrics](https://github.com/amll-dev/applemusic-like-lyrics) | amll-dev | Tuning constants and mappings, not code: `ui/component/LyricsScrollSpring.kt` (interval-adaptive scroll spring policy) and `ui/component/LyricsEmphasize.kt` (per-character last-word emphasis). Upstream is AGPL-3.0-only; no AGPL source was copied. Both files state the provenance in their headers. |

## Audio engine, USB output and native code

The October 2026 audio-engine ports were compared with the same-named files in the
candidate upstream (line similarity: 49 of 56 matched at 0.7 or more, 36 of them at
0.95 or more; 3 between 0.5 and 0.65 and 4 under 0.3, rewritten for this app). Per-file
lines in those Android sources read `Portions © <author>`. Those engine sources are
gone as of the 2026-11 unified-DSP pass; the audio a build still ships is the shared
`:shared-domain` chain in Kotlin, and the parts of that lineage ported into it keep the
credits below.

| Project | Author | Licence | What this repository carries |
| --- | --- | --- | --- |
| [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) | Clash-Projects | GPL-3.0 | Studio Master Clarity, adapted stage for stage from `lastwave/DspProcessor.cpp` into `:shared-domain` (`dsp/clarity/ClarityPreset.kt` and `ClarityStage.kt`: the six-section contour, the shelf slope and the named design trims), and `app/src/main/kotlin/com/lastwave/app/playback/UsbDacMonitor.kt`. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | GPL-3.0 | `playback/dsp/*`, `di/LastwaveModule.kt` and the audio-output inspector (`app/src/main/kotlin/tf/monochrome/`). |
| decent-usb-audio-driver | Marcelo Silva | MIT ([retained LICENSE](../audio/decent-usb-audio-driver/LICENSE)) | A previously vendored USB audio module; the unreachable implementation was removed and its licence and attribution are retained. |
| [Google Oboe](https://github.com/google/oboe) | Google | Apache-2.0 | Pinned at 1.11.0 and still declared in the app build; no native target links it since the LastWave engine went. |

The removed native work went with its notices: the LastWave engine library
(`app/src/main/cpp/lastwave`) and the SoX Resampler 0.1.3 subset it vendored
(`third_party/soxr`, LGPL-2.1), the Tryptify DSP core
(`app/src/main/cpp/tryptify/dsp`, GPL-3.0) with its snapins and WSOLA stretch, the
vendored libusb 1.0.30 subset (`third_party/libusb`, LGPL-2.1) with its USB Audio Class
driver, the Signalsmith `stretch` 1.4.0 / `linear` 0.6.5 headers
(`third_party/signalsmith-stretch`, MIT) with the pitch-shift stage that loaded them,
their Kotlin DSP layers, and the October 2026 audio-engine ports table. What still
ships from that lineage is the clarity contour ported into `:shared-domain`, the
USB-DAC monitor, and the audio-output inspector whose files name 4nx3b themselves; all
three stay credited above.

Licence status: `tryptz/Tryptify` had no `LICENSE` file when checked in October 2026.
Its native code was published separately in `tryptify-audio-core` under GPL-3.0; no part
of the Tryptify native layer remains in this tree.

Packaged desktop distributions carry their notice texts from
`desktopApp/packaging/notices/` (the QuickJS notices and the Apache-2.0 licence text); the iOS
app bundles the same QuickJS notice and the Apache-2.0 licence text it names as app resources
from `iosApp/ArchiveTune/Notices/`.
Each notice travels with the binary it covers.

## Local PO token helper

The desktop host can download and run the standalone
[bgutil-ytdlp-pot-provider-rs](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs) server
(`v0.8.1`, [release](https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs/releases/tag/v0.8.1))
to mint YouTube PO tokens when a build carries no BotGuard credentials. The project is
GPL-3.0 (Copyright © jim60105 and contributors); the corresponding source is the tagged upstream
repository.

The binary is **not bundled or shipped**: `desktopApp` downloads the asset matching the host
OS/architecture on first use from that official GitHub release, verifies it against a compiled-in
SHA-256, and runs it only on `127.0.0.1`. The pinned digests are the release's own asset digests
(also recomputed from the downloaded Linux x64 asset) and are recorded in
`desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/potoken/BgutilSidecarRelease.kt`.
Nothing in the helper path sends telemetry.

## Japanese romanization data

- **Kuromoji** (Copyright © 2010–2015 Atilika Inc. and contributors, Apache-2.0): the
  morphological analyzer behind Japanese romanization, ported to common Kotlin in
  `shared-domain` (`lyrics/romanization/`). Its licence and contributor list sit beside
  the port as `kuromoji-LICENSE.txt` and `kuromoji-CONTRIBUTORS.md`, and the ported files
  keep the Kuromoji notice.
- **IPADIC dictionary data** from `mecab-ipadic-2.7.0-20070801`; the notice is
  `kuromoji-IPADIC-NOTICE.md`. The pinned data is installed as a separate language pack
  and verified by digest.

## Mandarin romanization data

- **[mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data)** (MIT, Copyright ©
  mozillazg) and **[mozillazg/phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data)**
  (MIT): the kMandarin per-character readings and the polyphonic phrase overrides bundled into
  `shared-domain` as `MandarinPinyinData.kt`, generated by `scripts/generate_mandarin_pinyin.py`.
  Both licences permit redistribution with notice; their full texts ship beside the data as
  `pinyin-data-LICENSE.txt` and `phrase-pinyin-data-LICENSE.txt` (Copyright (c) 2016 and
  2017 mozillazg, mirrored into the bundled resource notices), the provenance sits in the
  generated file's header, and the data never ships Metrolist's `mandarin.bin` blob.

## Reference projects

These are not code dependencies. They informed design decisions and are credited where
their approaches were followed.

- [maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic): the liquid glass
  backdrop and crossfade scheduling.
- **Metrolist KMP**: the design reference for the additive `METROLIST_KMP` player style,
  the Enhanced lyrics animation and the bundled Lyprio lyrics protocol. The recovered
  Metrolist 14.0.0-rc3 material was read as reference only; the Kotlin here is written
  fresh.
- **Metrolist 14.0.0-rc6**: several 2026-10 player, lyrics, theme and playback refinements
  are behavior-level re-implementations of its rc6 improvements, including the artwork that
  holds the previous cover while the next loads, the lyrics text size, the mobile-data
  animated artwork switch, the gain-reduction readout, the stable equalizer graph and the
  palette styles. Credit order: InnerTune (Zion Huang), Metrolist (Mostafa Alagamy),
  ArchiveTune (Rukamori), with the KMP port by vossgraves / Natune.
- [Metrolist](https://github.com/mostafaalagamy/Metrolist): crossfade handoff timing, the
  Listen Together codec, and the Enhanced lyrics approach (one smoothed lyric clock,
  overlap-aware active-line scanning, draw-phase word wipe and glow masks, cooperative
  shape prewarm). The Kotlin here is written fresh.
- [maxrave-dev/kotlin-footguns](https://github.com/maxrave-dev/kotlin-footguns) (GPL-3.0):
  the Compose and Kotlin Multiplatform traps consulted for the word-timed karaoke and
  lyrics renderer work.
- [brahmkshatriya/echo-spotify-extension](https://github.com/brahmkshatriya/echo-spotify-extension):
  read to confirm how Spotify's web player lists recently played contexts and resolves
  them with `fetchEntitiesForRecentlyPlayed`. The parsing in `SpotifyPlayContexts.kt` is
  written from the live response shape; no source was copied (the repository publishes
  no licence).
- [amll-dev/applemusic-like-lyrics](https://github.com/amll-dev/applemusic-like-lyrics)
  (AGPL-3.0-only): the look the shared lyrics renderer's depth and background-vocal rows
  aim for, distance blur and a separate smaller background line. Only the behaviour was
  studied; no AGPL source was copied, and the Kotlin is written fresh. The look is rendered
  by the one shared Compose lyric surface on every platform; the earlier iOS-only UIKit
  pane and the UIKit Liquid Glass chrome it sat in were removed, so no Swift renderer of
  that lineage remains.
- [NewPipe / InnerTube](https://github.com/TeamNewPipe/NewPipeExtractor): the InnerTube
  request lineage behind `core`.
- [MetrolistGroup/innertubex](https://github.com/MetrolistGroup/innertubex) and
  [MetrolistGroup/quackjs](https://github.com/MetrolistGroup/quackjs) (GPL-3.0): used
  unmodified as libraries by the InnerTubeX extractor tier on Android, desktop and iOS.
- The Shazam discovery wire shapes in `shared-data` (`recognition/`) are ported from the
  baseline `shazamkit` module.

## Fork-only work

Multi-source audio routing (Tidal, Qobuz, Deezer, Apple Music, Amazon, QQ Music,
JioSaavn, Discord, Telegram), the ArchivePool credential service, listener presence,
the single pill-style system shared by the navigation bar, mini player and search pill,
the playlist-insertion policy, offline read-window correctness, the redacted
diagnostics exporter, the Listen Together server list, and the Kotlin Multiplatform port.

## Contributions

A contribution derived from another project names that project in the pull request and
adds it to this file.
