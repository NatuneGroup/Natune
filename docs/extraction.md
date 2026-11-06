# YouTube stream extraction

YouTube is the last source tried; everything here is dead code for a Tidal, Deezer,
Qobuz or Telegram track. See [AGENTS.md](../AGENTS.md) for the multi-source contract.

## The chain

Playback (`MusicService.resolvePlaybackDataSpec`) and downloads (`DownloadUtil.resolveYouTubeDownload`)
resolve YouTube through the same live chain, each tier tried only after the previous one produced
no stream:

1. **Shipped resolver** — `YTPlayerUtils.playerResponseForPlayback` (playback, honouring Manual
   client mode) or `playerResponseForDownload` (downloads). The compiled InnerTube core with
   BotGuard/QuickJS PO tokens; the hot path, documented under *Client selection* below.
2. **`InnerTuneXStreamRepository`** — the `com.github.MetrolistGroup.innertubex` library's own
   extraction stack, adapted, not forked. It asks for clients whose response carries a plain URL
   and refuses SABR, byte-range-only and HLS-manifest results, which the player cannot fetch.
3. **`NewPipeStreamRepository`** — NewPipe's own YouTube stream extractor (MetrolistExtractor, the
   artifact `:core` already ships, through core's single NewPipe downloader), then, if that yields
   nothing, an anonymous player response minted through NewPipe's JavaScript player.
4. **Echo** (`echo/EchoStreamResolver`) — the very last resort, see below.

`playback/stream/YouTubeFailsafeChain.recover` runs steps 2–4. The extractor-tier order lives in
`ResolveAudioStreamUseCase.shippedFallbackTiers` and nowhere else. Each extractor tier is bounded at
12 s (`YouTubeFailsafeChain.TIER_TIMEOUT_MS`) and echo at 20 s, and caller cancellation always stops
the chain. Each tier logs one line under `PlaybackDiagnostics`:
`YouTube tier=<shipped|innertubex|newpipe|echo> resolved=<bool> elapsedMs=<n> [reason=<type>]`.

`InvalidPlaybackLoginContextException` and `LoginRequiredForPlaybackException` skip steps 2–4:
both are actionable by the listener, and no other extractor can fix them. When every tier fails,
the shipped resolver's original failure is rethrown so the error mapping keeps its message.

Every tier hands back a `YTPlayerUtils.PlaybackData`, so the URL cache, format persistence,
expiry and request headers downstream do not change. The headers come from the stream URL's own
`c`/`cver` parameters through `StreamClientUtils.resolveRequestProfile`, which covers every client
the fallback tiers produce URLs for (InnerTubeX's automatic direct clients `TVHTML5`,
`TVHTML5_SIMPLY`, `WEB_REMIX`, `WEB_CREATOR`, `WEB_EMBEDDED_PLAYER`; NewPipe's `ANDROID_VR`;
the anonymous `ANDROID_MUSIC`/`IOS`/`WEB_REMIX` players). The extractor tiers resolve as an
anonymous visitor, and for downloads they prefer MP4/AAC (jaudiotagger cannot tag WebM). Low-data
mode passes `LOW` quality to every tier.

`ResolveAudioStreamUseCase.invoke` (used by `NextStreamPreloader`) wraps the same stack with its own
cache keyed by `(mediaId, quality, metered, purpose, authFingerprint, pinnedFormatId)` and adds
**`YtdlnisStreamRepository`** (an external yt-dlp plugin APK through `ytdlp/CompactYtDlp.kt`, the way
YTDLnis does) after NewPipe; the plugin is optional, so it is not part of the live chain above.
`ResolvedAudioStream.source` records which tier won (`NATIVE_INNERTUBE`, `INNERTUBE_X`,
`NEWPIPE`, `YT_DLP`).

## InnerTubeX version and scope

The dependency is pinned to
`com.github.MetrolistGroup.innertubex:innertubex:v0.11.2`, released from
`MetrolistGroup/innertubex` tag `v0.11.2` at
`c863ddf15be01a1abfa4630329bdc0a1e95f1531` (2026-10-22). Its GPL-3.0 license is unchanged.
The existing JitPack group scope, app dependency and Hilt construction are retained;
this is not a replacement of the separate `:core` client or its gitlink.

