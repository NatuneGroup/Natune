/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
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
