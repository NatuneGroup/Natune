package moe.rukamori.archivetune.shared.domain.settings

/** The settings families that can ask for a restart once they are committed. */
enum class RestartReason { UI_SCALE, FONT, TABLET_MODE, GLASS, NAVIGATION_GLASS, USB_OUTPUT, STORAGE, REGION }

/**
 * The effective value of each restart-scoped setting. These are values, not raw key presence: writing a default or
 * migrating a font is not an edit that needs a restart.
 */
fun restartSettingValues(settings: RestartScopedSettings): Map<RestartReason, String> {
    // The URI only matters while the custom font is the one in use; a stale URI behind another choice is not an edit.
    val customFontUri = if (settings.fontPreference == AppFontPreference.CUSTOM) settings.customFontUri else ""
    return mapOf(
        RestartReason.UI_SCALE to settings.uiScale.toString(),
        RestartReason.FONT to "${settings.fontPreference}|$customFontUri",
        RestartReason.TABLET_MODE to settings.tabletModeEnabled.toString(),
        RestartReason.GLASS to settings.liquidGlassEnabled.toString(),
        RestartReason.NAVIGATION_GLASS to
            "${settings.liquidGlassNavBarEnabled}|${settings.navigationBarFrostedBlur}|${settings.navigationBarTintFrostedBlur}",
    )
}

/**
 * The effective values of every restart-scoped setting, plus the USB output and the region. USB output is the choice
 * that actually takes effect: bit-perfect output needs the platform or opted-in direct driver and yields to crossfade
 * and automix, and exclusive audio is the fallback when it does not.
 */
fun restartSettingValues(
    preferences: PreferenceSnapshot,
    bitPerfectSupported: Boolean,
    directUsbDriverAvailable: Boolean = false,
): Map<RestartReason, String> {
    val bitPerfect =
        bitPerfectOutputSupported(bitPerfectSupported, preferences[DirectUsbDriverKey, false], directUsbDriverAvailable) &&
            preferences[BitPerfectUsbOutputKey, false] &&
            !preferences[CrossfadeEnabledKey, false] &&
            !preferences[AutomixEnabledKey, false]
    val direct = bitPerfect && preferences[DirectUsbDriverKey, false]
    val exclusive = !bitPerfect && preferences[UsbExclusiveAudioKey, false]
    return restartSettingValues(RestartScopedSettings.from(preferences)) +
        mapOf(
            RestartReason.USB_OUTPUT to "$bitPerfect|$exclusive|$direct",
            RestartReason.REGION to (preferences[YouTubeMusicRegionKey] ?: PreferenceDefaults.youtubeMusicRegion),
        )
}

/**
 * Decides when the single post-commit restart prompt is visible. A change prompts only when the committed value differs
 * from what is running. A later dismissal does not replay for the same value, and a restart is consumed once.
 *
 * Only settings events drive this policy. Reads, navigation and recomposition never call it.
 */
class RestartPromptPolicy {
    private val pending = linkedMapOf<RestartReason, String>()
    private val dismissed = mutableMapOf<RestartReason, String>()

    val reasons: Set<RestartReason> get() = pending.keys.toSet()

    val visible: Boolean get() = pending.any { (reason, value) -> dismissed[reason] != value }

    /**
     * Records one committed edit. A value equal to the running one clears its reason. A value that differs from the
     * running one, and that this edit changed, becomes pending. A value that is unchanged by this edit keeps whatever
     * state it already had, so a dismissed prompt stays dismissed.
     */
    fun committed(
        before: Map<RestartReason, String>,
        after: Map<RestartReason, String>,
        running: Map<RestartReason, String>,
    ) {
        after.forEach { (reason, value) ->
            if (running[reason] == value) {
                pending.remove(reason)
                dismissed.remove(reason)
            } else if (before[reason] != value) {
                pending[reason] = value
            }
        }
    }

    /** Storage changes carry a token, so a new storage operation prompts again while a repeat of the same one does not. */
    fun storageCommitted(token: String) {
        pending[RestartReason.STORAGE] = token
    }

    fun later() {
        dismissed.putAll(pending)
    }

    /** Consumes the prompt before the platform restart runs, so a duplicate confirm cannot relaunch twice. */
    fun consumeRestart(busy: Boolean): Boolean {
        if (!visible || busy) return false
        later()
        return true
    }
}
