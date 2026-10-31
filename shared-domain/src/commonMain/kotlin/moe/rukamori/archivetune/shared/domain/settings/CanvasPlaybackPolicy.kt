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
    (!lowDataMode() || !connectivity.metered) &&
    animatedArtworkAllowed(connectivity)

/**
 * The mobile-data rule for animated artwork: with the setting on, a cellular network holds Canvas loops and music
 * videos back. An unmeasured network is not known to be cellular, so the artwork stays on.
 */
fun PreferenceSnapshot.animatedArtworkAllowed(connectivity: CanvasConnectivity?): Boolean =
    !this[DisableAnimatedArtworkOnMobileDataKey, false] || connectivity?.cellular != true
