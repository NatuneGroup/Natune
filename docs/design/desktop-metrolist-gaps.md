# Metrolist rc8 desktop → Natune desktop: remaining gaps

Screen-by-screen comparison of the shell on `origin/dev` @ `08282d8f` against Metrolist
`14.0.0-rc8` (Linux screenshots in `/data/workspace/screens/metrolist`, side-by-side captures
in `/data/workspace/screens/user-compare`, decompiled `ui-shell` / `desktopApp` in
`/data/workspace/re-out`). The Metrolist decompile is a **behaviour reference only**; no GPL code
is copied. Metrolist paths below name decompiled classes, not a source checkout.

The `feat/desktop-metrolist-shell` / `desktop-parity-polish` / title-bar / home-background /
black-region work already landed most of the shell. What is already at parity and is **not**
re-listed as a gap: the single 56 dp top band with a centered 390 dp search pill, the 72 dp
player bar with a 6 dp seek edge, the 104/88 dp rail with 56 x 32 dp indicator pills on a 68 dp
pitch, the 648 dp Now Playing column with 392 dp art, the two-pane searchable settings tree,
the 28/22/16/13 sp `DesktopType` scale, first cover at ≤ 220 dp, the flat home/page background,
and the artwork-seeded first frame. Those metrics are pinned by `DesktopParityMetricsTest`,
`DesktopShellTest` and `DesktopSettingsNavTest`; this branch does not move them.

## Gaps (screen by screen)

| Screen | Gap vs Metrolist rc8 | Severity |
| --- | --- | --- |
| Window frame, all screens | Windows and Linux draw **the same** caption: one `DesktopCaptionButtons` with the same 10 dp canvas glyphs, no hover, no press, no close-red. `WindowsCustomFrame.applyBackdrop` labels `35` as `BORDER_COLOR` (it is `CAPTION_COLOR`; `BORDER_COLOR` is `34`), so the accent is written to the wrong DWM attribute. Mica is requested but the opaque `Surface`/AWT background never lets it show. Windows 11 caption buttons answer hover with a fill (and the close with red) and use hairline glyphs; Linux rc8 uses thin, flat glyphs with no fill. | **Largest** — the "worst UI" and "Linux/Windows look identical" reports |
| Top bar | `DesktopTitleBar` always paints `surfaceContainer`, so the Windows caption band is indistinguishable from Linux. Metrolist Windows keeps a Mica-style raised caption band; Linux keeps the artwork-tinted flat bar. | Large |
| Rail, settings nav, player controls | No hover state anywhere. `DesktopRailItem` only changes on **selection**; Metrolist's rail/rows answer pointer hover (`hoverable` + `collectIsHoveredAsState` in its shared nav scaffold), and its caption buttons answer hover/press. Selected-only feedback reads as unresponsive on a mouse. | Large |
| Settings pages and settings nav pane | Every `LazyColumn` on desktop scrolls with **no visible scrollbar**. Metrolist rc8 shows a thin desktop scrollbar at the right edge of lists (e.g. `29-player-bar.png` tracklist, settings pages). The shared UI has no scrollbar at all and `hide_scrollbar` is a phone-only option. | Medium |
| Rail labels / nav descriptions | 12 sp `labelMedium` on rail labels and `bodySmall` in the settings nav, while page/section/row headings use the 28/22/16/13 sp `DesktopType` scale. The last two sizes were never applied to the chrome that has room for them. | Small |
| Appearance settings, desktop | The merged Appearance page still shows phone-style rows that write keys with **no desktop reader**: `library_style`, `mini_player_background_style`, `crop_thumbnail_to_square`, `album_canvas_enabled`, `blur_intensity`, `backdrop_blur`, `backdrop_blur_amount`. The dedicated navigation-bar style page and `navigation_bar_settings` / `search_bar_position` / `tablet_mode` / `hide_scrollbar` rows were already removed; these are the leftover style switches. `apple_music_menus` (read via `playerMenuStyle()`), `apple_music_search_bar` (read by `SearchScreen`) and `interface_style` (the Apple Music preset, a fork invariant) are **kept**: they have desktop readers. | Medium |

