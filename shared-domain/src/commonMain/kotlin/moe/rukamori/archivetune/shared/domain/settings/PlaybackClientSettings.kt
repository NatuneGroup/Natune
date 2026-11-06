package moe.rukamori.archivetune.shared.domain.settings

/** Automatic selection leaves the saved manual choice dormant, rather than erasing or applying it. */
fun PreferenceSnapshot.manualPlaybackClient(): String? =
    get(PlayerStreamClientKey).takeUnless { get(AutoChoosePlaybackClientKey, true) }

/**
 * Every client the playback-client setting offers, in the order the settings page lists them.
 *
 * [preference] is the value [PlayerStreamClientKey] stores, so a saved choice keeps its meaning across this enum. A
 * host maps the enum rather than the loose stored string, and the compiler then asks that host to state what every
 * client the setting can hold means for it — a client added here cannot be silently ignored downstream.
 */
enum class PlaybackClientChoice(val preference: String) {
    ANDROID_VR("ANDROID_VR"),
    WEB_REMIX("WEB_REMIX"),
    IOS("IOS"),
    TVHTML5("TVHTML5"),
    ANDROID_MUSIC("ANDROID_MUSIC"),
    ;

    companion object {
        /** The choice [value] names, or null for an unset, blank or unknown saved value. */
        fun of(value: String?): PlaybackClientChoice? {
            val saved = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return entries.firstOrNull { it.preference.equals(saved, ignoreCase = true) }
        }
    }
}

/** The saved manual choice as [PlaybackClientChoice]; null while automatic selection is on or the value is unknown. */
fun PreferenceSnapshot.manualPlaybackClientChoice(): PlaybackClientChoice? =
    PlaybackClientChoice.of(manualPlaybackClient())
