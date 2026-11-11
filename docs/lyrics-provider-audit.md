# Lyrics provider audit — 2026-11-11 UTC

Baseline: `f44eae4c148896cdab61de31af57654af71ac777`. Inventory written before code edits.

## Interpretation and selection trace

Lyprio is a real provider, not an abbreviation for priority: `app/.../lyrics/LyprioLyricsProvider.kt:22`, shared `LyprioLyricsProvider.kt:121`, and `LYPRIO` in `PreferenceKeys.kt:1100`. It requires a build-supplied server and signing key; a blank build disables it. Credentials were not requested or read. Release workflows supply the pair; PR jobs intentionally omit the signing secret. Liveness without the real pair is unverified; retain it.

Android: LyricsSettings.kt:127 reads `lyricsProviderOrder`, dialog saves enum names at :148; PreferenceKeys.kt:1142 parses; LyricsHelper.kt:509 maps ids, appends unlisted providers, and :273 filters eligibility. :432 fetches in parallel, :468 ranks word > line > plain then order. Thus normal order is honoured within each timing tier. The default-on sweep at :289 instead uses fixed BetterLyrics/YouLyPlus/Unison order and :565 bypasses their toggles. Cache eligibility repeats that bypass. Saved order parser also rewrites an LRCLIB,KUGOU,BETTER_LYRICS prefix, violating explicit saved order.

Shared: LyricsProviderPreferences.kt:26 parses the same key and has the same prefix rewrite; SettingsLyricsRuntime.kt:43 builds config, :51 applies order, :55 supplies toggles. Its :100 sorter leaves providers outside the picker in fixed slots, so even moving Lyprio first can leave lrc.red/Bini ahead. Shared LyricsHelper.kt:213 fetches enabled providers, but :197 and the sweep bypass switches. HostLyrics.kt:309 registers portable clients; native account/YouTube clients are host extras.

All Android providers below are registered in `LyricsHelper.kt:49-72`; all portable versions are registered in `shared-ui/.../host/HostLyrics.kt:309-327`. No priority id lacks an Android implementation. Priority-only omissions are lrc.red, Bini, Genius and both YouTube providers. Direct Apple Music has an account availability gate but no user toggle; YouTube providers have no toggle.

