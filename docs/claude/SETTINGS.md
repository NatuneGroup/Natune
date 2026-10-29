# Settings route and surface inventory

## Stable navigation

Settings search uses parent routes and `?scrollTo=` anchors. Preserve both when changing groups. There is no manual-login gate: every sign-in row on Integration (Tidal, Qobuz, Deezer, Amazon, Apple Music, QQ Music, Telegram) is always visible. The `Use pool accounts` switch at the top of the Music Sources group (default on; rendered only where a pool URL is baked in) decides whether the community Source Pool's shared accounts are consulted at all — off means no pool fetch, and the "Refresh from pool" row is hidden.

Important Integration anchors include `external_sources`, `spotify`, `music_sources`, `use_pool_accounts`, `lastfm_scrobbling`, `lastfm_account`, `listenbrainz`, and `cross_service_import`.

The Player page carries three experimental audio-engine switches after `bit_perfect_usb`: `tryptify_engine` (`TryptifyAudioProcessingKey`), `lastwave_engine` (`LastwaveAudioProcessingKey`) and `float_dsp` (`FloatDspEnabledKey`), all off by default. Tryptify and LastWave exclude each other, turning any of them on turns audio offload off, and all three are disabled while bit-perfect USB output is on (that sink skips every processor). Their search entries navigate only and carry no inline switch, so the exclusivity stays on the settings page. `playback/dsp/AudioEngineHost` reads them; with all three off the per-sink router is inactive and Media3 skips it.

Between `bit_perfect_usb` and the engines sits `usb_exclusive_audio` (`UsbExclusiveAudioKey`, off by default, search entry navigates only). It is sampled when the service starts: with it on, the standard sink gets `playback/dsp/UsbExclusiveAudioOutputProvider`, which opens an exclusive AAudio stream on the routed USB DAC and otherwise defers to a stock `AudioTrackAudioOutputProvider`; with it off the sink is built exactly as before. It and bit-perfect USB output, crossfade, automix and audio offload turn each other off (the provider re-checks the same switches live, so a stale combination still plays normally). The engines and float DSP stay usable: they run in the chain ahead of the output. The switch is disabled when `libarchivetune_dsp` fails to load. While engaged it publishes to `EngineRuntime`/`BitPerfectRuntime`, which shows the media-info pipeline row and the live chain pill's USB route; it never claims verified bit-perfect.

Appearance (`settings/appearance`) is one scrolling page in three sections — Theme, Player and Interface — whose first groups carry the section anchors `appearance_theme`, `appearance_player` and `appearance_interface`. The former sub-page routes `settings/appearance/theme`, `/player` and `/interface` stay registered so old links and saved back stacks still open; each shows the same page scrolled to its section, or to its own `?scrollTo=` anchor when one is given. Search entries indexed under a section owner navigate to `settings/appearance` with the row's anchor. Extras, lyrics animations, navigation bar, AOD, icon, palette and theme creator remain separate pages.

## Spotify integration

The Spotify group contains account login/logout, playlist visibility, playlist reload, and the opt-in `Sync Spotify listening history` switch. The switch exposes Spotify Recently Played in the History screen; it is intentionally passive and does not emulate Spotify playback.

## Apple Music experience status

The experience is chosen with **Appearance → Theme section → Interface style** (`interface_style`, legacy
anchor `apple_music_experience`). Applying Material 3 Expressive (the default) or Apple Music
coordinates the preset in one edit. Material 3 Expressive seeds the palette from purple instead of
the wallpaper when no custom or album-art color applies, and nothing seeds the Apple Music style on
a fresh install. The indicator is derived from the actual surface choices and shows **Custom / mixed**
when they differ; it is never a rendering gate. `AppleMusicExperienceKey` is retained for legacy
backups and records preset actions, not a live override.

The Search tab's field can sit at the top (default) or float above the bottom bar
(`SearchBarPositionKey`), optionally sliding away while scrolling down
(`HideSearchChromeWhileScrollingKey`). Both styles render it: a 28dp tonal container with the Solar
magnifier for Material, a capsule for the independent `AppleMusicSearchBarKey` option.

Only the explicit preset coordinates `PlayerDesignStyleKey`, `LibraryStyleKey`,
`NavigationBarStyleKey`, `AppleMusicHeadersKey`, `AppleMusicMenusKey`,
`AppleMusicSearchBarKey` and `AppleMusicPaletteKey`. Every individual control writes only its own
piece, including animated artwork, backdrop motion, pause scaling, marquee and seek growth.
Headers, action menus, search field and default accent have independent controls. Wallpaper,
artwork and custom palettes still take priority over the default accent.

