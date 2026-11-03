package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsIdleParkTest {
    @Test
    fun atNormalSpeedTheParkIsTheMediaGap() {
        assertEquals(200L, lyricsIdleParkMs(nextChangeMs = 1_200L, feedMs = 1_000L, speed = 1f, minParkMs = 16L, maxParkMs = 250L))
    }

    @Test
    fun fasterPlaybackParksForLessWallTime() {
        // 2x: a 400 ms media gap passes in 200 ms of wall time, so sleeping 400 would skip the next sweep.
        assertEquals(200L, lyricsIdleParkMs(nextChangeMs = 1_400L, feedMs = 1_000L, speed = 2f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(50L, lyricsIdleParkMs(nextChangeMs = 1_100L, feedMs = 1_000L, speed = 2f, minParkMs = 16L, maxParkMs = 250L))
    }

    @Test
    fun slowerPlaybackParksLongerUpToTheCap() {
        assertEquals(200L, lyricsIdleParkMs(nextChangeMs = 1_100L, feedMs = 1_000L, speed = 0.5f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = 1_200L, feedMs = 1_000L, speed = 0.5f, minParkMs = 16L, maxParkMs = 250L))
    }

    @Test
    fun theParkStaysInsideItsBounds() {
        assertEquals(16L, lyricsIdleParkMs(nextChangeMs = 1_004L, feedMs = 1_000L, speed = 1f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = 9_000L, feedMs = 1_000L, speed = 1f, minParkMs = 16L, maxParkMs = 250L))
    }

    @Test
    fun noBoundaryOrNoValidSpeedPollsAtTheCap() {
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = null, feedMs = 1_000L, speed = 1f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = 1_100L, feedMs = 1_000L, speed = 0f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = 1_100L, feedMs = 1_000L, speed = -1f, minParkMs = 16L, maxParkMs = 250L))
        assertEquals(250L, lyricsIdleParkMs(nextChangeMs = 1_100L, feedMs = 1_000L, speed = Float.NaN, minParkMs = 16L, maxParkMs = 250L))
    }
}
