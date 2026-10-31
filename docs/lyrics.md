# Lyrics

## Parsing

One parser for everyone: `lyrics/LyricsUtils` (`parseLyrics`, `parseTtml`,
`isLineSyncedLrc`, `isTtml`, `findCurrentLineIndex`, `insertInstrumentalBreaks`).
A format either works in every renderer or in none. Providers live in `lyrics/`
and are selected in Lyrics settings.

Word-level timing only exists where the provider supplies it (TTML and the
rich-sync providers). Everything else is line-synced, and a renderer must degrade
to whole-line highlighting rather than pretending.

## Provider and animation setting dependencies

Prioritize word-synced lyrics first tries BetterLyrics, YouLyPlus and Unison in that fixed
order, regardless of their user switches. Provider switches and priority still control the
ordinary fallback and manual provider search; they remain editable while word-sync priority
is on. The shared helper follows this Android policy, with actual host/provider availability
checked separately from the user toggle. Portato is not part of the fixed sweep.

`LyricsModeKey` is the canonical renderer selection; the old `use_lyrics_v2` search anchor
navigates to that selector rather than editing an ignored boolean. Android's tuning page
applies to V2, not Enhanced, Spotify-style Enhanced or the SimpMusic renderer. It stays reachable
but disables unavailable controls with a reason. App/system reduced motion disables bounce,
glow, interlude and last-word/LRC bounce effects without changing saved values; V2 adaptive
scroll, soft reveal and fill-width settings remain editable. The shared renderer does not yet
read these Android V2 tuning preferences, so shared tuning rows are explicitly unavailable
and keep the values rather than advertising an unimplemented effect.

## Language and AI follow-up (implemented and source-reviewed)

Preserve raw Unicode, semantic ZWJ/ZWNJ, combining marks and source spacing. Korean
phonetic conversion uses an NFC working copy, not rewritten lyrics. Japanese routing
uses provider metadata or kana context, not pack presence as proof that Han is Japanese.
Provider-aligned phonetic words take precedence; AI or unaligned/split-token readings
fall back to line phonetics rather than proportional or duplicated word readings.
SimpMusic shares the built-in/provider/AI policy and uses one shaped, source-spaced
timed-text layout. Script-family exclusions are conservative, not exact detection.

Enhanced/V2/SimpMusic check full-settings/text AI eligibility before suppressing fallback.
All six translation surfaces invalidate changed lyrics targets even when auto-translation
is off; automatic calls keep exclusion checks. DeepL maps supported targets explicitly
and never owns romanization. Complete validated output alone can be saved/cached;
failed/partial output is retryable, with settings-bound opaque cache identities and stale
target/configuration guards.

Optional Japanese IPADIC data remains manually installable/cancellable/removable.
One persisted foreground consideration for primary system-device locale region JP may
download over Wi-Fi/cellular when there is no usable pack and no explicit off/removal or prior attempt.
Offline consumes the consideration; manual retry remains. No IP lookup, UI-language change,
startup wait or continuous watcher is added. The 13,343,016-byte pinned data/digest and
off-main tokenizer readiness check are retained. Focused regression fixtures, including real
pinned-data/production-tokenizer coverage, are integrated. At the 2026-10-15 pre-CI checkpoint
they were unexecuted; consult candidate PR/Actions for actual execution. Independent source
review and confirmed fixes are complete; no all-language or device QA is claimed.

## Renderers

| Renderer | Where |
|---|---|
| `ui/component/LyricsEnhanced.kt` | The default full-screen renderer; also drawn inside SimpMusic's lyrics card for every mode except SIMPMUSIC |
| `ui/component/LyricsV2.kt` | Alternate full-screen renderer |
| `ui/player/simpmusic/SimpMusicLyrics.kt` | The SIMPMUSIC lyrics *mode* — a user-selectable style, reachable from the full-screen surface and the card |

In the shared modules `LyricsFullscreenRenderer` draws every look. On iOS 26 and later, with
the "Native iOS interface" setting on, the Enhanced look is drawn by the SwiftUI view in
`iosApp/ArchiveTune/SystemLyrics.swift` instead. It reads the same render rows and the same
lyric clock through `NativeLyricsDocument` and `NativeLyricsTimeline`, so seeking, sync offset
and the tap policy match the Compose renderer. Both use `LyricsClock`, which subtracts the
snapshot's output-latency estimate once at the playback speed, separately from the user offset;
the iOS host does not add a second route-latency offset.

`LyricsMode` (settings) picks between these. Do not delete `SimpMusicLyrics`
thinking it is player-style chrome — it is a lyrics style a user can select.

## The two compact surfaces

Both players carry a small "current line" strip, and they are *not* the same shape:

- **Bitchord**: `CurrentLyricLine` in `ui/player/bitchord/BitChordLyrics.kt` — one
  strip directly above the scrubber, with a chevron into the full lyrics page.
- **SimpMusic**: `SimpMusicLyricLine` in `ui/player/simpmusic/SimpMusicPlayer.kt` —
  a 48dp centred band between the artwork and the title row. Its height feeds the
  artwork sizing arithmetic in the hero layout, so it is fixed on purpose.

