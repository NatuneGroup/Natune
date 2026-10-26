package moe.rukamori.archivetune.shared.domain.settings

/** Only Flamingo used the old key. Other players must not inherit that override. */
fun PreferenceSnapshot.showPlayerVolumeBar(style: PlayerDesignStyle): Boolean =
    if (style == PlayerDesignStyle.FLAMINGO) this[FlamingoShowVolumeBarKey] ?: this[ShowPlayerVolumeBarKey, true]
    else this[ShowPlayerVolumeBarKey, true]

/** Explicit user edits consolidate the legacy value; ordinary reads never reset it. */
fun PreferenceEditor.setPlayerVolumeBar(visible: Boolean) {
    this[ShowPlayerVolumeBarKey] = visible
    this[FlamingoShowVolumeBarKey] = null
}
