# Lyrics

## Android Enhanced upstream restoration (2026-11-08)

Android's Enhanced style uses `rukamori/ArchiveTune` `dev` at `8f25a9e21`, including
the `f71c0c086` frame-paced synchronization fix. `LyricsEnhanced.kt` retains upstream's
`KaraokeLyricsView`, playback clock, 64 dp / 25% focus anchor, 700 ms scroll tween,
72 dp keep-alive zone, word timestamps and offset-aware tap-to-seek. Word-synced and
TTML lyrics have no lead; line-synced LRC has upstream's 300 ms lead. The dependency
versions match upstream: `lyrics-ui 2.0.0-rc.2` and `lyrics-core 0.5.0`.

`NatuneLyricsEnhanced.kt` adapts the current provider flow, existing LRC/TTML parsers,
saved preferences, provider phonetics, Japanese pack state and settings-bound AI results
to upstream's prepared-lyrics model. The underlying TTML parser's line ends are retained.
An unaligned AI reading stays a line caption rather than being attached to the first word.
The renderer reports scroll/selection activity to the existing sheet controls and retains
the original copyright attribution. Those are the Natune adaptations; its animation,
auto-scroll and highlighting use upstream's implementation.

Spotify mode and TikTok's compact strip retain the previous implementation through
`LyricsAlternate.kt`, `LyricsAlternateData.kt` and `LyricsAlternateContent.kt`. Their
custom helpers and tests remain because they still have callers. The unused library
comparison branch was removed from `EnhancedKaraokePane` for the new library API; its
active `BasicSyncedLyricsList` path is unchanged. V2, SimpMusic, the shared desktop/iOS
renderer, the lyrics screen layout and `LyricsTrackHeader.kt` are unchanged. The Android
custom-renderer tuning history below applies to the alternate renderer after this restoration.

Source/reference review and the permitted Python checks do not establish Android
compilation or device rendering; CI supplies build/test validation.

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
checked separately from the user toggle. Portato is not part of the fixed sweep. In-memory
cached answers are revalidated on every read with the same eligibility as that lookup mode:
an ordinary lookup treats a result from a now-disabled, unavailable or media-id-incompatible
provider as a miss, while an available sweep provider's cached word-synced answer stays valid
even when its fallback switch is off, exactly as the sweep itself ignores the switch. Skipping
an entry leaves other providers' entries intact.

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
| `ui/component/LyricsEnhanced.kt` via `NatuneLyricsEnhanced.kt` | Upstream Enhanced mode in full-screen and inline lyrics surfaces |
| `ui/component/LyricsAlternate.kt` | Existing Spotify mode and compact TikTok strip |
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

The renderer draws no top bar, so each host owns its own chrome. Android's lyrics sheet header
(`ui/player/LyricsTrackHeader.kt`) carries the artwork and the track's name, and nothing else. Its
sizes come from the row it is given (`LyricsHeaderMetrics`): the artwork takes a clamped 28% share of
it (72-104 dp, so a phone gets a 92 dp tile and not a thumbnail), and the title and artist keep the
rest — about 224 dp of a 360 dp phone instead of the ~100 dp three fixed 48 dp targets left over. A
title that does not fit one line takes a second one (`LyricsHeaderTitle`: measured once per
title/style/width, marquee when it fits) instead of being clipped at the first, which is what a
clipped "São Pau" under the close button was. The sheet's one side inset is 16 dp on the header, the
transport, the lyric pane and the chrome row (`LyricsSheetSidePadding`), and the rows carry their own
12 dp text inset instead of a second 24 dp one on top of it.

The chrome row — "Lyrics from <provider>", the favourite, the overflow menu and the close button
(`ui/player/LyricsScreenFooter.kt`) — is the sheet's chrome, not a player control: it is drawn below
the auto-hiding transport, so it stays reachable after the transport has tucked itself away. It is
also the only place those three controls live: a 48 dp target is the accessibility floor, and three
of them in the name's row are what left the name no room while the row still read as spaced out.