v0.11.2 is the latest stable library release and the one every host consumes: the Android
variant through the root coordinate, the desktop `jvm("desktop")` module and the two iOS
modules all resolve the same tag. The v0.11 line moved the player cipher to the pure-Kotlin
QuackJS library (`com.github.MetrolistGroup.quackjs`, already in the JitPack allow-list), so
the artifact no longer requests a QuickJS native engine the way v0.9.2 did; the package's only
QuickJS runtime stays the app's own quickjs-kt dependency. It is built with Kotlin 2.4.21, the
same release our toolchain compiles, and requests Ktor 3.6.0, kotlinx-serialization 1.12.0 and
coroutines 1.11.0; aligned resolution remains a remote build gate, not a verified one.

Source inspection against the tag found every adapter-facing contract unchanged, so no call-site
adaptation was required: the anonymous `InnerTube` client, disabled `PlayerConfigRepository`,
`YtConfigParserImpl`/`YouTubeCipherService`/`InnerTubeExtractor` wiring, the `extract`
hints and `ExtractedStream` fields all keep their signatures. The library's `TokenProvider`
hook exists since v0.9.2 and stays unbound (`UnavailableTokenProvider`) — the tier remains
anonymous, with no personal/pool cookie or token-provider bridge, and this does not copy
Metrolist's authenticated facade. It requests `wantVideo=false` with HLS, SABR and
bounded-range capabilities disabled, and independently rejects prohibited results. LOW/HIGH
quality mapping, MP4-first non-LOW downloads, cancellation and retryable failed prewarm remain
unchanged.

This adapter resolves an already-selected media ID. The pin bump does not fix playlist
metadata, queue paging/order or Login WebView navigation/cookie capture; those are separate
app-owned responsibilities. Upstream explicit/kids-client and SABR-video changes do not imply
those features or benchmark gains are enabled in this direct-audio fallback.

## Desktop host tiers

The desktop host walks the same tier order through the shared `OrderedAudioResolver` and
`YouTubeFailsafePolicy`: its own client walk (`DesktopYouTubeResolver.primary`), then
`DesktopInnerTubeXTier`, then `DesktopNewPipeTier`, each bounded by the shared tier timeout and
each writing one host diagnostic line with its outcome. Echo runs on Android and iOS only, so
the desktop Echo tier answers null.

- The desktop primary walk states the player's `signatureTimestamp` for the clients that ask for it, deciphers
  `signatureCipher`/`cipher` formats and rewrites the throttling `n` parameter through the same NewPipe JavaScript
  player the NewPipe tier uses. Ciphered formats are therefore no longer dropped. Without a minted PoToken the walk
  leads with the token-free clients (`ANDROID_VR`, `VISIONOS`, `IOS`) and moves `WEB_REMIX` behind them; a manual
  client choice still leads. The walk is bounded to 15 s and six player requests, and the BotGuard mint is bounded
  inside it, so a bot-checked network fails over to the extractor tiers instead of spending the caller's whole budget.
  PO tokens still need BotGuard credentials injected at build time or supplied through `ARCHIVETUNE_POTOKEN_*`
  environment/system properties; an explicitly configured deployment mints by default, a blank build leaves the stored
  preference in charge and mints nothing. A page-bound attested provider (or the Metrolist-style bgutil sidecar)
  remains the follow-up for networks that reject untokened or unattested tokens.
- The desktop InnerTubeX tier uses `innertubexDesktopVersion`, today the same `v0.11.2`
  release as the Android pin — the version key stays separate only so a host can diverge again
  if a regression ever forces a split, not because the pins differ. Its cipher is the pure-Kotlin
  QuackJS library, so the desktop package gains no native library. The tier keeps the Android
  adapter's anonymous client, disabled player-config repository, direct-audio hints and
  prohibited-result guards.
- The desktop NewPipe tier runs NewPipe's stream extractor first and then asks ANDROID_MUSIC, iOS
  and WEB_REMIX as a visitor, deciphering `signatureCipher`/`cipher` formats and the n parameter
  through NewPipe's JavaScript player. Its one BotGuard mint is optional and bounded well inside the
  tier budget, so a stalled minting endpoint leaves untokened extraction its time.
