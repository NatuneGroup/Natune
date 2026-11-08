/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.constants.UpdateChannel

/** A resolved release is immutable from its eligibility check through the user's action. */
data class UpdateOffer(
    val channel: UpdateChannel,
    val tag: String,
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val downloadUrl: String,
) {
    val identity: String get() = "${channel.name}|$tag|$versionCode"
}

internal enum class UpdateEligibility { NEWER, NOT_NEWER, UNKNOWN_BUILD_CODE }

internal fun updateEligibility(candidateCode: Int?, installedCode: Int): UpdateEligibility =
    when {
        candidateCode == null || candidateCode <= 0 -> UpdateEligibility.UNKNOWN_BUILD_CODE
        candidateCode > installedCode -> UpdateEligibility.NEWER
        else -> UpdateEligibility.NOT_NEWER
    }

internal fun updateRequestIsCurrent(
    requestChannel: UpdateChannel,
    requestRevision: Long,
    selectedChannel: UpdateChannel,
    selectionRevision: Long,
): Boolean = requestChannel == selectedChannel && requestRevision == selectionRevision

internal fun archiveMatchesOffer(
    candidateCode: Int,
    archiveCode: Long?,
    installedCode: Int,
    sourceMatches: Boolean,
): Boolean = sourceMatches && updateEligibility(candidateCode, installedCode) == UpdateEligibility.NEWER &&
    archiveCode == candidateCode.toLong()

/**
 * What a tap on an update action must do. Deciding this as a pure function keeps every terminal
 * outcome visible to the reader: on the newest nightly the feed carries a build code equal to the
 * installed one (e.g. N20261108 / 1794114062), so there is no installable offer and the action must
 * report "up to date" rather than silently doing nothing. Before this, a stale offer (one the reader
 * has already installed, or one captured before a channel switch) fell through an early return in
 * the installer with no dialog, snackbar or progress — the "Update" button appeared dead.
 */
internal enum class UpdateAction {
    /** Stream the APK and hand it to the system installer (GMS builds). */
    INSTALL_DIRECT,

    /** Open the release/download URL in a browser (non-GMS builds). */
    OPEN_URL,

    /** Nothing newer is installable; tell the reader they are up to date. */
    UP_TO_DATE,

    /** The offer cannot be acted on (blank URL); tell the reader the check failed. */
    ERROR,
}

internal fun resolveUpdateAction(
    offer: UpdateOffer?,
    installedCode: Int,
    selectionIsCurrent: Boolean,
    canInstallDirectly: Boolean,
): UpdateAction {
    // No offer, or one captured before a channel switch / already installed: there is nothing to
    // download, so surface "up to date" instead of returning without feedback.
    if (offer == null || !selectionIsCurrent) return UpdateAction.UP_TO_DATE
    if (offer.downloadUrl.isBlank()) return UpdateAction.ERROR
    if (updateEligibility(offer.versionCode, installedCode) != UpdateEligibility.NEWER) {
        return UpdateAction.UP_TO_DATE
    }
    return if (canInstallDirectly) UpdateAction.INSTALL_DIRECT else UpdateAction.OPEN_URL
}
