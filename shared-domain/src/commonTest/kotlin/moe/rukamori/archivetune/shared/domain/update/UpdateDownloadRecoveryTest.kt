package moe.rukamori.archivetune.shared.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer
import moe.rukamori.archivetune.shared.domain.settings.updateRequestIsCurrent

class UpdateDownloadRecoveryTest {
    private fun offer(channel: UpdateChannel = UpdateChannel.NIGHTLY) =
        UpdateOffer(channel, "N202611081200", 101, "N202611081200 (build 101)", null, "https://example.test/a.apk")

    @Test
    fun goneAssetAcceptsDifferentIdentityOrUrl() {
        for (channel in UpdateChannel.entries) {
            val failed = offer(channel)
            for (status in listOf(404, 410)) {
                for (fresh in listOf(
                    failed.copy(tag = "N202611091200", versionCode = 102),
                    failed.copy(downloadUrl = "https://example.test/b.apk"),
                )) {
                    assertEquals(fresh, replacementDownloadOffer(status, failed, fresh, true, false))
                }
            }
        }
    }

    @Test
    fun goneAssetRejectsMissingOrUnchangedOffer() {
        val failed = offer()
        assertNull(replacementDownloadOffer(404, failed, null, true, false))
        assertNull(replacementDownloadOffer(410, failed, failed, true, false))
        assertNull(replacementDownloadOffer(404, failed, failed.copy(notes = "new notes"), true, false))
        assertNull(replacementDownloadOffer(404, failed, failed.copy(downloadUrl = ""), true, false))
    }

    @Test
    fun retryIsBoundedToOneAndOnlyForGoneHttpStatuses() {
        val failed = offer()
        val fresh = failed.copy(versionCode = 102)
        assertNull(replacementDownloadOffer(404, failed, fresh, true, true))
        assertNull(replacementDownloadOffer(410, failed, fresh, true, true))
        for (status in listOf(200, 403, 429, 500)) {
            assertNull(replacementDownloadOffer(status, failed, fresh, true, false))
        }
    }

    @Test
    fun retryRejectsChannelSwitchAndReturnToOriginalChannel() {
        val failed = offer()
        val fresh = failed.copy(versionCode = 102)
        assertNull(replacementDownloadOffer(404, failed, fresh.copy(channel = UpdateChannel.STABLE), true, false))
        assertNull(replacementDownloadOffer(404, failed, fresh, false, false))
        val selectionIsCurrent = updateRequestIsCurrent(UpdateChannel.NIGHTLY, 4, UpdateChannel.NIGHTLY, 6)
        assertNull(replacementDownloadOffer(404, failed, fresh, selectionIsCurrent, false))
    }
}
