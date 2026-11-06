package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceConnectConnection
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform

class DeviceConnectHandshakeTest {
    private val identityA = DeviceIdentity("a".repeat(16), "Laptop", DevicePlatform.DESKTOP)
    private val identityB = DeviceIdentity("b".repeat(16), "Phone", DevicePlatform.ANDROID)

    @Test
    fun sixDigitCodeAgreesAndPairingStoresTheSameSecret() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pairsA = MemoryPairRepository()
            val pairsB = MemoryPairRepository()
            val listenerA = RecordingListener()
            val listenerB = RecordingListener()
            val (channelA, channelB) = loopbackChannels(scope)
            val a = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, channelA, scope, "Phone", listener = listenerA,
            )
            val b = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER, identityB, JvmDeviceCrypto(), pairsB, channelB, scope, "Laptop", listener = listenerB,
            )
            a.start()
            b.start()
            val promptA = withTimeout(5_000) { listenerA.prompts.receive() }
            val promptB = withTimeout(5_000) { listenerB.prompts.receive() }
            assertEquals(promptA.code, promptB.code)
            assertEquals(6, promptA.code.length)
            assertTrue(promptA.isRequester)
            assertFalse(promptB.isRequester)
            b.approvePairing()
            awaitAuthenticated(listenerA, listenerB)
            val secretA = assertNotNull(pairsA.secret(identityB.installId))
            val secretB = assertNotNull(pairsB.secret(identityA.installId))
            assertContentEquals(secretA, secretB)
            a.close()
            b.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun grantedDevicesAuthenticateWithoutADialogAndAChangedSecretIsRefused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pairsA = MemoryPairRepository()
            val pairsB = MemoryPairRepository()
            val firstA = RecordingListener()
            val firstB = RecordingListener()
            val (channelA, channelB) = loopbackChannels(scope)
            val a = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, channelA, scope, "Phone", listener = firstA,
            )
            val b = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER, identityB, JvmDeviceCrypto(), pairsB, channelB, scope, "Laptop", listener = firstB,
            )
            a.start()
            b.start()
            withTimeout(5_000) { firstB.prompts.receive() }
            b.approvePairing()
            awaitAuthenticated(firstA, firstB)
            a.close()
            b.close()

            val secondA = RecordingListener()
            val secondB = RecordingListener()
            val (againA, againB) = loopbackChannels(scope)
            val a2 = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, againA, scope, "Phone", listener = secondA,
            )
            val b2 = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER, identityB, JvmDeviceCrypto(), pairsB, againB, scope, "Laptop", listener = secondB,
            )
            a2.start()
            b2.start()
            awaitAuthenticated(secondA, secondB)
            assertNull(secondA.prompts.tryReceive().getOrNull())
            assertNull(secondB.prompts.tryReceive().getOrNull())
            a2.close()
            b2.close()

            pairsB.replaceSecret(identityA.installId, ByteArray(32) { 9 })
            val thirdA = RecordingListener()
            val thirdB = RecordingListener()
            val (badA, badB) = loopbackChannels(scope)
            val a3 = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, badA, scope, "Phone", listener = thirdA,
            )
            val b3 = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER, identityB, JvmDeviceCrypto(), pairsB, badB, scope, "Laptop", listener = thirdB,
            )
            a3.start()
            b3.start()
            val failure = withTimeout(5_000) {
                var error: moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError? = null
                while (error == null) {
                    error = thirdA.failures.tryReceive().getOrNull() ?: thirdB.failures.tryReceive().getOrNull()
                    if (error == null) delay(10)
                }
                error
            }
            assertEquals(
                moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.UNAUTHENTICATED,
                assertNotNull(failure).code,
            )
            a3.close()
            b3.close()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitAuthenticated(first: RecordingListener, second: RecordingListener) {
        withTimeout(5_000) {
            while (first.authenticated == null || second.authenticated == null) delay(10)
        }
    }
}