The lyric list's rhythm is the row's own padding times two plus the list's spacing
(`BasicSyncedLyricsList`: 4 + 4 + 2 = 10 dp between two lines, down from 16 dp), so the lines read as
a stanza rather than as separated paragraphs without touching each line's own height.

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
through a sweep; paused surfaces poll at 100 ms.

Android's Enhanced pane does not draw the position the loop wrote. The loop anchors
`EnhancedLyricsDrawClock` with the player position it just read and the vsync instant it read it at,
and every draw projects that anchor with its own clock (`LyricsPositionProjection`, the one shared
drift curve: 80 ms forward / 180 ms backward before a re-anchor, 55% of the remainder per frame). A
frame therefore paints the position of the instant it is painted at instead of the position the
frame before it published, which is one frame of lag a position-driven sweep cannot hide. The loop
bumps one `frameTick` state per drawn frame, and that tick is the draw-phase subscription, so a
parked iteration still repaints the frame it published and asks for nothing else.

Nothing on the Enhanced row animates a render effect. `Modifier.blur` builds a `RenderEffect` for
the radius it is handed, so an animated distance-veil radius rebuilt one effect per row per frame for
the whole length of a line change — the construct that makes a 60 Hz lyrics sweep read as 30 Hz — and
it no longer runs: `BasicSyncedLyricsList` reads the veil's radius straight from the row's depth. The
row's fade, its depth scale and its pop still animate, in the draw phase, where a frame costs a
`graphicsLayer` update and not a relayout.

The UIKit native pane invalidates its `CADisplayLink`
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
costs a clip and a redraw rather than a recomposition. The glow is the sung word's own ink drawn
again, a little larger and tinted, inside a layer masked to that ink's box (`drawGlyphGlow`): the
light keeps the letter shapes, so a word never shows as a filled tile on the background, and a held
last word — including one the row wrapped onto its own line — never as a box behind each grapheme.
Each word fills over exactly its provider span. `LyricsSweepGeometry.MIN_SWEEP_MS` (16 ms, about
one frame) only keeps a zero or negative span from dividing by zero, so a quick word is never
stretched past the next one. The V2 profile keeps its own 180 ms `minSweepMs`.