- Every remote locator, including a header-free one, is fetched through the host lease: bounded 64 KiB probe and
  4 MiB ranged chunks, a browser `User-Agent` when the resolver names none, and socket timeouts. Only a local file
  still opens through FFmpeg's own reader (the C fallback also sets `rw_timeout` and a browser agent).
- A YouTube locator now carries the URL's `expire` parameter as its authorization window, so the queue's bounded
  recovery re-resolves it on `401/403/404/410/416` or an expired lease instead of treating a dead URL as terminal.

## iOS host tiers

The iOS host walks the same tier order through `OrderedAudioResolver`/`YouTubeFailsafePolicy` and
`IosYouTubeResolver.fallback`, each tier bounded by the shared timeout and each writing one
`YouTube tier=<name> resolved=<bool> elapsedMs=<n>` diagnostics line like the desktop host.

- The iOS InnerTubeX tier is the same `innertubex` `v0.11.2` stack as the other hosts, anonymous,
  with the pure-Kotlin QuackJS cipher — no native player engine is needed for it.
- The iOS NewPipe tier cannot run `org.schabi.newpipe.extractor` (JVM-only, like the desktop tier's
  own extractor pass). Instead it runs the tier's mechanism on common Kotlin pieces: anonymous
  visitor `player` requests over the extractor's client set — ANDROID_VR first, then the shipped
  ANDROID_MUSIC/IOS/WEB_REMIX walk — deciphering `signatureCipher`/`cipher` and the n parameter
  through the shared `YouTubeCipherService` (QuackJS), the pure-Kotlin counterpart of NewPipe's
  JavaScript-player deobfuscation. Its BotGuard mint binds the visitor session, stays optional and
  is bounded inside the tier budget.
- The iOS Echo tier keeps the cascade over the live session: WEB_REMIX first for the age-gate and
  private-track signals, then VISIONOS/ANDROID_VR 1.65/TVHTML5/ANDROID_VR 1.43/IPADOS/IOS/WEB_CREATOR
  with login-required clients skipped while anonymous, the same deciphering, the HEAD+Range
  validation probe, and the last client accepted unvalidated. Android's WebView-hosted
  meriyah/astring solver and WebView token generator are the Echo pieces that stay JVM-only; the
  QuackJS cipher and the shipped BotGuard support stand in for them.

## The cipher seam

