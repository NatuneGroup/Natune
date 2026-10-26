/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import java.io.ByteArrayOutputStream
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.models.PlaybackPersistenceCodec
import moe.rukamori.archivetune.shared.data.persistence.LegacyJavaQueueReader
import moe.rukamori.archivetune.shared.domain.persistence.LegacyQueueDocument

/** Typed Android capture retains the authoritative format. No new arbitrary-object deserializer. */
object AndroidLegacyQueueCapture {
    fun capture(queue: PersistQueue): LegacyQueueDocument {
        val bytes = ByteArrayOutputStream().also { PlaybackPersistenceCodec.write(it, queue) }.toByteArray()
        return LegacyJavaQueueReader().read(bytes)
    }
}