A word sweep needs **real per-word spans**, and the Enhanced list asks before it draws one
(`ISyncedLine.hasWordTiming()`: at least two syllables, more than one distinct start, at least one
span past the 1 ms peg). A line-synced LRC row carries a `KaraokeLine` only so it can show its
reading (`buildLineSyncedLrcLine`), and every syllable in it is pegged at the line's own start;
sweeping those lit the whole row at one instant and dragged it through a completion lift it never
earned. Those rows keep the line highlight alone — the row's own fade and pop — and the same rule
decides the background voices below.

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
  few percent of scale one at a time as the sweep crosses them, each riding the same glyph bloom
  as the word, and its glow — scaled by how long it is held (`LyricsSweepGeometry.emphasisGain`) —
  rises and falls. Every other word keeps the plain feathered sweep, no letter ripple and no
  emphasis glow.
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
  sung row sweeps and lifts over its own word spans the same way, and a line that *is* the
  background voice — a whole line of `x-bg` words, or the document's second voice
  (`ttm:agent="v2"`) — is published as its own `AccompanimentKaraokeLine` row and drawn smaller
  under the lead line instead of being promoted to a lead line at full size. The shared renderer
  does the same for the second voice (`lyricsSecondVoiceStyle`). TTML states the backing voice with
  `ttm:role="x-bg"`, which `TTMLParser` (pinned `lyrics` submodule) and `shared/domain`'s `TtmlParser`
  both read. The enhanced-LRC providers have no attribute for it and write the backing vocal inline in
  round brackets, so both enhanced-LRC readers mark a bracketed run as `isBackground` as well
  (`lyrics/BackgroundVocalRuns.withBackgroundRuns`, `EnhancedLrcWords.mapBackgroundRuns`): a run that
  opens at a word's own start and closes at a word's own end, with an unbalanced open bracket marking
  the rest of the line. The convention is only read on the enhanced-LRC word-stamp shape those
  providers emit, and only for a run with lead words outside it — a line that is nothing but brackets
  ("(I know)") is the source's own phrasing of a sung lead line and stays lead. An explicit role is
  read before the convention runs (TTML's `x-bg`); the LRC text channel carries no per-word flag, so a
  bracket is the only word-level signal there. The brackets stay in the text — the renderers draw the
  source text, they never rewrite it. A rising or emphasized word's cut
  and repaint window reach the band's glyph overshoot plus the lift, so accents and descenders move
  with the word on their own baseline rather than being clipped or drawn twice.
- The sung line pops to 1.05× over 100 ms when it activates (`LyricsModeProfile.activeScale`),
  scaling from its aligned edge.
- An instrumental break of 9 s or more shows three dots above the next line (`LyricsInterludes`,
  `LyricsInterludeDots`, and `EnhancedInterludeDots` on Android). They are the size ArchiveTune's
  breathing dots draw at — `KaraokeBreathingDotsDefaults` is `size = 16.dp`, `margin = 12.dp` — so
  three 16 dp dots 12 dp apart, 72 dp across, in a 22 dp band that holds the swell; the previous 14 dp
  dots 8 dp apart read as specks under a 26 sp line. Their light is a travelling pulse on the lyrics
  timeline — each dot one half of a cosine, a third of a 1.4 s cycle behind the one before it — so the
  loop is seamless at the wrap and a break of any length keeps the same rhythm instead of a fill that
  restarts with every break. A 2.8 s breath scales them, and over the last 1.5 s an end swell
  multiplies that breath from 1 to 1.3 (`LyricsInterludeMotion.scale`). It is a product, not a second
  scale taken with `maxOf`: the swell used to start at 1.05, so the dots snapped 5% larger on the frame
  its window opened and snapped back when it closed. Both factors are motion, so reduced motion scales
  the dots by a neutral 1 throughout the break — it used to hold only the breath still and still swell
  them 30% into the arriving line.
- Depth (scale, fade and a uniform 2 dp blur) applies to every non-sung row while the list
  follows (`LyricsDepth.UNIFORM_BLUR_DP`), and waits `LyricsDepth.staggerMs` per step, so a line
  change ripples outward. Rows stay sharp until the listener's hold ends or a line is tapped,
  not only while the finger is down. The desktop host turns depth on for ENHANCED unless reduced
  motion is on.
- The list moves to a new line at once, and each row starts where it was and eases into place
  after the same stagger (`LyricsLineSettle`). The glide lasts at most 1 s, is off under reduced
  motion, and ends at once on a drag, a seek or a hidden surface. Without a glide, a line at hand
  eases to the anchor on a fixed 280 ms ease-in-out scroll; a far one keeps the distance-covering
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
15% anchor, the 4.5 s hold, the 280 ms scroll, the active-line pop, the dots and the credits footer.
Line-synced rows light at their own start with no activation lead and take no word sweep — the
timing the library view kept, without the fabricated per-word pass Natune's own sweep used to run
over it — and descenders keep their band overshoot.

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

## Round 3: feel and word-sync fixes

These follow the screenshot and feel reports after rounds 1-2. Every change keeps the Natune
implementation; the ArchiveTune tree was consulted only as a behaviour/timing reference.

- **Syllable tokens of one word are no longer split.** Enhanced LRC / TTML can hand a renderer
  several syllables for a single word ("a" + "gain", "mo" + "ment"). `LyricsSublines.kt` now joins
  syllable content **verbatim** through one shared helper (`joinSyllables` -> `JoinedSyllables` holding
  the text and the range each syllable occupies); only whitespace the source put in `KaraokeSyllable.content`
  survives. `KaraokeSweepText`, `joinSyllableContents` and `EnhancedSweptLine.joinWithRanges` all derive
  from that one join, so the plain `Text`, the single-active strip and the swept line lay out the same
  string and adjacent syllables of a word produce contiguous ranges — the karaoke sweep crosses them
  without a hole while each syllable keeps its own timing. `app/.../LyricsSyllableJoinTest.kt` pins it.
  The shared-UI renderer already used the source `LyricsLine.text` + word ranges verbatim.
- **The sync lead follows the timing shape, not the container.** `LyricsRenderTiming` (`app/lyrics`)
  picks `hasWordTiming`/`leadMsFor` from the parsed entries, matching shared `LyricsFeedTiming.leadMsFor`:
  word-timed rows (TTML *and* enhanced LRC) take no lead, line-only rows keep the 300 ms LRC lead. Previously
  `isTtml` decided, so enhanced LRC with word stamps got LRC lead 300 + visual tuning 150 = +450 ms and a
  short word was fully lit before it was sung. `LyricsEnhanced`, `LyricsV2` and `SimpMusicLyrics` read this
  helper; the same shape decides whether V2/SimpMusic drive a live span from `withFrameNanos` at display
  refresh (instead of a coarse 50 ms / `delay(16)` tick). The single-active-line strip now also gates its
  `KaraokeSweepText` on `ISyncedLine.hasWordTiming()`, so a line-synced row built only to carry a reading
  never shows a word sweep. `LyricsRenderTimingTest.kt` pins the classification; `LyricsSyncClassificationTest`
  pins the strip rule.
- **Less work per frame.** `rememberLyricsClock` published `feedMs` twice per frame (loop top and inside
  `withFrameNanos`); it now publishes once and the frame callback only waits for vsync.
  `LyricsFullscreenRenderer` rebuilt a `Set` over every row on every frame for `passedRows`;
  `PassedRowCounter` (`LyricsInteraction.kt`) builds one prefix-max array once and answers each frame with an
  allocation-free Int binary search (the prefix max keeps overlapping duet/second-voice rows honest). The
  `LyricsAutoFollow` line-change scroll kept its 280 ms duration but switched from a symmetric ease-in-out
  to `FastOutSlowInEasing`, matching ArchiveTune's fast-response active-line follow; a slow-in curve held
  the first part of a line change almost still and read as ~30 Hz. `LyricsFrameCostTest.kt` pins both.
- **The V2 interlude dots never restart.** `LyricsInterludeDots.kt` used a `rememberInfiniteTransition`
  composed only while a break was active, so the pulse snapped back to phase 0 on every break. It now reads
  `EnhancedInterludeMotion.breath()`/`dotLight()` from the absolute lyrics position in `graphicsLayer`
  (draw phase), with a `withFrameNanos` projection advancing that position smoothly between coarse polls —
  the same timeline-driven cosine cycles the Enhanced dots use. `V2InterludeDotsContinuityTest.kt` pins that
  the phase depends on absolute position, not on a break's own progress.
- **Background vocals sweep in sync in the shared renderer.** TTML `x-bg` spans and enhanced-LRC bracket
  runs both arrive as `LyricsWord.isBackground`; `LyricsRenderRows(splitBackground = true)` splits them into
  `row.background` + `row.backgroundWords`, and the feed (same `LyricsClock`) and deadline schedule (which
  already walks `backgroundWords`) covered them. The draw path did not: `LyricsRowItems` drew them as a plain
  `Text`. `LyricsBackgroundSweep.sungFraction` maps each word's range to a contiguous horizontal slice and
  fills the active word over its own span; `SweptBackgroundLine` draws dim + clipped bright over one measured
  layout, reading `clock.feedMs` in the draw phase and keeping the backing voice's dimmer alpha.
  `LyricsBackgroundSweepTest.kt` pins the fractional sweep, reduced motion and the live-frame cover.

All of the above are source-reviewed and unit-tested but not run on a device or through the Kotlin
compiler locally (CI is the compile signal); the Blacksmith BUILD/TESTS jobs at the pushed head are the
compile/test evidence.