The one-time `independent_apple_music_settings` migration materializes old experience overrides,
including the previously forced tab bar, without replacing explicit library/player choices.
After migration, the legacy flag cannot silently reactivate a surface. Presets remember the
displaced player and tab bar; switching back preserves later manual non-Apple choices. Explicitly
reapplying Apple Music to a mixed setup applies the full preset again.

Also shipped since this note was written:

- **Library screen** — with the library style on, the Library tab renders an Apple Music root (large
  title, chevron rows, hairline insets to the text column, the four sections opened in place under a
  back row) instead of the fork's chip row.
- **Tab bar** — the Apple Music style is a floating rounded bar, inset, with a solid accent pill
  behind the active tab, its glyph knocked out, and the label in the accent.
  The preset writes `NavigationBarStyle.APPLE_MUSIC` (4nx3b's bar geometry: a capsule at the
  user's width, 80% by default, with a neutral indicator), but never locks the style row.
  `YUMA_PLAYER` stays a separate, user-picked style. The Apple Music bar draws its mini player in
  grayscale; an unrelated Apple Music option does not.
- **One-row bottom bar** — `NavigationBarCompactBehaviorKey` (Navigation bar → behaviour) folds the
  tab bar into round Library/Home and Search buttons with a slim mini player between them, the
  4nx3b layout. Unset, it follows `NavigationBarHideOnScrollKey`: adaptive (fold while scrolling
  down) when that is on, always expanded otherwise. The Apple Music bar itself is
  always the one-row layout, so the behaviour row is locked while it is active. The row is liquid
  glass only when the tab bar would be (Liquid Glass on + its navigation-bar toggle); otherwise the
  circles and the mini player capsule are opaque `surfaceContainerHigh` with no glow. The fold is a sheet-bound change of
  `collapsedBound`, not a translation. On History and Local songs the Search button opens the
  page's own search field (`openInPageSearch` on the entry's saved state).
- **Home pages** — the Home tab's pages are a multi-select set (`ActiveHomeSourcesKey`) with a
  single-choice switcher sheet in the top bar when more than one is active; YouTube and Spotify
  today, and the enum/selector take more.
- **Player and lyrics overflow** — the player's more button opens the shared menus, which carry the
  Apple Music sleep-timer sheet and the lyrics menu. The lyrics three-dot (`AnchoredLyricsOverflowMenu`)
  opens the ordinary lyrics sheet unless `AppleMusicMenusKey` is on; then it is Apple Music's anchored
  action list on a flat charcoal card. No live backdrop blur: recording one cost a full-screen layer
  every frame on every lyrics surface.
- **Flamingo player** — `PlayerDesignStyle.FLAMINGO` (Appearance → Player section), 4nx3b's album / lyrics /
  queue pager with its own floating-light backdrop. Its moving-blur option,
  `flamingo_background_effect`, renders only while it is the selected style. Its volume bar follows
  the shared `show_player_volume_bar` switch like every other layout that has a volume row (the old
  Flamingo-only switch was folded into it; an explicit earlier Flamingo choice is kept).
- **Additional downloads** (`settings/language_packs`, search key `language_packs`) — a visible row
  in the main page's Storage group (after Downloads), also linked from Lyrics settings and found
  by search. It holds the Japanese dictionary (about 13 MB) plus files kept out of the APK in
  `addons/` (today the SpatialFlow typeface, about 4 MB). `AddonPack` fetches them from the last
  commit that touched `addons/` and checks the SHA-256 baked in at build time, so each release
  keeps getting its own bytes; anything that reads an addon must work without it. Resources under
  1 MB belong in the APK instead of this screen; both current packs are well above that.

Not complete, and must not be described as shipped: YouTube/Spotify Apple-style *Home variants* (the Home selector switches
sources, it does not restyle them), and Spotify's Recommended for Today. Direct authenticated Apple
playback must remain separate from any future public catalog search/fallback work.

## Settings change checklist

- Preserve existing parent routes and search anchors.
- Add new preference keys with explicit defaults and localized descriptions.
- Keep settings available through both the page and settings search when appropriate.
- Update route inventory and tests for any renamed or moved entry.
- Verify back navigation, deep links, dialogs, toggles, sliders, and provider-specific pages on a Nightly build.

## Navigation bar

