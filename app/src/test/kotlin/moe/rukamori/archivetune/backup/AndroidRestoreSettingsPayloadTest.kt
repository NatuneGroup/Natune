package moe.rukamori.archivetune.backup

import java.io.ByteArrayOutputStream
import moe.rukamori.archivetune.shared.data.persistence.LegacyDataStorePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** A legacy protobuf payload only contributes the keys the selected categories carry. */
class AndroidRestoreSettingsPayloadTest {
    @Test
    fun aProtobufPayloadMergesOnlyTheKeysTheSelectionCarries() {
        val payload =
            preferencesProto(
                "playerDesignStyle" to "FLAMINGO",
                "spotify_sp_dc" to "catalog-account",
                "spotifyStreamSpDc" to "stream-account",
                "spotifyStreamDeviceId" to "another-install-device",
                "secure_store_youtube" to "ciphertext",
                "poolTidalAccounts" to "sealed-cache",
                "tidalRefreshToken" to "sign-in",
            )

        assertEquals(setOf("playerDesignStyle"), filteredKeys(payload, settings = true, account = false))
        assertEquals(
            setOf("spotify_sp_dc", "spotifyStreamSpDc", "tidalRefreshToken"),
            filteredKeys(payload, settings = false, account = true),
        )
        // A full selection still drops device-bound and secure-store values instead of installing the raw file.
        assertEquals(
            setOf("playerDesignStyle", "spotify_sp_dc", "spotifyStreamSpDc", "tidalRefreshToken"),
            filteredKeys(payload, settings = true, account = true),
        )
    }

    /** The stage conversion composes these two calls before the payload is written as XML. */
    private fun filteredKeys(payload: ByteArray, settings: Boolean, account: Boolean): Set<String> {
        val snapshot = LegacyDataStorePreferences.read(payload)
        return LegacyDataStorePreferences
            .filtered(snapshot) { name -> BackupPreferenceSelection.includes(name, settings, account) }
            .keys
            .map { it.name }
            .toSet()
    }

    private fun preferencesProto(vararg entries: Pair<String, String>): ByteArray =
        entries.fold(ByteArray(0)) { bytes, (name, value) -> bytes + entry(name, string(42, value)) }

    private fun entry(name: String, value: ByteArray): ByteArray = blob(10, string(10, name) + blob(18, value))

    private fun string(tag: Int, text: String): ByteArray = blob(tag, text.encodeToByteArray())

    private fun blob(tag: Int, bytes: ByteArray): ByteArray = varint(tag.toLong()) + varint(bytes.size.toLong()) + bytes

    private fun varint(number: Long): ByteArray =
        ByteArrayOutputStream().also { output ->
            var value = number
            do {
                val next = value and 127
                value = value ushr 7
                output.write((next or if (value != 0L) 128L else 0L).toInt())
            } while (value != 0L)
        }.toByteArray()
}
