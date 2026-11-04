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

In the shared modules `LyricsFullscreenRenderer` draws every look, on every platform:
there is one lyric renderer, and iOS mounts no second one. Its Enhanced refinements are the
same everywhere: emphasis letters for held words, the interlude break each row owns, the saved
text size, the active-line pop, the uniform blur veil with its stagger, duet end-alignment by
`LyricsAlignmentPolicy`, and the credits footer with the machine-made badges. The sweep share, the
emphasis wave and the completion lift are the shared `LyricsSweepGeometry.progressAt`,
`LyricsEmphasis.bumpAt` and `LyricsSweepGeometry.dropAt` math
rather than a port, so reduced motion and word completion behave identically, and readings slide in
when their text publishes to a row, not when the row starts singing. What stays Compose-only there
is the line-change settle glide and selection/share-card UI. Every surface uses
`LyricsClock`, which subtracts the snapshot's output-latency estimate once at the playback speed,
separately from the user offset; the iOS host does not add a second route-latency offset.

## Sync offsets, romanization and frame scheduling

The lyrics offset is two parts that add: a **global** preference (`GlobalLyricsOffsetMsKey`,
editable on the Lyrics settings page) and a **per-song** correction the lyrics-menu dialog edits.
The per-song part is persisted, not session state: Android stores it as `SongEntity.lyricsOffset`
(InternalDatabase v39) and desktop/iOS store it as `portable_song_lyrics_offset` rows
(PortableStateDatabase v11), keyed by provider + media id rather than queue occurrence. Every
lyrics surface — full screen, compact strip, the SimpMusic sheet, the iOS native view — applies
`stored + global` through the same `syncOffsetMs` plumbing. When a song row does not exist yet,
committing the dialog inserts it first so the offset is never silently dropped.

Mandarin romanization is **offline**: `MandarinPinyinData` bundles ~26.8k kMandarin readings and
~8.8k polyphonic phrase overrides generated from the MIT-licensed `pinyin-data` and
`phrase-pinyin-data` sets (see `docs/CREDITS.md`, `scripts/generate_mandarin_pinyin.py`).
`MandarinRomanization` longest-matches phrases then falls back per character, and ICU stays the
fallback for characters the table does not cover. The same table serves the shared `LyricsRomanizer`
(desktop/iOS) and the Android `LyricsUtils` path, so no host needs a bundled `mandarin.bin` or a
language pack download for pinyin.

Translations of line-synced documents are stored **merged**, never as an overwrite. A saved
translation re-stamps its lines with the source rows' own timestamps and lands in a trailing
`[translatedLyrics]` section (`LrcTranslationMerge`), which the repeated-timestamp fold already
parses back into `providerTranslationText`; re-translating strips the old section first. The same
shape arrives from providers carrying `[translated_lyrics]`.

