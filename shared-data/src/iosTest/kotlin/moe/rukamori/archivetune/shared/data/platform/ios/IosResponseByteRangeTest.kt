package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException

class IosResponseByteRangeTest {
    @Test fun aCompletePartialResponseContinuesUntilTheResourceEnds() {
        val first = parseIosResponseByteRange("bytes 0-1023/2048", 0)
        assertEquals(1024L, first.endExclusive)
        assertTrue(first.hasMoreBytes)
        assertFalse(parseIosResponseByteRange("bytes 1024-2047/2048", 1024).hasMoreBytes)
        assertTrue(parseIosResponseByteRange("bytes 0-1023/*", 0).hasMoreBytes)
    }

    @Test fun refusesWrongOffsetsReversedRangesAndImpossibleLengths() {
        for (header in listOf(null, "bytes 1-99/100", "bytes 0-100/100", "bytes 0-9223372036854775807/*", "bytes 0-10/no")) {
            assertFailsWith<ByteSourceException> { parseIosResponseByteRange(header, 0) }
        }
        assertFailsWith<ByteSourceException> { parseIosResponseByteRange("bytes 10-9/100", 10) }
    }
}
