/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import com.atilika.kuromoji.ipadic.Tokenizer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.zip.Inflater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

private val actualPack: ByteArray by lazy {
    Path.of(Tokenizer::class.java.protectionDomain.codeSource.location.toURI()).toFile().readBytes()
}

private class PackSettings(initial: PreferenceSnapshot?) {
    val snapshots = MutableStateFlow(initial)
    private val mutex = Mutex()
    var fail = false
    suspend fun edit(change: PreferenceEditor.() -> Unit) = mutex.withLock {
        check(!fail)
        val editor = requireNotNull(snapshots.value).toEditor()
        editor.change()
        snapshots.value = editor.snapshot()
    }
}

/** Real bounded files/atomic rename and real raw-DEFLATE input, with controllable transfer scheduling. */
private class TempPackIo : JapanesePackIo {
    private val base = Path.of(System.getProperty("user.dir"), "build").also { Files.createDirectories(it) }
    val directory: Path = Files.createTempDirectory(base, "japanese-pack-")
    val installed: Path = directory.resolve("kuromoji-ipadic-0.9.0.jar")
    val staged: Path = directory.resolve("kuromoji-ipadic-0.9.0.jar.partial")
    var downloads = 0
    var promotions = 0
    var inflations = 0
    var corrupt = false
    var failPromotion = false
    var failRemoval = false
    var failStagedCleanup = false
    var promotionRelease: CompletableDeferred<Unit>? = null
    var installedReadRelease: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var hang = false
    private fun read(path: Path): ByteArray? {
        if (!Files.exists(path)) return null
        require(Files.size(path) <= KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES)
        return Files.newInputStream(path).use { input ->
            input.readNBytes(KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES + 1).also {
                require(it.size <= KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES)
            }
        }
    }
    override suspend fun readInstalled(): ByteArray? { installedReadRelease?.await(); return read(installed) }
    override suspend fun readStaged() = read(staged)
    override suspend fun prepare() { discardStaged() }
    override suspend fun download(progress: (Long, Long) -> Unit) {
        downloads++
        progress(0, actualPack.size.toLong())
        if (hang) awaitCancellation()
        if (ignoreCancellation) withContext(NonCancellable) { release?.await() } else release?.await()
        Files.newOutputStream(staged).use { output ->
            var offset = 0
            while (offset < actualPack.size) {
                val count = minOf(64_000, actualPack.size - offset)
                output.write(actualPack, offset, count)
                offset += count
            }
        }
        if (corrupt) {
            val bytes = Files.readAllBytes(staged)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(staged, bytes)
        }
        progress(actualPack.size.toLong(), actualPack.size.toLong())
    }
    override suspend fun promote() {
        check(!failPromotion)
        Files.move(staged, installed, ATOMIC_MOVE, REPLACE_EXISTING)
        promotions++
        promotionRelease?.await()
    }
    override suspend fun discardStaged() { check(!failStagedCleanup); Files.deleteIfExists(staged) }
    override suspend fun removeInstalled() { check(!failRemoval); Files.deleteIfExists(installed) }
    override suspend fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray {
        inflations++
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedSize + 1)
            var size = 0
            while (!inflater.finished()) {
                currentCoroutineContext().ensureActive()
                val n = inflater.inflate(output, size, minOf(64_000, output.size - size))
                check(n > 0 || inflater.finished())
                size += n
                check(size <= expectedSize)
            }
            check(size == expectedSize && inflater.remaining == 0)
            return output.copyOf(size)
        } finally { inflater.end() }
    }
    fun cleanup() {
        Files.deleteIfExists(staged)
        Files.deleteIfExists(installed)
        Files.delete(directory)
    }
}

