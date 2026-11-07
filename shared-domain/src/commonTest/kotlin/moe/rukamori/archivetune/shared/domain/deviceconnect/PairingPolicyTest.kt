package moe.rukamori.archivetune.shared.domain.deviceconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingPolicyTest {
    private val initiatorRole = DeviceConnectRole.INITIATOR
    private val responderRole = DeviceConnectRole.RESPONDER

    private fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() }

    @Test
    fun commitmentBindsRoleDeviceNonceAndKey() {
        val nonce = bytes(16, 1)
        val key = bytes(65, 2)
        val first = DeviceConnectPairing.commitment(initiatorRole, "0123456789abcdef", nonce, key)
        val again = DeviceConnectPairing.commitment(initiatorRole, "0123456789abcdef", nonce, key)
        assertContentEquals(first, again)
        assertNotEquals(
            first.toHex(),
            DeviceConnectPairing.commitment(responderRole, "0123456789abcdef", nonce, key).toHex(),
        )
        assertNotEquals(
            first.toHex(),
            DeviceConnectPairing.commitment(initiatorRole, "fedcba9876543210", nonce, key).toHex(),
        )
    }

    @Test
    fun transcriptIsTheSameOnBothSides() {
        val initiatorNonce = bytes(16, 3)
        val responderNonce = bytes(16, 4)
        val initiatorKey = bytes(65, 5)
        val responderKey = bytes(65, 6)
        val initiatorCommit = DeviceConnectPairing.commitment(initiatorRole, "0123456789abcdef", initiatorNonce, initiatorKey)
        val responderCommit = DeviceConnectPairing.commitment(responderRole, "fedcba9876543210", responderNonce, responderKey)
        val first = DeviceConnectPairing.transcript(
            initiatorCommit, responderCommit, "0123456789abcdef", "fedcba9876543210",
            initiatorNonce, responderNonce, initiatorKey, responderKey,
        )
        val second = DeviceConnectPairing.transcript(
            initiatorCommit, responderCommit, "0123456789abcdef", "fedcba9876543210",
            initiatorNonce, responderNonce, initiatorKey, responderKey,
        )
        assertContentEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun hkdfMatchesRfc5869TestCaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = DeviceConnectPairing.HkdfSha256.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun sasAgreesFromTheSameSecretAndDiffersFromAnother() {
        val secret = bytes(32, 7)
        val same = DeviceConnectPairing.sas(secret)
        assertEquals(6, same.length)
        assertEquals(same, DeviceConnectPairing.sas(secret.copyOf()))
        assertTrue(DeviceConnectPairing.matchesSas(same, same))
        assertFalse(DeviceConnectPairing.matchesSas(same, "000000"))
        assertNotEquals(same, DeviceConnectPairing.sas(bytes(32, 8)))
    }

    @Test
    fun proofsAreTranscriptAndPhaseBound() {
        val key = bytes(32, 9)
        val transcript = bytes(32, 10)
        val auth = DeviceConnectPairing.proof(key, "auth", initiatorRole, "0123456789abcdef", "fedcba9876543210", transcript)
        val same = DeviceConnectPairing.proof(key, "auth", initiatorRole, "0123456789abcdef", "fedcba9876543210", transcript)
        assertTrue(DeviceConnectPairing.verify(auth, same))
        assertFalse(
            DeviceConnectPairing.verify(
                auth,
                DeviceConnectPairing.proof(key, "approve", initiatorRole, "0123456789abcdef", "fedcba9876543210", transcript),
            ),
        )
        assertFalse(
            DeviceConnectPairing.verify(
                auth,
                DeviceConnectPairing.proof(key, "auth", initiatorRole, "0123456789abcdef", "fedcba9876543210", bytes(32, 11)),
            ),
        )
    }

    @Test
    fun helloValidationRejectsWrongSizesAndIds() {
        val nonce = bytes(16, 12).toHex()
        val key = bytes(65, 13).toHex()
        assertTrue(DeviceConnectPairing.validHello(nonce, key, "0123456789abcdef"))
        assertFalse(DeviceConnectPairing.validHello(bytes(15, 12).toHex(), key, "0123456789abcdef"))
        assertFalse(DeviceConnectPairing.validHello(nonce, bytes(64, 13).toHex(), "0123456789abcdef"))
        assertFalse(DeviceConnectPairing.validHello(nonce, key, "not-a-device"))
    }
}
