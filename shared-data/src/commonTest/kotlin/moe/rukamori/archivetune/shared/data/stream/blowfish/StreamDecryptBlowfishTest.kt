package moe.rukamori.archivetune.shared.data.stream.blowfish

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamDecryptBlowfishTest {
    @Test
    fun ericYoungSingleBlockVectorsEncryptAndDecrypt() {
        assertVector("0000000000000000", "0000000000000000", "4ef997456198dd78")
        assertVector("ffffffffffffffff", "ffffffffffffffff", "51866fd5b85ecb8a")
        assertVector("3000000000000000", "1000000000000001", "7d856f9a613063f2")
    }

    @Test
    fun piDerivedTablesStartWithKnownWords() {
        assertEquals(18, BLOWFISH_P_INIT.size)
        assertEquals(1024, BLOWFISH_S_INIT.size)
        assertEquals(0x243F6A88, BLOWFISH_P_INIT[0])
        assertEquals(0x85A308D3.toInt(), BLOWFISH_P_INIT[1])
        assertEquals(0x3AC372E6, BLOWFISH_S_INIT[1023])
    }

    private fun assertVector(
        keyHex: String,
        plainHex: String,
        cipherHex: String,
    ) {
        val cipher = Blowfish(hex(keyHex))
        val plain = hex(plainHex)
        val encrypted = ByteArray(8)
        cipher.encryptBlock(plain, 0, encrypted, 0)
        assertContentEquals(hex(cipherHex), encrypted)
        val decrypted = ByteArray(8)
        cipher.decryptBlock(encrypted, 0, decrypted, 0)
        assertContentEquals(plain, decrypted)
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