## Fixed on this branch

- **Per-OS caption chrome** (`shared-ui/.../desktop/DesktopChrome.kt` (new),
  `desktopApp/.../DesktopWindowFrame.kt`, `WindowsCustomFrame.kt`,
  `shared-ui/.../desktop/DesktopTitleBar.kt`): a `DesktopCaptionChrome` enum (`System` /
  `Windows` / `Linux`) is provided by the frame and read by the title band. Windows caption
  buttons get 10 dp hairline glyphs, tonal hover/press overlays and a red close; Linux keeps
  compact 8 dp glyphs with a quiet hover tint. The Windows title band is raised one tonal step
  (`surfaceContainerHigh`); Linux keeps `surfaceContainer`. `WindowsCustomFrame.applyBackdrop`
  now writes the accent to `DWMWA_BORDER_COLOR` (34) instead of `CAPTION_COLOR` (35), resets
  the caption colour to the DWM default so an app-drawn caption is not tinted behind Mica, and
  keeps the ordered dark / Mica / rounded-corner attributes. Snap Layouts (`HTMAXBUTTON`) and
  the `WM_NCHITTEST` regions are unchanged.
- **Hover / selected** (`DesktopRail.kt`, `settings/SettingsTwoPane.kt`): hover tints unselected
  rail items and settings nav rows; selection keeps `secondaryContainer`. Caption buttons answer
  hover/press on both OSes.
- **Scrollbars** (`settings/SettingsPage.kt`, `settings/SettingsTwoPane.kt`,
  `desktopApp/.../DesktopRootMount.kt`): a `LocalDesktopListScrollbar` hook (no-op on every
  other host) draws a thin Compose Desktop `VerticalScrollbar` over the settings detail list and
  the settings nav pane. The composable stays in `jvmMain`, so no shared/iOS source imports a
  desktop-only API. Phone/iOS lists are unchanged.
- **Type**: `DesktopType.rowTitle()` / `rowBody()` (16/13 sp) replace the ad-hoc 12/sp
  `bodyMedium`/`bodySmall` chrome styles on the settings nav, and `rowBody()` replaces
  `labelMedium` on rail labels.
- **Dead desktop style rows** (`settings/AppearanceSettingsRows.kt`,
  `settings/PlayerAppearanceSettings.kt`, `DesktopSettingsNavTest.kt`): the seven keys above
  are gated behind the existing `desktop` flag. Stored keys, saved player-style
  meanings/order, lyrics rows, and the Apple Music preset are untouched.

## Deliberately not changed

- **Lyrics**: `DesktopLyricsPane`/`DesktopLyricsBody` and every lyrics setting stay as-is.
- **Playback**: no player logic, source chain or transport change.
- **Shell geometry**: the pinned 56/72/104/648 dp metrics, breakpoints and pane budget stay.
- **Full Mica transparency**: the window keeps its opaque AWT/Compose background. Making the
  client area translucent would reintroduce the black resize regions the earlier branch fixed;
  it needs a theme-wide surface change plus device QA. The Windows caption band is raised a
  tonal step and DWM's caption colour is left at its default, which is the visible part of the
  Windows frame difference. Reported as open below.

## Open / needs device QA

1. The exact Windows caption tint and whether DWM Mica is visible at all behind an opaque
   client area cannot be checked here (no Windows host, no Compose screenshot run). Verified by
   reading the DWM/JNA calls and the Compose call sites only.
2. The `hoverable` interaction and desktop `VerticalScrollbar` are exercised by source review,
   not by a rendered UI test.
3. Linux CSD behaviour under Wayland is unchanged (`DesktopWindowFramePolicy` keeps the system
   frame there); only X11 self-decoration receives the rc8-style caption.
