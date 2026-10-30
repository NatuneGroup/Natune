package moe.rukamori.archivetune.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TdLibLoadCompatibilityTest {
    @Test
    fun nougatIsNotRejectedWithoutAnActualLinkerFailure() {
        for (sdkInt in listOf(24, 25)) {
            val compatibility = TdLibLoadCompatibility(sdkInt)
            assertFalse(compatibility.androidVersionUnsupported)
            assertEquals("NativeLibraryMissing", compatibility.unavailableStateName)
        }
    }

    @Test
    fun anAvailableLibraryRejectedOnNougatReportsAndroidVersion() {
        for (sdkInt in listOf(24, 25)) {
            val compatibility = TdLibLoadCompatibility(sdkInt)
            compatibility.recordFailure(UnsatisfiedLinkError("Native symbol unavailable"), libraryPresent = true)
            assertTrue(compatibility.androidVersionUnsupported)
            assertEquals("AndroidVersion", compatibility.unavailableStateName)
        }
    }

    @Test
    fun aMissingSlimLibraryDoesNotBlockItsFirstDownload() {
        val compatibility = TdLibLoadCompatibility(24)
        compatibility.recordFailure(UnsatisfiedLinkError("Native library missing"), libraryPresent = false)
        assertFalse(compatibility.androidVersionUnsupported)
        assertEquals("NativeLibraryMissing", compatibility.unavailableStateName)
    }

    @Test
    fun otherFailuresAndModernAndroidDoNotClaimAnUnsupportedVersion() {
        val nougat = TdLibLoadCompatibility(25)
        nougat.recordFailure(SecurityException("Load denied"), libraryPresent = true)
        assertFalse(nougat.androidVersionUnsupported)
        for (sdkInt in listOf(26, 37)) {
            val compatibility = TdLibLoadCompatibility(sdkInt)
            compatibility.recordFailure(UnsatisfiedLinkError("Native symbol unavailable"), libraryPresent = true)
            assertFalse(compatibility.androidVersionUnsupported)
            assertEquals("NativeLibraryMissing", compatibility.unavailableStateName)
        }
    }

    @Test
    fun aKnownIncompatibilityStaysLatchedForLaterStartupAttempts() {
        val compatibility = TdLibLoadCompatibility(25)
        compatibility.recordFailure(UnsatisfiedLinkError("Native symbol unavailable"), libraryPresent = true)
        compatibility.recordFailure(UnsatisfiedLinkError("Native library missing"), libraryPresent = false)
        assertTrue(compatibility.androidVersionUnsupported)
        assertEquals("AndroidVersion", compatibility.unavailableStateName)
    }
}
