package app.natune.shared.domain.source

import app.natune.shared.domain.source.AudioSourceType.AMAZON
import app.natune.shared.domain.source.AudioSourceType.APPLE
import app.natune.shared.domain.source.AudioSourceType.DEEZER
import app.natune.shared.domain.source.AudioSourceType.JIOSAAVN
import app.natune.shared.domain.source.AudioSourceType.QOBUZ
import app.natune.shared.domain.source.AudioSourceType.QOBUZ_BACKUP
import app.natune.shared.domain.source.AudioSourceType.MODULES
import app.natune.shared.domain.source.AudioSourceType.QQ
import app.natune.shared.domain.source.AudioSourceType.SPOTIFY
import app.natune.shared.domain.source.AudioSourceType.TIDAL
import app.natune.shared.domain.source.AudioSourceType.YOUTUBE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterizes the built-in source priority and the stored-order migration. The order is the shipped
 * Android order: JioSaavn sits before Amazon there, and YouTube is always the terminal fallback.
 */
class SourceOrderPolicyTest {
    @Test
    fun defaultOrderIsTheShippedPriorityWithYouTubeLast() {
        assertEquals(
            listOf(TIDAL, QOBUZ, QOBUZ_BACKUP, DEEZER, APPLE, JIOSAAVN, AMAZON, QQ, MODULES, SPOTIFY, YOUTUBE),
            AudioSourceConfig.DEFAULT_ORDER,
        )
        assertEquals(YOUTUBE, AudioSourceConfig.DEFAULT_ORDER.last())
    }

    @Test
    fun everySourceAppearsExactlyOnceInTheDefaultOrder() {
        assertEquals(AudioSourceType.entries.size, AudioSourceConfig.DEFAULT_ORDER.size)
        assertEquals(AudioSourceType.entries.toSet(), AudioSourceConfig.DEFAULT_ORDER.toSet())
    }

    @Test
    fun onlyTidalAndYouTubeAreEnabledOutOfTheBox() {
        for (source in AudioSourceType.entries) {
            assertEquals(source == TIDAL || source == YOUTUBE, AudioSourceConfig.DEFAULT_ENABLED[source], source.name)
        }
    }

    @Test
    fun aMissingOrBlankStoredOrderMeansTheDefaultOrder() {
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(null))
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(""))
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(" , ,bogus"))
    }

    @Test
    fun aStoredOrderKeepsTheUsersArrangementAndInsertsNewSourcesAboveYouTube() {
        // DEEZER was moved above TIDAL. Every source the stored order lacks goes directly above YouTube.
        assertEquals(
            listOf(DEEZER, TIDAL, QOBUZ, QOBUZ_BACKUP, APPLE, JIOSAAVN, AMAZON, QQ, MODULES, SPOTIFY, YOUTUBE),
            AudioSourceConfig.parseOrder("DEEZER,TIDAL,YOUTUBE"),
        )
    }

    @Test
    fun unknownAndRepeatedNamesAreDroppedFromAStoredOrder() {
        assertEquals(
            listOf(TIDAL, QOBUZ, QOBUZ_BACKUP, DEEZER, APPLE, JIOSAAVN, AMAZON, QQ, MODULES, SPOTIFY, YOUTUBE),
            AudioSourceConfig.parseOrder("bogus, tidal ,TIDAL,YOUTUBE,youtube"),
        )
    }

    @Test
    fun aStoredOrderWithoutYouTubeStillEndsWithYouTube() {
        val order = AudioSourceConfig.parseOrder("TIDAL,QOBUZ")

        assertEquals(TIDAL, order.first())
        assertEquals(YOUTUBE, order.last())
        assertEquals(AudioSourceType.entries.size, order.size)
    }

    @Test
    fun migrationWritesTheResolverOrderBackAndChangesNothingOnASecondRun() {
        val migrated = AudioSourceConfig.migrateStoredOrder("TIDAL,QOBUZ")

        assertEquals("TIDAL,QOBUZ,QOBUZ_BACKUP,DEEZER,APPLE,JIOSAAVN,AMAZON,QQ,MODULES,SPOTIFY,YOUTUBE", migrated)
        assertEquals(migrated, AudioSourceConfig.migrateStoredOrder(migrated))
    }

    @Test
    fun migrationLeavesABlankValueBlankSoItKeepsTrackingTheDefault() {
        assertEquals("", AudioSourceConfig.migrateStoredOrder(null))
        assertEquals("", AudioSourceConfig.migrateStoredOrder(""))
        assertEquals("  ", AudioSourceConfig.migrateStoredOrder("  "))
    }

    @Test
    fun aStoredNameTrimsAndIgnoresCaseButAnUnknownNameIsRejected() {
        assertEquals(TIDAL, parseAudioSourceType(" tidal "))
        assertEquals(QOBUZ_BACKUP, parseAudioSourceType("qobuz_backup"))
        assertNull(parseAudioSourceType("qobuz-backup"))
        assertNull(parseAudioSourceType(""))
    }
}