| Provider | Android source | Endpoint hosts/routes | Priority id | Toggle and whether honoured |
|---|---|---|---|---|
| Lyprio | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/LyprioLyricsProvider.kt:22` | build-supplied LYPRIO_BASE_URL; signed POST /v2/lyrics then /lyrics | LYPRIO | EnableLyprioLyricsKey; normal/manual: yes |
| lrc.red | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/LrcRedLyricsProvider.kt:25` | lrc.red | MISSING | EnableLrcRedKey; normal/manual: yes |
| BiniLyrics | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/BiniLyricsProvider.kt:23` | lyrics-api.binimum.org; response-supplied HTTPS document | MISSING | EnableBiniLyricsKey; normal/manual: yes |
| BetterLyrics | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/BetterLyricsProvider.kt:19` | lyrics-api.boidu.dev (getLyrics, kugou/getLyrics) | BETTER_LYRICS | EnableBetterLyricsKey; normal/manual: yes; sweep/cache: NO |
| BetterLyrics Portato | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/BetterLyricsPortatoProvider.kt:17` | lyrics-api.boidu.dev (qq/getLyrics) | BETTER_LYRICS_PORTATO | EnableBetterLyricsPortatoKey; normal/manual: yes |
| YouLyPlus | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/YouLyPlusLyricsProvider.kt:19` | lyricsplus.prjktla.my.id, lyricsplus.prjktla.workers.dev, lyricsplus-seven.vercel.app | YOULY_PLUS | EnableYouLyPlusLyricsKey; normal/manual: yes; sweep/cache: NO |
| LrcLib | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/LrcLibLyricsProvider.kt:17` | lrclib.net | LRCLIB | EnableLrcLibKey; normal/manual: yes |
| Kugou | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/KuGouLyricsProvider.kt:17` | mobileservice.kugou.com, lyrics.kugou.com | KUGOU | EnableKugouKey; normal/manual: yes |
| Megalobiz | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/MegalobizLyricsProvider.kt:29` | api.megalobiz.com, megalobiz.com | MEGALOBIZ | EnableMegalobizLyricsKey; normal/manual: yes |
| SimpMusic | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/SimpMusicLyricsProvider.kt:17` | api-lyrics.simpmusic.org | SIMPMUSIC | EnableSimpMusicLyricsKey; normal/manual: yes |
| Unison | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/UnisonLyricsProvider.kt:19` | unison.boidu.dev | UNISON | EnableUnisonLyricsKey; normal/manual: yes; sweep/cache: NO |
| Paxsenix: Apple Music | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/PaxsenixAppleMusicLyricsProvider.kt:17` | lyrics.paxsenix.org; music.apple.com, amp-api.music.apple.com (catalog search) | PAXSENIX_APPLE_MUSIC | EnablePaxsenixAppleMusicLyricsKey; normal/manual: yes |
| Apple Music | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/AppleMusicAccountLyricsProvider.kt:47` | music.apple.com, amp-api.music.apple.com (account) | APPLE_MUSIC | AppleMusicMediaUserTokenKey; normal/manual: yes |
| Paxsenix: NetEase | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/PaxsenixNeteaseLyricsProvider.kt:17` | lyrics.paxsenix.org | PAXSENIX_NETEASE | EnablePaxsenixNeteaseLyricsKey; normal/manual: yes |
| Paxsenix: Spotify | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/PaxsenixSpotifyLyricsProvider.kt:17` | lyrics.paxsenix.org | PAXSENIX_SPOTIFY | EnablePaxsenixSpotifyLyricsKey; normal/manual: yes |
| Paxsenix: Musixmatch | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/PaxsenixMusixmatchLyricsProvider.kt:17` | lyrics.paxsenix.org | PAXSENIX_MUSIXMATCH | EnablePaxsenixMusixmatchLyricsKey; normal/manual: yes |
| Paxsenix: YouTube | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/PaxsenixYouTubeLyricsProvider.kt:17` | lyrics.paxsenix.org | PAXSENIX_YOUTUBE | EnablePaxsenixYouTubeLyricsKey; normal/manual: yes |
| Tidal | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/TidalLyricsProvider.kt:23` | api.tidal.com (account) | TIDAL | EnableTidalLyricsKey; normal/manual: yes |
| Deezer | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/DeezerLyricsProvider.kt:19` | api.deezer.com, www.deezer.com (account) | DEEZER | EnableDeezerLyricsKey; normal/manual: yes |
| YouTube Subtitle | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/YouTubeSubtitleLyricsProvider.kt:14` | music.youtube.com/youtubei/v1/get_transcript | MISSING | none (always enabled); no user toggle |
| YouTube Music | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/YouTubeLyricsProvider.kt:15` | music.youtube.com/youtubei/v1/next, browse | MISSING | none (always enabled); no user toggle |
| Musixmatch (experimental) | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/MusixmatchExperimentalLyricsProvider.kt:20` | apic-appmobile.musixmatch.com | MUSIXMATCH_EXPERIMENTAL | EnableMusixmatchExperimentalKey; normal/manual: yes |
| Genius | `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/GeniusLyricsProvider.kt:24` | genius.com | MISSING | EnableGeniusLyricsKey; normal/manual: yes |

## Shared-only and auxiliary inventory

| Component | Source | Registration/order/toggle | Hosts |
|---|---|---|---|
| Spotify account | `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/lyrics/SpotifyAccountLyricsProvider.kt:11` | Dormant implementation; no production constructor caller found; no picker entry required | api.spotify.com, spclient.wg.spotify.com |
| User modules | `app/src/main/kotlin/moe/rukamori/archivetune/modules/ModuleLyricsProviders.kt:1`; `desktopApp/.../DesktopModules.kt:204` | Dynamic host registrations; PreferLyricsExtensionsKey orders Android extensions before/after built-ins; shared runtime does not observe this key (defect) | Module-defined, cannot enumerate without installed modules |
| Embedded/local lyrics | `app/.../localmedia/EmbeddedLyricsExtractor.kt:1` | Local media route, no network provider or picker entry required | none |

The lyrics git submodule at `2f36ad1` owns LRCLIB, KuGou, SimpMusic, Paxsenix, BetterLyrics, Unison and YouLyPlus transports (`settings.gradle.kts:149-155`; `app/build.gradle.kts:885-891`). It stays untouched. Paxsenix QQ/Deezer/Genius auto proxies are not shipped as Natune providers; do not add them. EnablePaxsenixLyricsKey is a visible master toggle (LyricsProvidersSettings.kt:126, :413), but neither Android nor shared fetch eligibility reads it: it only hides settings. This is a wiring defect. No Auto implementation is registered.

## Liveness and upstream evidence

Measurements follow. Source references above describe the pre-edit baseline.


Requests used Rick Astley / Never Gonna Give You Up, 213 seconds, no album, and video id dQw4w9WgXcQ. Each curl had a 15-second cap and 512 KiB response cap. Authentication headers used only shipped public defaults; no personal/build secrets were read. At most three requests were made to any host, including the Paxsenix landing page and Bini's document request to lrc.red. Generic payloads were inspected for timing/text; no Android renderer was involved.

