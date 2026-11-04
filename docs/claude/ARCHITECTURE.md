# Architecture and protected boundaries

## Playback

`MusicService` owns the Media3 player, notification/session lifecycle, queue persistence, audio focus, wake lock, route recovery, source switching, and crossfade. `CrossfadePolicy` and `CrossfadeHandoffPolicy` are pure decision logic and are unit tested.

The primary player uses a bounded load control and network wake mode. Crossfade uses a separately prepared player only when explicitly enabled. Offload is disabled during crossfade because overlapping players and audio processing are not compatible with the low-power path. Secondary-player promotion is generation guarded; failed promotion leaves the outgoing player authoritative.

Crossfade and automix hand-offs are decided by `CrossfadeHandoffPolicy` (`CONTINUE`, `PROMOTE`, `CARRY_TO_PRIMARY`, `RESTORE_OUTGOING`). Each transition runs once per song pair and the outgoing song is silenced at hand-over. Once the incoming song is dominant (the outgoing song ran out, or the fade is at least half done) it is never restarted from 0: a healthy incoming player is promoted, and an unusable one hands its position to the primary player. Restores never rewind, and `CrossfadeFailureTracker` turns repeated failures into a bounded hard cut instead of another fade. When the outgoing player ends during an incomplete fade, the service clears `pauseAtEndOfMediaItems` and continues only when playback had been requested, so an intentional pause is respected.

Stream errors are handled through bounded URL/cache invalidation, source re-resolution, codec recovery, and optional next-item skipping. Do not turn a terminal error into an unbounded retry loop.

## Finite YouTube playlists (local source; validation pending)

Ordinary playlist entry points use `YouTubePlaylistQueue` and `FinitePlaylistLoader`
over the existing core browse playlist/continuation APIs. RD/Mix and explicit radio
stay on `YouTubeQueue`. MusicService owns one generation-checked finite load; accepted
items and continuation state move together. Occurrence keys preserve repeated videos;
filtered display indexes are not source locators. Selected preloads retain playback
position while the resolved prefix is integrated. Manual additions, actual shuffle
traversal, filtering and repeat remain independent of finite paging.

Persistence retains finite origin, occurrence and completion/token state. Legacy records
restore saved items/position with unknown completion; only unique order-preserving
alignment permits automatic rehydration. Failure/ambiguity keeps that timeline with
explicit retry/reload, never guessed exhaustion or radio.

Strict completion gates whole-list insertion, import/sync and ordinary Shuffle.
Shuffle uses the complete finite list and existing client shuffle, not server ordering.
No target is partially replaced/inserted on failed completion; old playback remains
until complete shuffle success. Work is bounded to 200 browse requests / 120 seconds
per operation, including selected-occurrence resolution. Ambiguous empty terminal
envelopes fail incomplete; only explicit numeric-zero initial counts prove empty.
No live envelope test or fixed 80-track server limit is established.

## Infinite queue and queue editing (local source; validation pending)

`MusicService` owns Android recommendation work through `AndroidInfiniteQueue`;
`PortableQueueOwner` owns the same feature through `PortableInfiniteQueue`. Both
use `InfiniteQueuePolicy`: seed with at most three songs remaining, page a radio
tail with at most five, counting the current song in actual shuffle/play order.
Only confirmed-exhausted finite membership may seed radio. Finite paging remains
independent of the infinity setting; unknown/failed legacy tails do not prove
exhaustion. A consumed radio tail keeps its provenance with a null continuation,
so a checkpointed exhausted radio may reseed from its current song while an
unknown legacy finite queue never seeds. Deliberate repeated playlist/manual
occurrences remain intact.

The existing `AutoLoadMoreKey` (`autoLoadMore`, default true) gates requests and
their commits. Off/repeat changes invalidate in-flight work, including late
responses; recommendations are filtered and deduplicated against the live queue.
Each attempt has a 15-second bound and at most three recommendation pages.
Empty/failing attempts back off 30/60/120/240/300 seconds using monotonic time;
preference toggles do not reset that cooldown. Playback/queue events reconsider
loading, not an unconditional retry timer. Appending never skips a playing last
song; an ended queue advances only with its existing playback intent preserved.