class JapanesePackLifecycleTest {
    private suspend fun TestScope.scenario(
        initial: PreferenceSnapshot? = PreferenceSnapshot.EMPTY,
        installed: ByteArray? = null,
        region: String? = "JP",
        network: Boolean = false,
        stagingFailure: Boolean = false,
        networkProbe: suspend () -> Boolean = { network },
        installedReadRelease: CompletableDeferred<Unit>? = null,
        body: suspend TestScope.(JapaneseLanguagePackManager, PackSettings, TempPackIo) -> Unit,
    ) {
        val io = TempPackIo()
        io.failStagedCleanup = stagingFailure
        io.installedReadRelease = installedReadRelease
        installed?.let { Files.write(io.installed, it) }
        val settings = PackSettings(initial)
        val manager = JapaneseLanguagePackManager(this, StandardTestDispatcher(testScheduler),
            settings.snapshots, settings::edit, io, { region }, networkProbe)
        try {
            runCurrent()
            body(manager, settings, io)
        } finally {
            io.release?.complete(Unit)
            io.promotionRelease?.complete(Unit)
            io.installedReadRelease?.complete(Unit)
            manager.close()
            io.cleanup()
        }
    }

    @Test fun offlineForegroundConsumesPersistedConsiderationOnlyOnce() = runTest {
        scenario { manager, settings, io ->
            manager.onForeground()?.join()
            assertEquals(true, settings.snapshots.value?.get(autoAttemptedKey))
            assertEquals(null, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertNull(manager.onForeground())
            assertEquals(0, io.downloads)
            assertEquals(JapaneseLanguagePackState.NotInstalled, manager.state.value)
        }
    }

    @Test fun nonJpPrimaryRegionDoesNotConsumeOrDownload() = runTest {
        scenario(region = "US", network = true) { manager, settings, io ->
            manager.onForeground()?.join()
            assertNull(settings.snapshots.value?.get(autoAttemptedKey))
            assertEquals(0, io.downloads)
        }
    }

    @Test fun explicitOffRemovedAndPriorAttemptSuppressAutomaticInstall() = runTest {
        for (prefs in listOf(
            PreferenceSnapshot.of(LyricsRomanizeJapaneseKey to false),
            PreferenceSnapshot.of(removedKey to true),
            PreferenceSnapshot.of(autoAttemptedKey to true),
        )) scenario(initial = prefs, network = true) { manager, _, io ->
            manager.onForeground()?.join()
            advanceUntilIdle()
            assertEquals(0, io.downloads)
        }
    }

    @Test fun persistedOfflineAttemptPreventsLaterOnlineReconsideration() = runTest {
        var saved = PreferenceSnapshot.EMPTY
        scenario { manager, settings, _ ->
            manager.onForeground()?.join()
            saved = requireNotNull(settings.snapshots.value)
        }
        scenario(initial = saved, network = true) { manager, _, io ->
            manager.onForeground()?.join()
            advanceUntilIdle()
            assertEquals(0, io.downloads)
        }
    }

    @Test fun missingInitialSettingsAreNotTreatedAsDefaults() = runTest {
        scenario(initial = null, network = true) { manager, settings, io ->
            manager.onForeground()
            runCurrent()
            assertEquals(0, io.downloads)
            settings.snapshots.value = PreferenceSnapshot.of(LyricsRomanizeJapaneseKey to false)
            advanceUntilIdle()
            assertEquals(0, io.downloads)
            assertNull(settings.snapshots.value?.get(autoAttemptedKey))
        }
    }

    @Test fun manualInstallUsesActualReadyTokenizerAndOfflineReopen() = runTest {
        val explicitOff = PreferenceSnapshot.of(LyricsRomanizeJapaneseKey to false, removedKey to true)
        scenario(initial = explicitOff) { manager, settings, io ->
            manager.install()?.join()
            assertEquals(JapaneseLanguagePackState.Installed(actualPack.size.toLong()), manager.state.value)
            assertEquals(1, io.promotions)
            assertEquals(8, io.inflations)
            assertEquals(false, settings.snapshots.value?.get(removedKey))
            assertEquals(true, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertEquals("ニホンゴ", manager.tokenizerOrNull()?.tokenize("日本語")?.single()?.reading)
            assertNull(manager.install())
            assertEquals(1, io.downloads)
        }
        scenario(installed = actualPack) { manager, _, io ->
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertNotNull(manager.tokenizerOrNull())
            manager.onForeground()?.join()
            assertEquals(0, io.downloads)
        }
    }

    @Test fun automaticOnlineInstallIsSingleAndPublishesOnlyAfterValidation() = runTest {
        scenario(network = true) { manager, settings, io ->
            io.release = CompletableDeferred()
            manager.onForeground()?.join()
            runCurrent()
            assertTrue(manager.state.value is JapaneseLanguagePackState.Downloading)
            assertNull(manager.tokenizerOrNull())
            assertNull(settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            io.release?.complete(Unit)
            advanceUntilIdle()
            assertEquals(true, settings.snapshots.value?.get(autoAttemptedKey))
            assertEquals(true, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertEquals(1, io.downloads)
        }
    }

    @Test fun choiceChangedDuringTransferIsNotAutoEnabledAndOffDetachesMemory() = runTest {
        scenario(network = true) { manager, settings, io ->
            io.release = CompletableDeferred()
            manager.onForeground()?.join()
            runCurrent()
            settings.edit { this[LyricsRomanizeJapaneseKey] = false }
            io.release?.complete(Unit)
            advanceUntilIdle()
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertEquals(false, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertNull(manager.tokenizerOrNull())
            settings.edit { this[LyricsRomanizeJapaneseKey] = true }
            runCurrent()
            assertNotNull(manager.tokenizerOrNull())
            settings.edit { this[LyricsRomanizeJapaneseKey] = false }
            assertNull(manager.tokenizerOrNull()) // no wait for asynchronous observer
        }
    }

    @Test fun manualInstallDoesNotOverrideChangedChoice() = runTest {
        scenario { manager, settings, io ->
            io.release = CompletableDeferred()
            manager.install()
            runCurrent()
            settings.edit { this[LyricsRomanizeJapaneseKey] = false }
            io.release?.complete(Unit)
            advanceUntilIdle()
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertEquals(false, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertNull(manager.tokenizerOrNull())
        }
    }

    @Test fun cancelRejectsLateTaskAndAllowsManualRetryWithoutStaleCleanup() = runTest {
        scenario { manager, _, io ->
            io.release = CompletableDeferred()
            io.ignoreCancellation = true
            val old = manager.install()
            runCurrent()
            manager.cancel()
            assertEquals(JapaneseLanguagePackState.NotInstalled, manager.state.value)
            val retry = manager.install()
            runCurrent()
            io.release?.complete(Unit)
            old?.join()
            retry?.join()
            assertEquals(2, io.downloads)
            assertEquals(1, io.promotions)
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertNotNull(manager.tokenizerOrNull())
            assertFalse(Files.exists(io.staged))
        }
    }

    @Test fun removalPersistsSuppressionAndRejectsLateTransferPublication() = runTest {
        scenario { manager, settings, io ->
            io.release = CompletableDeferred()
            io.ignoreCancellation = true
            manager.install()
            runCurrent()
            val removal = manager.remove()
            runCurrent()
            assertEquals(JapaneseLanguagePackState.Removing, manager.state.value)
            assertNull(manager.tokenizerOrNull())
            io.release?.complete(Unit)
            removal?.join()
            assertEquals(true, settings.snapshots.value?.get(removedKey))
            assertEquals(false, settings.snapshots.value?.get(LyricsRomanizeJapaneseKey))
            assertEquals(0, io.promotions)
            assertFalse(Files.exists(io.installed))
            assertFalse(Files.exists(io.staged))
            assertEquals(JapaneseLanguagePackState.NotInstalled, manager.state.value)
            manager.onForeground()?.join()
            assertEquals(1, io.downloads)
        }
    }

    @Test fun corruptInstalledAndStagedArchivesNeverInvokeInflaterOrBecomeReady() = runTest {
        val corrupt = actualPack.copyOf().also { it[0] = 0 }
        scenario(installed = corrupt) { manager, _, io ->
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.INVALID), manager.state.value)
            assertFalse(Files.exists(io.installed))
            assertEquals(0, io.inflations)
            assertNull(manager.tokenizerOrNull())
        }
        scenario { manager, _, io ->
            io.corrupt = true
            manager.install()?.join()
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL), manager.state.value)
            assertEquals(0, io.promotions)
            assertEquals(0, io.inflations)
            assertFalse(Files.exists(io.staged))
        }
    }

    @Test fun oversizedInstalledFileIsRejectedByRealIoBeforeArchiveParsing() = runTest {
        scenario(installed = ByteArray(actualPack.size + 1)) { manager, _, io ->
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.INVALID), manager.state.value)
            assertEquals(0, io.inflations)
            assertFalse(Files.exists(io.installed))
        }
    }

