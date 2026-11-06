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
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairGrant
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairInfo
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
            val promptA = awaitPrompt(listenerA)
            val promptB = awaitPrompt(listenerB)
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
            awaitPrompt(firstB)
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

    /**
     * Only the responder kept a grant: the initiator asks to pair and the responder answers the hello
     * with its stored-secret proof. That proof is unverifiable on both sides, so it must be ignored
     * instead of closing the connection, and pairing must still finish after the user approves it.
     */
    @Test
    fun aResponderThatKeptTheGrantStillPairsWithADeviceThatLostIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val staleSecret = ByteArray(32) { 7 }
            val pairsA = MemoryPairRepository()
            val pairsB = MemoryPairRepository()
            pairsB.save(
                DevicePairGrant(
                    DevicePairInfo(identityA.installId, "Laptop", DevicePlatform.DESKTOP),
                    staleSecret,
                ),
            )
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
            val promptA = awaitPrompt(listenerA)
            val promptB = awaitPrompt(listenerB)
            assertEquals(promptA.code, promptB.code)
            assertTrue(promptA.isRequester)
            assertFalse(promptB.isRequester)
            // The responder writes its stored-grant proof from its own hello, so it has crossed the wire
            // before the pair request is answered. The initiator must ignore it: no failure, no close
            // and no authentication may come out of it.
            delay(300)
            assertNull(listenerA.failures.tryReceive().getOrNull())
            assertNull(listenerB.failures.tryReceive().getOrNull())
            assertNull(listenerA.authenticated)
            assertNull(listenerB.authenticated)
            assertFalse(listenerA.closed)
            b.approvePairing()
            awaitAuthenticated(listenerA, listenerB)
            val newSecretA = assertNotNull(pairsA.secret(identityB.installId))
            val newSecretB = assertNotNull(pairsB.secret(identityA.installId))
            assertContentEquals(newSecretA, newSecretB)
            assertFalse(newSecretA.contentEquals(staleSecret), "the stale grant must be replaced by the pairing")
            a.close()
            b.close()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitAuthenticated(first: RecordingListener, second: RecordingListener) {
        withTimeout(5_000) {
            while (first.authenticated == null || second.authenticated == null) {
                first.failures.tryReceive().getOrNull()?.let { throw AssertionError("initiator failed: ${it.code} ${it.message}") }
                second.failures.tryReceive().getOrNull()?.let { throw AssertionError("responder failed: ${it.code} ${it.message}") }
                delay(10)
            }
        }
    }

    private suspend fun awaitPrompt(listener: RecordingListener) = withTimeout(5_000) {
        while (true) {
            listener.failures.tryReceive().getOrNull()?.let { throw AssertionError("connection failed: ${it.code} ${it.message}") }
            listener.prompts.tryReceive().getOrNull()?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }
}