SimpMusic *also* has a 300dp lyrics card further down the page
(`SimpMusicLyricsCard`), below the fold with the artist and track-info cards. That
is a different surface again, and it follows the global `LyricsModeKey`: the
SimpMusic renderer when the mode is SIMPMUSIC, `LyricsEnhanced` for every other
mode (the card is a preview, and the other renderers' karaoke sweep needs a full
screen to read as anything but flicker). The style's own fullscreen lyrics sheet
(`SimpMusicFullscreenLyricsSheet`) follows the same rule.

## The word sweep

`SweptLyricLine` draws a line in two layers: the whole line dim, then the sung part bright,
clipped to however much has been sung. The clip is recomputed in the draw phase, so a frame
costs a clip and a redraw rather than a recomposition. The glow is a tinted rounded box behind
the word being sung, not a blurred copy of the text.

ENHANCED (the `refinedMotion` flag on `LyricsModeProfile`, in `shared-ui`) adds these, all of
them skipped under reduced motion:

- The sung edge is feathered over 6 dp with a `DstIn` mask on a layer limited to the line's band.
- The glow is scaled by how long the word is held. Words under 1 s get none, and the share rises
  to full at 2 s (`LyricsSweepGeometry.emphasisGain`).
- A word held for at least 1 s lifts up to 2.5 dp while it is sung. It is cut out of the line
  and drawn again higher inside its own box (`LyricsSweepGeometry.liftShare`).
- An instrumental break of 4 s or more shows three dots above the next line (`LyricsInterludes`,
  `LyricsInterludeDots`). They light in turn over the break and breathe.
- Depth (scale, fade and blur by distance from the sung line) waits `LyricsDepth.staggerMs` per
  step, so a line change ripples outward. Rows stay sharp until the listener's hold ends or a
  line is tapped, not only while the finger is down. The desktop host turns depth on for ENHANCED
  unless reduced motion is on.
- The list moves to a new line at once, and each row starts where it was and eases into place
  after the same stagger (`LyricsLineSettle`). The glide lasts at most 1 s, is off under reduced
  motion, and ends at once on a drag, a seek or a hidden surface. The line settles at 35% of the
  box, about a third from the top. Every other look keeps the single animated scroll.

The iOS 26 native Enhanced view (`iosApp/ArchiveTune/SystemLyrics.swift`) draws its own sweep. It
takes the idle alpha and the depth values from the shared profile, but not the feather, lift,
glow gating, dots, stagger or glide.

It is fed by `rememberLyricsClock`, which carries the player's twice-a-second position
report forward on the frame clock so the sweep does not step.

**Both compact surfaces share this renderer.** That is a deliberate exception to the
player-style self-containment rule (2026-09-01, amended 2026-09-07): SimpMusic's band
used to highlight whole lines off the same word timings Bitchord swept through, and
a second copy of the sweep is a second thing to fix whenever a provider changes shape.
Everything else in each style stays its own.

## The box edge fade

Every scrolling lyrics surface fades the top and bottom of its own box with
`lyricsViewportFade` (`shared-ui`, `lyrics/LyricsViewportFade.kt`): an offscreen layer
with a smoothstep `DstIn` mask, 56dp at the top and 96dp at the bottom, each capped at
22% of the box height. The ramps belong to the box, so they stay put while lines scroll
under them, and the mask only fades the lyrics, never the glass or artwork behind them.
`LyricsEnhanced`, `LyricsV2`, `SimpMusicLyrics`, the legacy `Lyrics` and the shared
`LyricsFullscreenRenderer` each apply it themselves, so hosts (the lyrics page, the
SimpMusic card, every player style's lyrics pane) must not add a second edge mask.
Flamingo keeps only its controls-visible mask that clears the lines from under its controls.

Enhanced's synced pane (`ui/component/EnhancedKaraokePane.kt`) draws every line with
`BasicSyncedLyricsList` on every API level. The list owns its auto-scroll to the anchor, sweeps
the active karaoke line with `EnhancedSweptLine`, glides rows on a line change, scales and fades
rows by their distance from the sung line, and shows interlude dots over silences of 4 s or more.
Following pauses for 3 s after a user drag, and a tap-to-seek resumes it at once. While the user
scrolls, every line is un-blurred. Distance blur needs Android 12+ and the line-blur setting, and
is off under reduced motion, with animations disabled, and on low-end devices.

The library's `KaraokeLyricsView` (lyrics-ui 1.0.x, which hard-codes a 20dp/100dp mask) stays
wired behind `USE_KARAOKE_LIBRARY_VIEW`, switched off, because it cannot draw the sweep, glide,
depth or dots. Its measure-taller-and-lift workaround and the 20dp anchor offset apply only to
that branch; re-check those numbers when the library is upgraded. The single-active-line strip
(TikTok main lyrics) has no list and no fade.

## Two things that were wrong, so they do not come back

- **Clock gating.** The sweep must be driven by `PlayerConnection.isAudioAdvancing`
  (Media3's real `isPlaying`), never `PlayerConnection.isPlaying` — the latter is
  `playWhenReady && state != ENDED`, which stays true right through a buffer and
  through focus suppression, so lyrics ran on while nothing was audible. `isPlaying`
  stays as it is for play/pause affordances, which should flip on tap.
- **Truncation.** A one-line strip with `TextOverflow.Ellipsis` ended most lyrics in
  a "…". The strips reserve two lines and hold that height whether they fill it or
  not; growing to fit would move the controls underneath every time the line changed.
