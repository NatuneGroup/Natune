package moe.rukamori.archivetune.shared.ui.settings

/**
 * The settings login routes that mount [ProviderSignInPage]. The host composes the page at each route and passes its
 * browser or WebView as the destination. Spotify streaming and Discord have no login route in this module, so the host
 * decides where their pages are shown.
 */
fun providerSignInKindForRoute(route: String): ProviderSignInKind? =
    when (route) {
        DeezerLoginRoute -> ProviderSignInKind.DEEZER_WEB
        TidalLoginRoute -> ProviderSignInKind.TIDAL_WEB
        QobuzLoginRoute -> ProviderSignInKind.QOBUZ_WEB
        else -> null
    }