Android's default and custom queue sheets expose the persisted toggle and queue
save/clear/jump actions without replacing their transports. Desktop/iOS bind the
common queue panel to their existing checkpointed facade, settings and transactional
library. Queue editing and undo use occurrence identities, preserve the current
song and live additions, and invalidate stale undo on replacement. Undo/loading/source
labels are runtime state; saved queue formats, database schemas and legacy readers
are unchanged. Portable collection titles remain limited by available request
metadata (the provider page has no collection-title field). These changes and the
focused tests still need exact-head CI and device/host interaction validation.

## YouTube login ownership (local source; validation pending)

Login-only official Music install recognition makes one owned fixed same-WebView
HTTPS return; the observed `net::ERR_UNKNOWN_URL_SCHEME` is not proof of renderer loss.
Normal DOM storage/current jar remain; logout expires YouTube scopes only.
Candidates stay memory-only through a private reusable core client, serialized
validation and final-context account verification. Atomic repository mutation checks
attempt/base revision before committing identity/context. App collectors reconcile
latest persistence; delayed refresh/repair/logout/switch writers share ownership.
See [source logins](../source-logins.md) for navigation/security boundaries.

Existing anonymous MetrolistGroup InnerTubeX `v0.11.2` is separate maintenance, not
the login or pager implementation. Primary/manual client, terminal auth errors,
YTPlayerUtils → InnerTubeX → NewPipe → Echo and direct-audio guards remain intact.
Focused tests, independent review and remote compilation/execution remain pending.

## Podcast history and progress (local source; validation pending)

Android History and the shared desktop/iOS History page retain dated songs and podcast
episodes from the signed-in YouTube archive. Near-tail reads and explicit Load more use
the returned browse cursor; overlapping entries merge within a date, not across dates.
Failures retain the loaded archive and require retry, and an account change invalidates
old pages/actions. Songs retain their existing radio entry point; an episode tap queues
that episode without silently starting radio. Shuffle reads the remaining archive first,
bounded to 100 additional pages, and never starts playback from a failed/unknown prefix.

The Android/JVM adapters use core's public lower-level browse API, with a private client
and the current auth, locale, static proxy credentials and DNS. Core and its gitlink are
unchanged. Core's rotating-proxy selector is internal: while rotation is active, the
existing routed first-page request remains available, with a visible paging-unavailable
notice and retry; complete-history shuffle is unavailable. No completed archive is
claimed for that limited page. Full paging with rotation needs an approved public core
continuation/routing API. iOS uses its existing native InnerTube session with explicit
authenticated browse, without changing anonymous browse settings.

`PodcastProgressRecorder` consumes actual engine samples only. Provider-qualified episode
bookmarks write every five seconds of monotonic observation time and on pause, seek,
transition and stop. A seekable new occurrence resumes once; a played episode replays
from zero. Natural end, at least 95 percent, or the final 30 seconds mark an episode
played and reset its stored position. Unknown duration stays unknown. FIFO reads/writes
prevent a slow bookmark read or repeated episode from overwriting newer progress.
`PlaybackSessionObservers` moves Android progress and SponsorBlock listeners together
on player promotion. Episode rows observe persisted progress, showing a thin indicator,
Played, and remaining time where available.

Android adds only `podcast_progress` in schema 36 → 37; unsaved episodes need no library
row or foreign key. Portable schema 8 → 9 retains `portable_podcast_progress`. Whole-DB
backups include these tables, and desktop's schema-37 Android archive importer preserves
bookmarks alongside existing library/playlist occurrences. SponsorBlock's independent
podcast opt-in is documented in [SponsorBlock](../sponsorblock.md).

Song title/artist edits add `song_metadata_override` beside the canonical `song` row in
Android schema 37 → 38, and `portable_song_metadata_override` in portable schema 9 → 10.
The edit is display-only: an effective item keeps the provider title and credits beside the
override, so provider refresh, persistence, audio lookup and scrobble identity stay
canonical, and a reset restores the provider names. Pre-38 `titleOverride` titles move into
the table during the upgrade and the flag is cleared.

