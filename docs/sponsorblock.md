# SponsorBlock

Skips stretches of a YouTube track that the SponsorBlock community has marked.
Off by default — it is a third-party lookup, so it is the user's call.

Package: `sponsorblock/`. Settings live in Player settings.

## Privacy

Lookups go out **by SHA-256 prefix**, never by video id: the service is asked for
every video whose id hashes to the same first four hex characters, and the answer is
filtered locally. That is the mode the official clients use, and it is the only mode
implemented here — a plain `videoID=` lookup would hand a third party a complete
listening history.

Non-YouTube ids are never looked up at all. That matters more here than in a
YouTube-only player: most sources in this app are Tidal, Deezer, Qobuz or Telegram,
and a SponsorBlock lookup for them would be both meaningless and a leak.

## Pieces

- `SponsorBlockModels.kt` — the eight categories, the segment type, and
  `normalizeSponsorBlockApiUrl`, which accepts only a bare https origin (no path,
  query, fragment or userinfo) so a bad value cannot redirect lookups.
- `SponsorBlockRepository.kt` — Ktor/OkHttp client, settings read from DataStore per
  lookup, access-ordered LRU of 64 responses. Overlapping segments are merged, since
  seeking out of one into the next reads as a stutter. Lookup failures do not break
  playback; coroutine cancellation propagates, and a 404 is normal "nothing published".
- `SponsorBlockPlaybackController.kt` — polls 200ms only while playing over known
  segments. Paused, idle and segment-free states sleep until the play state or the
  segments change, and it seeks out of a segment it is inside.
- `SponsorBlockDriver.kt` (`shared-ui`) — the desktop and iOS path for both
  songs and podcast episodes.

## Behaviour worth keeping

- **Only ever seeks forward**, and remembers each segment it has skipped, so a user
  who deliberately scrubs back into one is not fought frame by frame.
- **Never seeks past the end** — a segment running to the finish is left to play out
  rather than ending the track early.
- **Re-attaches on crossfade promotion.** `MusicService` builds a new session player
  and releases the old one there; a listener left on the old player goes deaf for the
  rest of the session.

## Podcast episodes

Episodes are skipped only when **Skip podcast segments** (`sponsor_block_podcasts_enabled`)
is also on. It is off by default and has no effect while SponsorBlock itself is off. Only
episodes that play from YouTube are looked up, through the same lookup songs use; other
providers are never looked up. Android handles them in `SponsorBlockPlaybackController`.
The shared desktop and iOS sessions handle them in `SponsorBlockDriver`, which applies the
same gates: songs need only the main opt-in, episodes additionally need the podcast opt-in.

Both controllers observe settings during the current episode, including while paused.
Turning either opt-in off cancels an in-flight lookup and clears loaded segments; changing
categories or the API origin replaces that occurrence's lookup. Cache hits still check
the current gates. Late responses cannot re-enable skipping after a settings edit or
player promotion. A seek requires an advancing, seekable player, and an end-of-episode
segment is not marked skipped when it is left to play out.

An unset or blank API origin keeps the public default. An invalid stored origin refuses
lookup instead of selecting another server, including when a URL parser could infer a
host from an incomplete `https:` value. An explicitly empty category selection also
refuses lookup. These gates apply before cache hits; stored preferences are not rewritten.

The shared domain gate and portable request-configuration tests cover these policy
boundaries without an advancing synthetic playback engine. They are authored, not
locally executed; CI and real playback verification remain required.

## Defaults

`MUSIC_OFFTOPIC` only. A music player wants the non-music parts of a music video
skipped; the other seven categories are about talking over video content this app
does not show, so they ship off.
