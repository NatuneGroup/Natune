/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import java.io.File

/**
 * The media the playback tests decode. `fixtures/make-playback-fixtures.sh` generates these files,
 * and the committed copies are that script's output.
 */
internal object PlaybackFixtures {
    /** Length of the WAV, FLAC and OGG ramps. The MP3 and the streams are close to this value. */
    const val RAMP_DURATION_MS: Long = 4_000L
    const val RAMP_SAMPLE_RATE: Int = 48_000

    private const val RAMP_PERIOD = 4_096L

    private val directory: File = File(
        requireNotNull(PlaybackFixtures::class.java.classLoader.getResource("playback")) {
            "the playback fixtures are missing from the test resources"
        }.toURI(),
    )

    /** The directory the fixture HTTP server serves at its URL root. */
    val root: File get() = directory

    fun localPath(name: String): String {
        val file = directory.resolve(name)
        check(file.isFile) { "missing playback fixture $name" }
        return file.absolutePath
    }

    /**
     * The value the ramp fixtures hold at frame [index]. The ramp repeats every 4096 frames, so one
     * value does not identify its frame; tests compare whole runs of samples instead.
     */
    fun rampSample(index: Long): Float {
        val phase = index.mod(RAMP_PERIOD) - RAMP_PERIOD / 2
        return phase.toFloat() / RAMP_PERIOD
    }
}
