package moe.rukamori.archivetune.shared.domain.platform

import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/** Reuse the existing portable implementation instead of introducing a second digest engine. */
object ContentDigest {
    fun sha256(bytes: ByteArray): String = Sha256.hexDigest(bytes)
}
