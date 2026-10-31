package moe.rukamori.archivetune.shared.domain.settings

/**
 * The baseline player surface gate: either canvas toggle turns the feature on (a local custom canvas needs no
 * network), and reduced motion always wins.
 */
fun PreferenceSnapshot.canvasPlaybackEnabled(): Boolean =
    (this[ArchiveTuneCanvasKey, false] || this[SpotifyCanvasKey, false]) && !this[DisableAnimationsKey, false]

/**
 * The baseline CanvasPolicy network gate: ArchiveTune Canvas is the master switch for every provider, the stored
 * source choice must accept the provider, and the Spotify resolver additionally needs its own toggle, exactly like
 * the baseline's trySpotifyCanvas argument. The resolver is a catalog/artwork feature, never the separate Spotify
 * streaming account.
 */
fun PreferenceSnapshot.canvasPlaybackAllowed(
    provider: CanvasHealthProvider,
    connectivity: CanvasConnectivity?,
): Boolean = this[ArchiveTuneCanvasKey, false] && canvasSourceAccepts(provider) &&
    (provider != CanvasHealthProvider.SPOTIFY || this[SpotifyCanvasKey, false]) &&
    connectivity != null && connectivity.online &&
    (!this[CanvasWifiOnlyKey, false] || connectivity.wifi) &&
    (!lowDataMode() || !connectivity.metered)
