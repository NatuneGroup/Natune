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
| [NatuneGroup/IconPack](https://github.com/NatuneGroup/IconPack) (fork of [rukamori/IconPack](https://github.com/rukamori/IconPack)) · [rukamori/morideobfuscator](https://github.com/rukamori/morideobfuscator) | Rukamori (upstream); Natune (IconPack fork) | The icon pack for the icon switcher, with Natune artwork replacing or redrawing the icons that carried the old ArchiveTune mark, and the media obfuscator (submodules). |
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
| [YumaPlayer](https://github.com/MuwMx/YumaPlayer) | [MuwMx](https://github.com/MuwMx) | The opening animation (`ui/component/splash/*`), the Spotify integration (catalog, home feed, playlist queue, profile cache), the glass surfaces (`GlassSurface`, `GlassScaffold`) and the shared Spotify home layout. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | Navigation-bar and pill appearance work, the BitChord navigation bar (`ui/component/BitChordNavBar.kt`), player-style refinements, the playlist UI, several performance fixes (frosted-backdrop gating, activity-level refresh rate, menu z-order) and the media info screen. |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | kushagrasinghx | The floating bottom bar whose glyph set, glass edge, stretch-squash selection pill and drag gesture the navigation bar reproduces; the BitChord home presentation and the lyrics HTTP and payload layer, re-implemented in the shared modules. |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) · SukiSU-Ultra · compose-miuix-ui | Kyant0; SukiSU-Ultra and compose-miuix-ui authors | Apache-2.0. The inner shadow and drag-to-slide animation of the liquid glass navigation pill, reached through SukiSU-Ultra's adaptations. |
| [adam-adrian/ArchiveTune](https://github.com/adam-adrian/ArchiveTune) | adam-adrian | The Editorial (V10) player adaptations. |
| [amll-dev/applemusic-like-lyrics](https://github.com/amll-dev/applemusic-like-lyrics) | amll-dev | Tuning constants and mappings, not code: `ui/component/LyricsScrollSpring.kt` (interval-adaptive scroll spring policy) and `ui/component/LyricsEmphasize.kt` (per-character last-word emphasis). Upstream is AGPL-3.0-only; no AGPL source was copied. Both files state the provenance in their headers. |

## Audio engine, USB output and native code

The October 2026 audio-engine ports were compared with the same-named files in the
candidate upstream (line similarity: 49 of 56 matched at 0.7 or more, 36 of them at
0.95 or more; 3 between 0.5 and 0.65 and 4 under 0.3, rewritten for this app). Per-file
lines in those Android sources read `Portions © <author>`.

| Project | Author | Licence | What this repository carries |
| --- | --- | --- | --- |
| [tryptz/Tryptify](https://github.com/tryptz/Tryptify) | tryptz | none published | The `tf.monochrome.*` Kotlin layer (39 files): DSP manager, mix bus, parametric/AutoEq, USB router and driver wrapper, stretch/resample processors, `SpectrumAnalyzerTap`. |
| [tryptz/tryptify-audio-core](https://github.com/tryptz/tryptify-audio-core) | tryptz | GPL-3.0 | The native side under `app/src/main/cpp/tryptify` (`dsp_engine`, snapins, `libusb_uac_driver`, WSOLA stretch). |
| [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) | Clash-Projects | GPL-3.0 | `com.lastwave.*` Kotlin (9 files) and `app/src/main/cpp/lastwave`. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | GPL-3.0 | `playback/dsp/*`, `di/LastwaveModule.kt`, `EqModels.kt`. |
| decent-usb-audio-driver | Marcelo Silva | MIT ([retained LICENSE](../audio/decent-usb-audio-driver/LICENSE)) | A previously vendored USB audio module; the unreachable implementation was removed and its licence and attribution are retained. |
| [libusb](https://libusb.info) · [SoX Resampler](https://sourceforge.net/projects/soxr/) | libusb and soxr authors | LGPL-2.1 (`COPYING`, `COPYING.LGPL`) | Vendored in `third_party/` and built into the native library. |
| [signalsmith-stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) · [linear](https://github.com/Signalsmith-Audio/linear) | Signalsmith Audio | MIT (`LICENSE-*.txt`) | Vendored headers for independent pitch shift. |

Licence status: `tryptz/Tryptify` had no `LICENSE` file when checked in October 2026.
Its native code is published separately in `tryptify-audio-core` under GPL-3.0; the 39
`tf.monochrome.*` Kotlin files appear only in the app repository.

Packaged desktop distributions carry their notice texts from
`desktopApp/packaging/notices/` (the QuickJS notices and the Apache-2.0 licence text).
Each notice travels with the binary it covers.

## Japanese romanization data

- **Kuromoji** (Copyright © 2010–2015 Atilika Inc. and contributors, Apache-2.0): the
  morphological analyzer behind Japanese romanization, ported to common Kotlin in
  `shared-domain` (`lyrics/romanization/`). Its licence and contributor list sit beside
  the port as `kuromoji-LICENSE.txt` and `kuromoji-CONTRIBUTORS.md`, and the ported files
  keep the Kuromoji notice.
- **IPADIC dictionary data** from `mecab-ipadic-2.7.0-20070801`; the notice is
  `kuromoji-IPADIC-NOTICE.md`. The pinned data is installed as a separate language pack
  and verified by digest.

## Reference projects

These are not code dependencies. They informed design decisions and are credited where
their approaches were followed.

- [maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic): the liquid glass
  backdrop and crossfade scheduling.
- **Metrolist KMP**: the design reference for the additive `METROLIST_KMP` player style,
  the Enhanced lyrics animation and the self-hosted Lyprio lyrics protocol. The recovered
  Metrolist 14.0.0-rc3 material was read as reference only; the Kotlin here is written
  fresh.
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
- [NewPipe / InnerTube](https://github.com/TeamNewPipe/NewPipeExtractor): the InnerTube
  request lineage behind `core`.
- [MetrolistGroup/innertubex](https://github.com/MetrolistGroup/innertubex) and
  [MetrolistGroup/quackjs](https://github.com/MetrolistGroup/quackjs) (GPL-3.0): used
  unmodified as libraries by the InnerTubeX extractor tier on Android and desktop.
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