Focused parser, paging/account, progress/policy, row-recomposition, portable migration/
snapshot, and Android archive-import tests are authored but unrun locally. Android's
migration test checks the additive SQL contract, not a real Android Room upgrade.
Schema exports were prepared without running KSP: CI must confirm generated identities
for Android 38 and portable 10, including the new portable identity assertion, and those
two exports still need committing once a build emits them. Build,
test, device/account/audio and upgrade/restore validation remain unverified.

## Sources

The shared `audiosource` contract resolves Tidal, Qobuz (plus its backup), Deezer, Apple Music,
Amazon, QQ Music, JioSaavn and YouTube streams. Amazon plays through a self-hosted instance — the
user's own, then any the pool serves, each host carrying only its own authorization material — and
QQ plays from the user's own signed-in account. Spotify's catalog account serves metadata only;
the opt-in, experimental Spotify streaming source plays on a second, streaming-only account and
never on the catalog account (`docs/spotify-streaming.md`). Amazon, QQ and Spotify are all members
of `AudioSourceConfig.DEFAULT_ORDER`, placed just above YouTube (`… AMAZON, QQ, SPOTIFY, YOUTUBE`),
and all three default to off; Spotify is additionally active only while a streaming account is
signed in. Like every other source they resolve through `resolveMultiSourceDataSpec`. One ordered, gated chain (`audiosource/PlaybackSourceChain`) decides which sources may be tried, and normal play, prefetch, crossfade/automix, Cast, per-song pins, the cache shortcut and downloads all use it, so a source the user turned off is never tried. Telegram uses
independent `telegram://` routing. Tidal progressive DASH uses `tidal-dash://`. YouTube is the final
fallback and is itself a toggle: turning it off leaves the resolver with no terminal fallback beyond
the standard no-stream error.

No new provider may bypass `resolveMultiSourceDataSpec`, replace the source priority contract, or
introduce a REST/WS path to koiverse domains.

## iOS native interface (local source; validation pending)

SwiftUI is limited to `App`/`WindowGroup`, a `UIViewControllerRepresentable`, safe-area handling
and URL/browsing-activity forwarding in `iosApp/ArchiveTune/ArchiveTuneApp.swift`. The UIKit
`ArchiveTuneSession.swift` owns one `IosHost` and re-presents the same controller; detach,
backgrounding and SwiftUI reconstruction do not close services.

The shared Compose tree is the only app UI on every platform: screens, navigation shell,
tab bar, mini player, player sheet and all three lyric looks. `ArchiveTuneSession.swift`
hosts one `ComposeUIViewController` full-bleed through `IosRootController`, which exists only
to report `view.safeAreaInsets`. `IosHost.safeArea` carries those points and
`IosMountedRoot` pads the whole mounted app with them once; the shell reserves its own
measured chrome height beneath the pages (`LocalShellContentPadding`), so the status bar and
home indicator are reserved in exactly one place. Instants outside that padding are painted
by the host window.

The `NativeIosInterfaceKey` toggle and the UIKit chrome/lyric copies it switched to are
removed; a stored value simply has no surface left to gate. No app content uses SwiftUI or
`UIHostingController`. There is no WidgetKit/ActivityKit target or lock-screen lyric renderer:
existing MediaPlayer Now Playing metadata and transport remain independent and unchanged.
See [the binary comparison and boundary](../engineering/ios-swiftui-parity.md). No device QA
is claimed.

## Keyboard shortcuts (local source; validation pending)

One table owns every shortcut: `shared-ui/.../ui/shortcuts/KeyboardShortcuts.kt`. A `ShortcutEntry` pairs a
`KeymapAction` with a help group, a resource label and its chords, and the root OS key handler, the help overlay and
the settings row all read that same list, so a binding cannot exist in one surface and be missing from another. The
matcher is pure (`KeyboardShortcuts.actionFor`) and the overlay is only rendering, so both are testable without a
window.

`primary` is the platform's shortcut modifier — Cmd on Apple hardware (`ShortcutPlatform.MAC`, from
`currentShortcutPlatform`: iOS actual is always MAC, the JVM actual reads `os.name`) and Ctrl everywhere else
(`WINDOWS_LINUX`: Android, Windows, Linux). Display follows the same split: `⌘K` on Mac, `Ctrl + K` elsewhere.

