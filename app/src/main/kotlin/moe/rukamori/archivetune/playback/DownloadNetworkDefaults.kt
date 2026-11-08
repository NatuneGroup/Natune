package moe.rukamori.archivetune.playback

internal object DownloadNetworkDefaults {
    // 12 concurrent songs. Increased from 8 — PRDownloader now
    // manages its own internal OkHttp dispatcher (configured in
    // App.kt) with generous per-host caps, and the cache-first
    // strategy means most downloads hit the local playerCache (no
    // network fetch, just disk read) so we can afford even more
    // parallelism without starving the player's streaming requests.
    // 12 saturates a 300+ Mbps link on the cache-miss path while
    // keeping per-song latency reasonable on the cache-hit path
    // (~1-2 s for a 40 MB FLAC).
    const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 12

    // PRDownloader manages its own internal OkHttp dispatcher for
    // downloads. The constants below configure the OkHttp client
    // shared by PRDownloader and the player's streaming requests.
    // Bumped from 64/128/64 → 96/256/96 to give PRDownloader more
    // concurrent streams per host (Apple Music / Tidal CDN often
    // serves multiple songs from the same host — the higher
    // per-host cap lets those requests multiplex over a single
    // HTTP/2 connection instead of queuing behind each other).
    const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 96
    const val MAX_DOWNLOAD_HTTP_REQUESTS = 256
    const val MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST = 96
    const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 10L

    // 16 MB in-memory write buffer for CacheDataSink — increased
    // from 12 MB. Larger buffer = fewer fsync syscalls = higher
    // write throughput on flash storage. 12 parallel downloads ×
    // 16 MB = 192 MB peak heap, at the large-heap limit on modern
    // Android — safe because the buffer is only allocated lazily
    // inside `CacheDataSink.open()`, and most downloads don't
    // allocate the full buffer at the same instant.
    internal const val DOWNLOAD_WRITE_BUFFER_SIZE = 16 * 1024 * 1024

    // 128 MB fragment size — increased from 64 MB. A 40 MB FLAC is
    // stored as 1 fragment, a 150 MB hi-res FLAC as 2 (was 3).
    // Fewer fragments = fewer open file handles + fewer fsync
    // syscalls + smaller index file in the cache directory.
    internal const val DOWNLOAD_FRAGMENT_SIZE = 128L * 1024 * 1024

    // Hosts that downloads frequently hit. Pre-warming these at app
    // start means the first download of a session skips the
    // DNS+TCP+TLS handshake (~300-800ms each).
    val PREWARM_HOSTS = listOf(
        "www.youtube.com",
        "music.youtube.com",
        "r1---sn.googlevideo.com",
        "api.qobuz.com",
        "api.tidal.com",
        "amp-api.tidal.com",
        "www.jiosaavn.com",
        "api.deezer.com",
        "media.deezer.com",
        "www.deezer.com",
    )
}
