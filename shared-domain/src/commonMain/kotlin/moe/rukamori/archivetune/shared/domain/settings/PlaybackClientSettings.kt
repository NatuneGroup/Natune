package moe.rukamori.archivetune.shared.domain.settings

/** Automatic selection leaves the saved manual choice dormant, rather than erasing or applying it. */
fun PreferenceSnapshot.manualPlaybackClient(): String? =
    get(PlayerStreamClientKey).takeUnless { get(AutoChoosePlaybackClientKey, true) }