Frame requests are **deadline-gated**, not per-vsync. `LyricsDeadlineSchedule` (shared-ui, plus the
`LyricsEntry` copy in `app/lyrics`) lists the live spans where motion is continuous — a word sweep
in flight over its exact timing, a breathing interlude — and the discrete edges where the drawn
state changes. The clock loop requests a frame inside a live span, sleeps to the next boundary
otherwise, and re-checks at most every 250 ms so a seek still lands promptly. Every such loop (the
shared `rememberLyricsClock`, and Lyrics, V2, SimpMusic and Enhanced on Android) publishes the
position and line before it parks, and divides the media-time gap by the playback speed
(`lyricsIdleParkMs` in shared-ui; the app's `sleepUntilNext` wraps it), so a 2× track never sleeps
through a sweep; paused surfaces poll at 100 ms. The UIKit native pane invalidates its `CADisplayLink`
and timer when hidden or backgrounded, draws display frames only while advancing without reduced
motion, and polls a held position at 250 ms. Neither callback advances its Kotlin clock. A word's live span runs
through its completion lift (`LyricsSweepGeometry.liftEndMs`), so the loop stays awake until the
word is seated, and each row's `SINK_MS` before its first word is live too. The Android Enhanced
loop also steps every frame inside an interlude break its dots draw over (`enhancedInterludes`),
because the dots breathe from the position and the word schedule holds nothing there. The parked
`KaraokeLyricsView` branch is the exception: that view animates every frame while the song plays.

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
the word being sung, not a blurred copy of the text. Each word fills over exactly its provider
span. `LyricsSweepGeometry.MIN_SWEEP_MS` (16 ms, about one frame) only keeps a zero or negative
span from dividing by zero, so a quick word is never stretched past the next one. The V2 profile
keeps its own 180 ms `minSweepMs`.

ENHANCED (the `refinedMotion` flag on `LyricsModeProfile`, in `shared-ui`) adds these, all of
them skipped under reduced motion:

- Each line lights an activation-lead before its sung start (`LyricsActivation`): 250 ms by
  default, growing toward 750 ms when the silence before it is a tight 200–750 ms hand-off. The
  word sweep still follows the real word times; only the lit line, the deadline edges and the
  scroll target move early. Overlapping duet rows measure the silence against the furthest
  earlier end, so they light together. Only a word-synced row takes the lead: on a line-synced row
  it stacked on the LRC lead and the visual tuning offset and lit the row up to a second early, so
  line-synced rows activate at their timed start — the same instant the parked library view uses —
  and the Enhanced line index (first focus, TikTok strip) follows the same gated starts.
- The sung edge is feathered across a fifth of the active word's width, clamped to 4–28 dp, with
  a `DstIn` mask on a layer limited to the line's band (`LyricsSweepGeometry.sweepFeatherPx`).
- A word earns emphasis only when it is held at least 1 s, is at most 7 grapheme clusters and is
  written in an unjoined safe script (`LyricsEmphasis`). Its letters ripple a couple of dp and a
  few percent of scale one at a time as the sweep crosses them, and its glow — scaled by how long
  it is held (`LyricsSweepGeometry.emphasisGain`) — rises and falls. Every other word keeps the
  plain feathered sweep, no letter ripple and no emphasis glow.
- Every unsung word sits `LyricsSweepGeometry.WORD_LIFT_DP` (2 dp) below its seat and eases up
  through the last half of its own span — clamped to 120–300 ms, shortened near a tight hand-off and
  never longer than the word itself (`liftDurationMs`/`liftStartMs`/`liftEndMs`) — so it is seated
  exactly at its provider end, as its sweep finishes. Only a zero-length word rises past its end.
  The rise is an ease-out (`liftAt`) with no pause and no jump. Inactive rows draw seated, so a lit row sinks
  into that low seat over the `SINK_MS` (200 ms, inside the shortest 250 ms activation lead) before
  its first word (`sinkAt`) instead of dropping in one frame; `dropAt` is the product every renderer
  reads. A started word that has not landed draws dim and lit in its own window at one offset, so
  the sweep never lights it at another height than its dim ink. The rise is a clip plus a translate
  in the draw pass, never a relayout. An emphasized word rides the same drop under its letter
  ripple, and a word wrapped over two visual lines stays seated on Compose. Reduced motion seats
  every word, the same no-bounce behaviour as before. On Android a timed background voice under the
  sung row sweeps and lifts over its own word spans the same way. A rising or emphasized word's cut
  and repaint window reach the band's glyph overshoot plus the lift, so accents and descenders move
  with the word on their own baseline rather than being clipped or drawn twice.
- The sung line pops to 1.05× over 100 ms when it activates (`LyricsModeProfile.activeScale`),
  scaling from its aligned edge.
- An instrumental break of 9 s or more shows three dots above the next line (`LyricsInterludes`,
  `LyricsInterludeDots`). They light in turn over the break and breathe, and over the last 1.5 s
  they swell toward the arriving line.
- Depth (scale, fade and a uniform 2 dp blur) applies to every non-sung row while the list
  follows (`LyricsDepth.UNIFORM_BLUR_DP`), and waits `LyricsDepth.staggerMs` per step, so a line
  change ripples outward. Rows stay sharp until the listener's hold ends or a line is tapped,
  not only while the finger is down. The desktop host turns depth on for ENHANCED unless reduced
  motion is on.
- The list moves to a new line at once, and each row starts where it was and eases into place
  after the same stagger (`LyricsLineSettle`). The glide lasts at most 1 s, is off under reduced
  motion, and ends at once on a drag, a seek or a hidden surface. Without a glide, a line at hand
  eases to the anchor on a fixed 350 ms ease-in-out scroll; a far one keeps the distance-covering
  animation. The line settles at 35% of the box, about a third from the top, and the manual-scroll
  hold is 4.5 s. A feed jump past 1 s re-anchors at once like a seek, but it does not end the hold —
  only a seek or a new session does.
- The reading and translation slide in under the line over about 0.6 s when they publish to the
  row — a reading is for reading ahead, so it does not wait for the line to activate.
- A TTML document's songwriters and its machine-made translation/transliteration flags
  (`LyricsParser.parseTtmlCredits`) close the list as a dim credits footer; a provider-supplied
  reading is never badged.

The Enhanced pane draws with the in-house list on every API level again. The lyrics-ui
`KaraokeLyricsView` is parked behind `USE_KARAOKE_LIBRARY_VIEW` for comparison and rollback: its
word lift is hard-coded — every syllable floats 4 px into place over a fixed 700 ms from its start —
which is the choppy completion motion the shared lift replaces, and the published artifact cannot be
patched. The in-house path (`EnhancedSweptLine`, `BasicSyncedLyricsList`,
`EnhancedInterludeDots`) mirrors the shared
refinements: activation leads on word-synced rows, the shared completion lift, the per-letter
ripple, the width-share feather, the uniform blur, the
15% anchor, the 4.5 s hold, the 350 ms scroll, the active-line pop, the dots and the credits footer.
Line-synced rows light at their own start with no activation lead — the timing the library view
kept — and descenders keep their band overshoot.

iOS previously drew its own Enhanced pane in UIKit — a UIKit table, TextKit glyph caches and
Core Graphics bright masks, with a separate 35% anchor, 350 ms follow and its own dots. That
second renderer is removed: it duplicated the sweep, hold, seek and share policy and carried
its own cell-reuse and interlude invalidation defects. The shared Compose renderer is the only
Enhanced pane now.

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

Enhanced's synced pane (`ui/component/EnhancedKaraokePane.kt`) draws with
`BasicSyncedLyricsList` on every API level; the library's `KaraokeLyricsView` branch
(`USE_KARAOKE_LIBRARY_VIEW`, switched off) keeps its own auto-scroll, sweep, spring placement,
blur and dots for comparison, with a hard-coded 20dp/100dp mask the pane's fade does not touch.
The in-house list owns its auto-scroll to the anchor, sweeps
the active karaoke line with `EnhancedSweptLine` — lift, feather and emphasis letters — glides
rows on a line change, scales and fades
rows by their distance from the sung line, and shows interlude dots over silences of 9 s or more.
A line-synced row hands focus over after its 5 s hold only when more than 9 s of silence follows,
the interlude dots' own threshold. While the
library branch is parked the songwriter credits footer shows on every API level.
Following pauses for 3 s after a user drag, and a tap-to-seek resumes it at once. While the user
scrolls, every line is un-blurred. Distance blur needs Android 12+ and the line-blur setting, and
is off under reduced motion, with animations disabled, and on low-end devices.

The single-active-line strip (TikTok main lyrics) has no list and no fade.

## Two things that were wrong, so they do not come back

- **Clock gating.** The sweep must be driven by `PlayerConnection.isAudioAdvancing`
  (Media3's real `isPlaying`), never `PlayerConnection.isPlaying` — the latter is
  `playWhenReady && state != ENDED`, which stays true right through a buffer and
  through focus suppression, so lyrics ran on while nothing was audible. `isPlaying`
  stays as it is for play/pause affordances, which should flip on tap.
- **The in-house list on Android 10+.** From 2026-10-31 to 2026-11-03 Enhanced drew every
  API level with `BasicSyncedLyricsList`. Its sweep clip bands sliced descenders and lifted
  letters, and its activation lead stacked on the LRC lead and the visual tuning offset, so
  line-synced rows lit 0.7–1.2 s before they were sung. Both faults are fixed by construction —
  the bands carry a descender overshoot (`LyricsLineBands.overshootPx`) and the lead is gated to
  word-synced rows — so the list draws on every API level again, and the library view stays parked
  behind `USE_KARAOKE_LIBRARY_VIEW` because its own lift is the hard-coded one. Device verification
  of the restored list is still pending.
- **Truncation.** A one-line strip with `TextOverflow.Ellipsis` ended most lyrics in
  a "…". The strips reserve two lines and hold that height whether they fill it or
  not; growing to fit would move the controls underneath every time the line changed.