The first batch saved bodies but lost HTTP-status telemetry when Spotify returned an object instead of a list and the probe script raised an exception. Remaining per-host request slots were used to capture statuses; the three boidu requests had already exhausted their budget and were not repeated. Their HTTP status is explicitly unverified, while all three returned usable TTML/LRC. One unauthenticated Megalobiz control was accidentally issued; it is excluded from liveness conclusions. The shipped bearer shape returned 200. This limitation is preserved rather than inventing missing statuses or exceeding the request cap.

| Host | Requests | Captured HTTP / usable result | Decision |
|---|---:|---|---|
| lrclib.net | 2 | 200; synced and plain lyrics | Keep |
| mobileservice.kugou.com | 1 | HTTP not retained; 214 song matches | Keep |
| lyrics.kugou.com | 3 | 200 hash search and 200 download; base64 decoded to timed LRC; initial keyword miss | Keep |
| lyrics-api.boidu.dev | 3 | HTTP not retained; getLyrics returned 35,030-byte TTML; kugou/getLyrics 2,681-byte LRC; qq/getLyrics 8,251-byte lyrics | Keep BetterLyrics and Portato |
| lyricsplus.prjktla.my.id | 3 | 530 / Cloudflare 1033, no lyrics (TTML and LRC paths) | Unavailable; tunnel failure can be temporary, keep |
| lyricsplus.prjktla.workers.dev | 3 | 404 / Cloudflare 1042, no lyrics (TTML and LRC paths) | Unavailable; no retirement notice, keep |
| lyricsplus-seven.vercel.app | 3 | 402 DEPLOYMENT_DISABLED, no lyrics | Billing/deployment issue; keep fallback pending maintainer decision |
| unison.boidu.dev | 3 | 200 metadata search, empty data; video query said NOT_FOUND | Live track miss; keep |
| api-lyrics.simpmusic.org | 2 | 403 Cloudflare challenge HTML, no usable lyrics | Blocked from this VM; no retirement evidence, keep |
| lyrics-api.binimum.org | 1 | HTTP not retained; 5,262-byte results with lyricsUrl; document fetched from lrc.red returned 200 TTML | Keep |
| lrc.red | 3 | Search matches; both document requests 200 TTML | Keep |
| api.megalobiz.com | 3 | Shipped bearer 200, empty models; unauthenticated control 403 excluded | Live track miss; keep |
| megalobiz.com | 2 | 200 public /lrc page | Keep |
| genius.com | 2 | HTTP 200 official Rick Astley lyric page after search; HTML lyrics container, plain lyrics | Keep |
| lyrics.paxsenix.org | 3 | Landing page; Spotify search retirement JSON (HTTP not retained); Musixmatch word query 403, same retirement JSON | Remove four retired proxies; retain exempt Apple Music |
| Lyprio build host | 0 | Server/signing pair unavailable; cannot make its signed request shape | Unverified, keep |
| music.apple.com, amp-api.music.apple.com | 0 | Apple developer-token discovery and account lyrics require configured credentials; no fabricated unsigned/account requests | Unverified, keep |
| api.tidal.com | 0 | Account bearer required | Unverified, keep |
| api.deezer.com, www.deezer.com | 0 | Catalog matching / ARL-authenticated gateway session required | Unverified, keep |
| music.youtube.com | 0 | InnerTube next/browse/transcript context is host configured; not executed in this audit | Unverified, keep |
| apic-appmobile.musixmatch.com | 0 | Token/bootstrap session required; separate from retired Paxsenix proxy | Unverified, keep experimental |
| api.spotify.com, spclient.wg.spotify.com | 0 | Account implementation dormant; no production constructor call | No addition |

Captured requests and statuses: `/data/workspace/tmp/lyrics-probes.json`; raw responses: `/data/workspace/tmp/lyrics-probe-*.body`. Query names match the transport: boidu s/a/d; YouLyPlus title/artist/duration on v1/ttml/get and v2/lyrics/get; LRCLIB track_name/artist_name; KuGou ver/man/client/duration or hash and download id/accesskey; Bini track/artist/duration; Unison song/artist/duration/limit; Paxsenix Spotify q and Musixmatch q/t/a/d/type=word. The same host budget precluded separate NetEase/YouTube/Apple requests; their decision uses the service notice and existing Natune retirement wording.

## Authoritative evidence (observed 2026-11-11 UTC)