`StreamUrlExtractor` (`playback/stream/`) is the app-side contract for the cipher step:
mint a playable URL for a format the caller has already chosen, plus the JavaScript player's
signature timestamp. `NewPipeStreamUrlExtractor` is its implementation and the only file that
touches core's `NewPipeUtils`; `di/ExtractorModule` binds it for the injectable tiers, and the
player-response pipelines (`YTPlayerUtils`, `VideoArtworkPlayer`, `EchoStreamResolver`) call it
directly because they are static call sites. Swapping the extractor backend therefore changes
one binding and one object — which is also where the `org.schabi.newpipe.extractor` question
(core's dependency block) is decided.

The embedded Python/Chaquopy yt-dlp layer was removed in 2026-08 and must not come
back; the external plugin above is a different thing and is fine.

## Client selection

Automatic client selection ignores, but retains, the saved manual client; turning automatic
selection off makes that choice available again. Desktop and iOS use the same shared
`manualPlaybackClient` gate before their existing client walks. This does not change fallback
tier order, authentication policy or video-scoped tokens. Retired persisted names
`ARCHIVETUNE_EXTRACTOR` and `HI_RES_LOSSLESS` normalize to `WEB_REMIX`; selectors no longer offer
the latter as a separate client because the runtime already resolves it to WEB_REMIX. This is
not removal of provider high-resolution quality choices or of manual client mode.

`YTPlayerUtils.STREAM_FALLBACK_CLIENTS` is tried in order: `VISIONOS`,
`ANDROID_VR`, `WEB_REMIX`, `WEB`, `MWEB`, `WEB_CREATOR`. Playback asks for
`WEB_REMIX` first (`NativeStreamRepository`), which is the YouTube Music client.

Resilience already built in, before adding more:

- **Last-successful-client fast path** (`lastSuccessfulClientKey`) — the client that
  worked last is tried first.
- **Per-video, per-client backoff** — a client that fails with 403/404/410/416 is
  benched for 10 minutes (`FAILED_CLIENT_BACKOFF_MS`).
- **Bot-detection repair** — `repairAuthStateAfterBotDetection` invalidates tokens,
  refreshes `visitorData`/`dataSyncId`, remints and retries; there is also an
  IP-rotation refresh path.
- **Quality-ladder retry** — HIGHEST → HIGH → LOW.
- **Age-gate bypass clients** appended last, only when the content setting allows it.
- **Typed failures** — `LoginRequiredForPlaybackException`,
  `BotDetectionPlaybackException`, `BadStreamPlayerResponseException`.

## Audio format choice

`selectAudioFormatCandidates` filters to audio formats that have a usable URL or
cipher, then:

- `HIGHEST` (and `AUTO` off a metered network) sorts by **highest bitrate, uncapped**.
- `HIGH`/`LOW` prefer the best format *at or under* the target (160k/70k), falling
  back to the cheapest one above it.
- Ties break on codec rank, then sample rate.

**On "high-res" / itag 774:** there is nothing to add. Playback already asks the
YouTube Music client and already takes the highest bitrate on offer, so 774 is
picked whenever it is in the response. It usually is not — that format is tied to
a Premium entitlement, not to anything the client does. The itag and bitrate that
actually played are shown in the media-info sheet (`ui/utils/ShowMediaInfo.kt`), so
check there before assuming the picker is at fault.

Browser-side tricks that harvest 774 out of an authenticated web session do not
port: they work by letting Google's own player JS run in a real DOM with real
cookies, which a native client has no equivalent of.

## PO tokens

`ui/screens/settings/PoTokenScreen.kt` and `PoTokenExtractionActivity.kt` own the
UI; minting is BotGuard/QuickJS inside the core. Tokens are video-scoped and feed
the client selection above. `AutoChoosePlaybackClientKey` gates automatic client
choice — manual selection must stay available.

## The `echo/` resolver — the fallback behind the shipped path

18 files under `app/src/main/kotlin/moe/rukamori/archivetune/echo/` plus five assets, ported from
4nx3b. `YouTubeFailsafeChain` runs it for playback and downloads as the **last resort**, never as
the primary: the shipped resolver, InnerTubeX and NewPipe are tried first and echo is only reached
once all of them have produced no stream, so the worst case echo can cause is the same failure a
moment later.

`EchoStreamResolver.playerResponseForPlayback` returns the same `YTPlayerUtils.PlaybackData` the
shipped path returns, so nothing downstream changes. Two exceptions bypass it (and the extractor
tiers) entirely — `InvalidPlaybackLoginContextException` and `LoginRequiredForPlaybackException`.
Both are actionable by the listener and neither is something a different extractor can fix, so
echo would only replace a message that can be acted on with one that cannot. When echo does run
and also fails, the *original* throwable is rethrown so the error mapping below it still produces
the meaningful message. `CipherDeobfuscator.ensureInitialized` loads echo's config stores on first
use rather than at app start.

What it adds over the shipped path: the player script is parsed with a real JavaScript parser
(`assets/solver/meriyah.js`) and regenerated with `astring.js`, so the n-parameter transform can be
recovered from whatever YouTube ships rather than matched against a pattern that a player change
breaks. `player_configs.json` and `player_dates.json` seed the known-good configurations.

Three of their core APIs do not exist in `vossgraves/core`, so the port adapts rather than pulling
the submodule along:

| Theirs | Here |
|---|---|
| `YouTube.newPipePlayer(videoId, response)` re-resolves the player response through NewPipe | No such entry point; the `?: streamPlayerResponse` fallback was already the only outcome, so the response is used directly |
| `AdaptiveFormat.isOriginal` marks the undubbed audio track | Field absent from this fork's `PlayerResponse`; every audio format stays a candidate |
| `YouTube.getNewPipeStreamUrls(videoId)` lists every `(itag, url)` pair | `NewPipeStreamUrlExtractor.streamUrl(format, videoId)` immediately above already resolves the same format through NewPipe, so the second pass had nothing to add |

Downloads reach echo through the same chain, but echo only mirrors the playback entry point: it
selects the best playable format, not an MP4 one, so a download that only echo could resolve may be
stored as WebM.
