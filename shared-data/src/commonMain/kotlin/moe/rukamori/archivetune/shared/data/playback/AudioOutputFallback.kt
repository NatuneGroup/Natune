package moe.rukamori.archivetune.shared.data.playback

/** Graph retries happen before any locator or lease is consumed; at most one plain canonical graph is attempted. */
internal fun <T : Any> createAudioOutputWithFallback(
    primary: () -> T?,
    plain: () -> T?,
): T? = primary() ?: plain()
