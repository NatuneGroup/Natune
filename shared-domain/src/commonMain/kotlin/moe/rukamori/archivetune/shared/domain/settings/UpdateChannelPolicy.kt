package moe.rukamori.archivetune.shared.domain.settings

/** Stable and Nightly are the only persisted channels; the legacy aliases land on Nightly. */
enum class UpdateChannel {
    STABLE,
    NIGHTLY,
    ;

    companion object {
        fun fromStoredName(value: String?, defaultValue: UpdateChannel): UpdateChannel =
            when (value) {
                // "DAILY_NIGHTLY" predates the named pre-release channel and "CANARY" named the channel
                // that was removed; both keep following the Nightly feed they were already on.
                "DAILY_NIGHTLY", "CANARY" -> NIGHTLY
                else -> entries.firstOrNull { it.name == value } ?: defaultValue
            }
    }
}

/** Build identity supplies only the unset default; a stored channel choice always wins. */
fun defaultUpdateChannel(isNightlyBuild: Boolean): UpdateChannel =
    if (isNightlyBuild) UpdateChannel.NIGHTLY else UpdateChannel.STABLE

fun PreferenceSnapshot.selectedUpdateChannel(isNightlyBuild: Boolean): UpdateChannel =
    UpdateChannel.fromStoredName(this[UpdateChannelKey], defaultUpdateChannel(isNightlyBuild))

fun PreferenceEditor.setUpdateChannel(channel: UpdateChannel) {
    this[UpdateChannelKey] = channel.name
}

enum class UpdateEligibility { NEWER, NOT_NEWER, UNKNOWN_BUILD_CODE }

/** Only a verified build code strictly above the installed one is offered; there is never a downgrade. */
fun updateEligibility(candidateCode: Int?, installedCode: Int): UpdateEligibility =
    when {
        candidateCode == null || candidateCode <= 0 -> UpdateEligibility.UNKNOWN_BUILD_CODE
        candidateCode > installedCode -> UpdateEligibility.NEWER
        else -> UpdateEligibility.NOT_NEWER
    }

/**
 * A resolved release is immutable from its eligibility check through the user's action. The host
 * chooses [downloadUrl] for its distribution (direct download or the release page) before calling.
 */
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

/** Returns null unless the release is a verified newer build with a usable download location. */
fun eligibleUpdateOffer(
    channel: UpdateChannel,
    tag: String,
    buildCode: Int?,
    versionName: String,
    notes: String?,
    downloadUrl: String?,
    installedCode: Int,
): UpdateOffer? {
    val code = buildCode ?: return null
    if (updateEligibility(code, installedCode) != UpdateEligibility.NEWER) return null
    val url = downloadUrl?.takeIf { it.isNotBlank() } ?: return null
    return UpdateOffer(channel, tag, code, versionName, notes, url)
}

/** A feed answer is applied only while both the requested channel and the selection revision still match. */
fun updateRequestIsCurrent(
    requestChannel: UpdateChannel,
    requestRevision: Long,
    selectedChannel: UpdateChannel,
    selectionRevision: Long,
): Boolean = requestChannel == selectedChannel && requestRevision == selectionRevision

/** An archive is trusted for install only when its embedded build code matches the offered newer build. */
fun archiveMatchesOffer(
    candidateCode: Int,
    archiveCode: Long?,
    installedCode: Int,
    sourceMatches: Boolean,
): Boolean = sourceMatches &&
    updateEligibility(candidateCode, installedCode) == UpdateEligibility.NEWER &&
    archiveCode == candidateCode.toLong()