    @Test fun promotionAndSettingsFailuresDoNotPublishOrLeavePromotedData() = runTest {
        scenario { manager, _, io ->
            io.failPromotion = true
            manager.install()?.join()
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL), manager.state.value)
            assertFalse(Files.exists(io.installed))
            assertNull(manager.tokenizerOrNull())
        }
        scenario { manager, settings, io ->
            settings.fail = true
            manager.install()?.join()
            assertEquals(1, io.promotions)
            assertFalse(Files.exists(io.installed))
            assertNull(manager.tokenizerOrNull())
        }
    }

    @Test fun failedRemovalDetachesTokenizerAndExposesRetryState() = runTest {
        scenario(installed = actualPack) { manager, settings, io ->
            io.failRemoval = true
            manager.remove()?.join()
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.REMOVE, true), manager.state.value)
            assertNull(manager.tokenizerOrNull())
            assertEquals(true, settings.snapshots.value?.get(removedKey))
            io.failRemoval = false
            manager.remove()?.join()
            assertEquals(JapaneseLanguagePackState.NotInstalled, manager.state.value)
        }
    }

    @Test fun timeoutIsAnInstallErrorNotAUserCancellation() = runTest {
        scenario { manager, _, io ->
            io.hang = true
            manager.install()
            runCurrent()
            advanceTimeBy(180_001)
            runCurrent()
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL), manager.state.value)
            assertNull(manager.tokenizerOrNull())
        }
    }

    @Test fun closedOwnerCannotRestartOrPublish() = runTest {
        scenario { manager, _, io ->
            manager.close()
            assertNull(manager.install())
            assertNull(manager.onForeground())
            assertNull(manager.remove())
            assertEquals(0, io.downloads)
            assertNull(manager.tokenizerOrNull())
        }
    }

    @Test fun cancelledAtomicPromotionCannotResurrectAnUnpublishedInstallOnRestart() = runTest {
        scenario { manager, _, io ->
            io.promotionRelease = CompletableDeferred()
            val installation = manager.install()
            runCurrent()
            assertEquals(1, io.promotions)
            assertNull(manager.tokenizerOrNull())
            manager.cancel()
            io.promotionRelease?.complete(Unit)
            installation?.join()
            assertFalse(Files.exists(io.installed))
            assertEquals(JapaneseLanguagePackState.NotInstalled, manager.state.value)
        }
    }

    @Test fun failedStalePartialDeletionDoesNotDestroyAValidInstalledArchive() = runTest {
        scenario(installed = actualPack, stagingFailure = true) { manager, _, io ->
            assertTrue(Files.exists(io.installed))
            assertTrue(manager.state.value is JapaneseLanguagePackState.Installed)
            assertNotNull(manager.tokenizerOrNull())
        }
    }

    @Test fun staleConnectivityCannotReinstallAfterEvenAFailedRemoval() = runTest {
        val connectivity = CompletableDeferred<Unit>()
        scenario(networkProbe = { connectivity.await(); true }) { manager, settings, io ->
            val consideration = manager.onForeground()
            runCurrent()
            assertEquals(true, settings.snapshots.value?.get(autoAttemptedKey))
            settings.fail = true
            manager.remove()?.join()
            connectivity.complete(Unit)
            consideration?.join()
            assertEquals(0, io.downloads)
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.REMOVE, true), manager.state.value)
            assertNull(manager.tokenizerOrNull())
        }
    }

    @Test fun delayedStartupCannotRestoreReadinessAfterFailedRemovalOrAutoRetry() = runTest {
        val read = CompletableDeferred<Unit>()
        scenario(installed = actualPack, network = true, installedReadRelease = read) { manager, settings, io ->
            settings.fail = true
            manager.remove()?.join()
            read.complete(Unit)
            runCurrent()
            assertNull(manager.tokenizerOrNull())
            settings.fail = false
            manager.onForeground()?.join()
            assertEquals(0, io.downloads)
            assertNull(manager.tokenizerOrNull())
            assertEquals(JapaneseLanguagePackState.Failed(JapanesePackFailure.REMOVE, true), manager.state.value)
        }
    }
}