- Service response from [Spotify search](https://lyrics.paxsenix.org/spotify/search?q=Never%20Gonna%20Give%20You%20Up%20Rick%20Astley) and [Musixmatch word lyrics](https://lyrics.paxsenix.org/musixmatch/lyrics?q=Never%20Gonna%20Give%20You%20Up%20Rick%20Astley&t=Never%20Gonna%20Give%20You%20Up&a=Rick%20Astley&d=213&type=word): **"This endpoint is no longer available due to the massive amount of traffic and the lack of support needed to keep it running."** It explicitly says **"The /apple-music/lyrics endpoint has no restrictions, so feel free to use it."** This is a retirement announcement, not a transient 403 inference. The baseline Natune string `paxsenix_endpoint_retired` already said **"Only Paxsenix: Apple Music still works."** Four unsupported proxy toggles were nevertheless shipped.
- [Paxsenix landing/docs](https://lyrics.paxsenix.org/) still lists every route and says **"Fetch synced and plain lyrics from Apple Music, Spotify, YouTube, Genius and more in one API."** This landing-page inventory is stale relative to the direct route retirement response; it does not establish route liveness. No dated global shutdown announcement was found.
- [Paxsenix0 Spotify Lyrics API README](https://github.com/Paxsenix0/Spotify-Lyrics-API/blob/main/README.md) says **"Unofficial API to fetch Spotify Lyrics data"** and **"You must supply your sp_dc cookie from a logged-in Spotify session."** Its repository is unarchived, updated 2026-11-02T23:20:40Z. This separate self-hosted project is not the retired hosted endpoint and is not a replacement added here. [MusixMatch-Lyrics](https://github.com/Paxsenix0/MusixMatch-Lyrics) is also unarchived, updated 2026-10-25T13:48:48Z. No upstream project abandonment claim is justified by these repos.
- The supplied `/data/workspace/metrolist-src` contains MetrolistManager, innertubex and quackjs checkouts, **no Metrolist app checkout**. GitHub [Metrolist branches](https://api.github.com/repos/MetrolistGroup/Metrolist/branches?per_page=100) lists main, but no dev (both MetrolistGroup/dev and 4nx3b/dev returned 404). Inspected main at `e6fea3b36c68841bad21daa6252e1d69ecd3afbe`, dated 2026-11-02T23:18:28Z. Its [Paxsenix client](https://github.com/MetrolistGroup/Metrolist/blob/e6fea3b36c68841bad21daa6252e1d69ecd3afbe/app/src/main/kotlin/com/metrolist/paxsenix/Paxsenix.kt) still targets **"https://lyrics.paxsenix.org"** for Apple Music. It has not removed Paxsenix altogether; this supports keeping the exempt Apple route. No Lyprio implementation exists in that current tree; Natune's configured signed client remains unverified, not proven dead.
- Supplied `/data/workspace/archivetune-ref` is at `59d2c056c3712be0ea929fc847e4c708c81a3644`, dated 2026-11-06T23:45:56+07:00; its origin is koiverse/ArchiveTune. Current [rukamori ArchiveTune dev LyricsHelper](https://github.com/rukamori/ArchiveTune/blob/dev/app/src/main/kotlin/moe/rukamori/archivetune/lyrics/LyricsHelper.kt), fetched at commit `6719e4b9a985c600385db8fa000b0cb4f7623314` dated 2026-11-11T12:17:39Z, still registers **PaxsenixAppleMusicLyricsProvider, PaxsenixSpotifyLyricsProvider, PaxsenixMusixmatchLyricsProvider**. It did not provide an upstream removal to port. Its Binimum provider is already represented by Natune's Bini implementation; no new provider is added.

## Removal slice

Removed Android wrappers, portable implementations/models, registrations, priority enum entries, toggle keys/rows, retired strings in every locale where present, and corresponding dead-provider tests for Paxsenix NetEase/Spotify/Musixmatch/YouTube. Both platforms silently drop stale order ids and preserve surviving order; old boolean preference leftovers have no registered consumer. Android endpoint diagnostics now use the portable Apple-only probe. The lyrics submodule is left untouched as instructed: its still-needed Apple Music transport shares a file/module with inactive retired transports; no gitlink or submodule source is staged.

Migration tests cover interleaved removed ids, duplicates, unknown names, blank/all-removed lists, and surviving toggles. `:shared-domain:jvmTest` migration/M6 tests passed. Repository checker: 0 findings. Python script suite: 265 tests passed. The shared-data provider order, cancellation and Apple Music mock tests also passed (63 total tests across the two modules in the final removal invocation). Android compilation/device behavior remains unverified. The wiring slice is recorded below when completed.