| Action | Chord |
|---|---|
| Play or pause | Space |
| Next / previous song | Shift+N / Shift+P, Ctrl/Cmd + → / ← |
| Seek back / forward | ← / → |
| Volume up / down | ↑, =, + (and Shift+= where + needs Shift); ↓, - and numpad -; Ctrl/Cmd + ↑ / ↓ |
| Mute | M |
| Shuffle / repeat | S, Alt+S, Ctrl/Cmd+S; R, Alt+R, Ctrl/Cmd+R |
| Like | Alt+Shift+B |
| Seek to a part | 0-9 and numpad 0-9, to tenths of the duration |
| Search | Ctrl/Cmd+K, +F, +L, +Shift+L |
| Home / Library | Alt+Shift+H; Alt+Shift+0 |
| Playlists / podcasts / new releases / charts | Alt+Shift+1 / 2 / N / C |
| Settings | Ctrl/Cmd+, |
| Back | Esc |
| Now playing / queue | Alt+Shift+J / Alt+Shift+Q |
| Show or hide the player | T, Alt+Shift+R |
| Fullscreen lyrics / window fullscreen | L; F |
| This shortcut list | Ctrl/Cmd+/ and Ctrl/Cmd+? |

Modifiers must match exactly: an Alt+Shift chord never answers a press that also holds Ctrl, and Shift alone is not a
modifier for this purpose, so typing a capital letter never fires a transport action. While a tracked text field holds
focus (`Modifier.shortcutTextInput()` into `ShortcutTextInputState` through `LocalShortcutTextInput`) every chord
without Ctrl/Cmd/Alt is dropped — Space, letters, arrows and digits reach the field — and Escape and the modifier
chords stay live. Today the shared search field is the instrumented input; other fields still let an unmodified
letter shortcut through, which the shortcut table must not be extended into until they report focus too.

Settings → Keyboard → Keyboard shortcuts opens the same overlay as the Help chord. The desktop host keeps its own
window accelerators and the Alt+Back navigation gesture; whether any of them claim a chord here has no local
evidence, because this source is uncompiled and unexecuted and no device or keyboard-layout QA is claimed.

## Performance

SpatialFlow's player (a second Media3 player, 50–60 s buffering, a 250 ms progress loop and a simpler linear crossfade handoff) is a useful comparison, not a source to copy wholesale: this fork already has stronger readiness, generation, cache, codec, audio-route and promotion safeguards, so measure before borrowing anything. Before changing buffer sizes or adding audio processors, measure transition latency, resolver calls, cache hit rate, decoder state, dropped frames, CPU, radio use, and thermal status. Low-end validation must cover crossfade, canvas/artwork, lyrics, and audio offload independently.

Liquid glass reads the app-wide backdrop through a throttled layer (`ThrottledLayerBackdrop`,
32 ms, with a trailing record so the last frame is never stale); blur and refraction need Android 12,
runtime shaders Android 13, and older devices fall back to the plain surfaces. The About page
draws no glass or shader background: its header and credit segments are tonal `surfaceContainer` surfaces
(`AboutTonalSegment`), so it has no frame or colour loop to pause.

## Data and privacy

Spotify history synchronization is read-only and uses the existing authenticated Recently Played request. It is opt-in, appears in the History source list only when enabled, and never launches Spotify playback or a background WebView.

Pool account payloads use an Android-Keystore-encrypted cache (`PoolCacheCrypto`),
separate from personal sign-in preferences, not plaintext Pool storage. Cached payloads are not portable
credentials across reinstall/device transfer; the feed repopulates them. Personal credential
preferences and legacy migration are separate concerns: do not infer their encryption or backup
policy from Pool encryption. The Playback Pool toggle gates feeds/discovery/reports and
provider-local caches without overwriting personal accounts. An APK-delivery/read key is
extractable, not a server administrator/session/database secret; server-side authorization
remains essential.

## Bounded acoustic graph (implemented; execution evidence in PR/Actions)

`acoustics/` owns read-only input resolution, bounded decoding/FFT and memory-only results.
`AcousticQualitySection` and `AcousticQualityDialog` share the Developer and player/song-menu
surfaces. Opening is idle; Run/Cancel/Retry owns work. Ordinary seekable file/content and
supported clear current-source snapshots or complete contiguous source-bound cache are usable;
unsupported/nonseekable/DRM inputs remain honestly unavailable. Cancellation releases owned
resources; target/generation checks reject stale results. No playback resolver, queue,
source-rejection setting or persistent quality badge is mutated.

