# Desktop parity follow-up

Branch `feat/r3-desktop-parity`, based on `origin/dev` at `c289e50f`.
Implementation is source-reviewed only until the orchestrator integrates it and runs CI.
No local Gradle/JVM build, lint, tests or Windows/Linux device playback is claimed.

## Spotify

Desktop playback now admits the existing catalog-bound Premium Librespot OAuth
grant through the existing account owner, calls the pinned `native/librespot` Rust
JNI resolver, and feeds its encrypted Vorbis stream through the shared Spotify
CTR preparation and account-scoped plaintext lease. Android's JNI coroutine worker
is reused through generated desktop sources; only packaged-library discovery differs.
Spotify remains streaming-only. Unsupported native proxy modes decline the source.
Unofficial playback retains its separate-account policy and the selected Echo method.

The release and desktop-compile workflows prepare optional JNI on native Linux x64,
Linux arm64 and Windows x64 runners. Exact recipe/host/checksum caches are validated;
only trusted dev/main producers save caches. Failed native preparation omits the
engine explicitly, reports a CI warning, and retains the configured source fallback.
Packages validate prepared JNI and ship the retained Librespot GPL notice.
Native compilation, packaging and authenticated playback remain unverified.

The other unofficial Spotify methods use Android WebView/MediaDrm Widevine.
The JVM host has no licensed desktop CDM binding, so these remain unavailable;
the stored method is never silently changed to Echo.

## Offline downloads

Desktop downloads now read the player's plaintext lease for progressive, TIDAL
DASH, supported HLS/AES-128, Deezer, Amazon CENC and QQ preparations. Spotify
remains excluded and protected Apple/FairPlay remains unsupported. Request-owned
staging, bounded reads, cancellation interruption, complete-length checks and the
existing atomic file/index commit are retained. Podcast episode refs are accepted
by the download/player menus and retain their kind in the portable stored row.
The only commonMain change is allowing downloaded EPISODE rows in that existing
index serializer; songs and cache rows keep their previous contract.
Playback/export of these files still needs CI and Windows/Linux runtime validation.