`NavigationBarHideOnScrollKey` (off by default, Navigation bar page, anchor
`navigation_bar_hide_on_scroll`) hides the bar on downward user scrolling of a tab and drops the
collapsed mini player into its place; content padding stays static so lists do not jump. The tinted
frosted bar blends the accent into its base and content in both light and dark schemes.

Preset actions change their coordinated preferences in one DataStore edit using current stored
values, not a previous composition. Re-selecting an unchanged preset does not overwrite saved
choices; individual edits remain independent and never force the tab bar back to Apple Music.

`BackAnimationStyleKey` (Appearance, anchor `back_animation_style`) chooses the back animation:
Predictive (the default; sheets, pages and menus follow the back gesture) or Original. The SukiSU
liquid glass bar is a `NavigationBarStyle` (`SUKISU`) rendered inside the normal nav-bar container,
so compact mode and hide-on-scroll keep working.

## Restart-to-apply settings

UI scale, font and custom font, tablet mode, the Liquid Glass master switch, the liquid-glass nav
bar and the frosted nav bar are read once per process through `utils/StartupSettings`. Changing one
keeps the running latch unchanged. The local follow-up mounts one shared post-persistence
**Restart now / Later** modal through the root host, with `RestartToApplyNotice` as supporting
inline copy. Restart now is explicit; Later/dismiss never schedules a restart. Direct/search
edits and custom-font changes must use the same prompt path. Keep page snapshots and atomic writes.
"Disable blur effects" remains live through the root `LocalBlurPolicy`.

## Description text, channel choice and acoustic graph

Settings-specific marquee is automatic and finite only while visible/resumed, overflowing and
motion-enabled; short, offscreen/background and 0x text stays static. Preserve full accessible
text, wrapped paragraphs, row geometry, RTL and the separate player-title policy.

Stable and Nightly are explicit choices on all builds; build identity is only the unset default.
Canary migration must not overwrite a later Stable choice. Update/About/startup/worker paths
share the selected channel, reject stale channel candidates and require known newer version codes.

Developer remains `settings/misc`; `acoustic_quality` is a searchable animated expansion
for the current item. No item disables Run with an explanation. Shared PlayerMenu, SongMenu
and YouTubeSongMenu actions open the same dialog for their actual selected item, not blindly
the playing song; SpatialFlow uses the shared player menu. Opening is idle; collapse/navigation/
target changes deactivate work even during exit animation. See [architecture](ARCHITECTURE.md)
for bounded-source and heuristic-result limits.

Implementation and independent source review are complete; the 113 new focused test methods
were integrated but unexecuted at the 2026-10-15 pre-CI checkpoint. Actual build/test evidence is
in candidate PR/Actions, not a source-review claim. Japanese pack automation uses
primary system-device locale region JP only, one persisted best-effort foreground consideration
on Wi-Fi/cellular, no startup block/IP lookup, and explicit off/removal refusal. Offline consumes
the consideration without a retry loop. Keep manual download/cancel,
retry/remove and absent-pack fallback. IPADIC data (13,343,016 bytes) supplies offline romaji,
not UI language, lyrics, fonts or translation models. Built-in Korean/Devanagari/ICU conversions
are best-effort, not validated language identification or complete pronunciation support.
SimpMusic now shares built-in/provider/AI phonetic policy. AI settings/endpoint/exclusion
changes invalidate result ownership; DeepL is translation-only and unsupported targets fail
honestly. See [lyrics](../lyrics.md) for source-text and alignment boundaries.

## Preserved appearance-port provenance

The superseded 2026-10-02 review compared canary `6932180bb` with 4nx3b `5e0c8e452`
(historical ancestor `646829cfd`). Scroll-hidden navigation (`76cd0ae6d`), tint
(`940473914`, `6c8639207`) and bottom search (`bf905241f`, `481b13579`) were adapted
to the fork's nested scroll, stable padding, insets and pill coordinator. Credit remains
with 4nx3b; this is not a wholesale tree merge or import of settings redesign `e50e8a606`.
Glass recorder rewrites were deliberately not copied (upstream freeze/recursive-recording
fixes `53ede6f0d`, `cbf7d806c` are context, not prerequisites). Alternate players, canvas
and source/login policies remain independent of appearance. Atomic interface-style selection
retains the previous player design and a later manual choice; no database/backfill/key rotation.
Historical maintenance rationale includes gapless/podcast crossfade guards, USB lifecycle,
digest analysis keys, opaque Tidal tokens, font-backup/path validation, canvas fallback and
stale-account/profile invalidation. Do not regress these boundaries during prose cleanup.