Heat, cutoff and decoded format describe the sampled rendition, not studio provenance or
physical DAC output. Silence/short/uncertain samples remain inconclusive. The graph adds no
Oboe route or native engine.

## DSP and retained native source

Maintenance pins: Oboe 1.11.0 and soxr 0.1.3 remain. The vendored libusb USB Audio
Class driver, its libusb subset and the Signalsmith/Stretch pitch shifter were removed
in the 2026-11 DSP dead-code pass, so no native pitch-shift or libusb target ships.
Existing Media3/AAudio/LastWave/Tryptify/float-DSP switches, defaults, presets, source
policy and bit-perfect bypass remain separate. soxr retains Android
runtime/header/build/license closure, not complete standalone upstream tooling/tests:
see [soxr subset](../../third_party/soxr/README).
The removed unreachable decent USB island is not the active USB output; its
[MIT license](../../audio/decent-usb-audio-driver/LICENSE), credit and app optional USB-host
feature remain. Persisted preset/state formats are not dead with an unused builder.

## Listen Together recovery and design references

Connection and pending room requests have bounded deadlines (historical design: 60/90 seconds);
decoded responses, cancellation and connection generations guard completion. Keep manual recovery,
protobuf-only Meowery (no JSON/client-capabilities frame), JSON-only chat and server-side host
restrictions. Historical 2026-10-06 synthetic checks received `room_created` from Hugging Face
and `host_not_allowed` from Meowery; these were not Android synchronized-playback validation.
The local delayed-retry fix snapshots socket, connection and room-request generations,
then checks the still-pending deadline before sending. Independent source review and confirmed
fixes are complete; actual CI is recorded in the candidate PR/Actions. This does not establish
synchronized playback or host eligibility.

Spotify Home's Yuma-derived geometry was recorded at fork `04ef0f333`, reference YumaPlayer
`28aef61`: two track rows, 240dp track tiles, 150dp artwork cards, 16dp gutters, reference
8dp quick-grid corners and 0.5dp border. The historical correction used a low-alpha 0.5dp
highlight, fixed 2dp refraction and no default shadow rather than the generic 24dp control
effect. Shared Suki material has since evolved; these pins preserve design provenance, not
current pixel parity. Preserve independent glass/blur/older-Android fallbacks and source routing.

## Integrated language, AI and request ownership

The shared lyrics path preserves semantic Unicode joiners and source spacing. Japanese
phonetics prefer provider language metadata or kana context; ambiguous Han is not assumed
Japanese just because a pack is installed. Korean phonetic working copies use NFC without
rewriting source lyrics. Provider-aligned words win; unaligned readings fall back to a line.
SimpMusic shares the built-in/provider/AI policy and shapes timed text as one source-spaced
layout. Script-family exclusions are conservative, not exact language identification.

Enhanced/V2/SimpMusic use full-settings/text AI eligibility before suppressing fallback.
Every active translation surface signals target changes even with automation off; automatic
calls retain exclusion gates. DeepL target mapping is explicit and romanization is unsupported.
Only complete validated AI output is accepted; failures remain retryable, cache identities are
opaque and configuration-bound, and obsolete work cannot publish to a changed lyrics target.

`JapaneseLanguagePackManager` owns IO validation and serialized install/cancel/remove.
Primary system-device region JP, no usable pack and no off/removal/prior-attempt flag permit
one persisted foreground consideration; offline consumes it, while manual retry remains.
No geolocation, startup wait or background watcher is added. The pinned IPADIC JAR is data,
not executable downloaded code. The real pinned-data/production-tokenizer fixture executed
in PR #198's green 835-test run and shipped in Nightly N202610151104 / build 6833.
That historical result does not validate the newer YouTube source above.

Downloader requests own UUID staging directories, latches and transport/probe cleanup.
Spotify refresh, sign-in and sign-out serialize credential persistence and session invalidation
under the same mutex; catalog/streaming separation and playback source policy remain intact.
These source-level ownership changes are not proof of real-network or device behavior.
