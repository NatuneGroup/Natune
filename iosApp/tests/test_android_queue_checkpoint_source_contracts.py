"""Android checkpoint source relationships only; no Kotlin, JVM or player execution."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/src/main/kotlin/moe/rukamori/archivetune"
PLAYBACK = ANDROID / "playback"


class AndroidQueueCheckpointSourceContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = (PLAYBACK / "MusicService.kt").read_text()
        cls.gate = (PLAYBACK / "QueueCheckpointGate.kt").read_text()
        cls.session = (PLAYBACK / "SessionTimelinePlayer.kt").read_text()

    def test_checkpoint_items_and_occurrences_use_the_installed_identity(self):
        blocked = self.gate.split("fun blocked(): Boolean", 1)[1]
        self.assertNotIn("pendingReplacement", blocked)
        self.assertIn("if (mediaItemCount() > 0", blocked)
        snapshot = (PLAYBACK / "QueueCheckpointSnapshot.kt").read_text()
        self.assertIn("else persistenceQueue", snapshot)
        self.assertIn("title = persistenceTitle", snapshot)
        self.assertIn("identity as? YouTubePlaylistQueue", snapshot)
        live_snapshot = self.service.split("internal fun snapshotQueue()", 1)[1].split(
            "internal suspend fun writeQueueSnapshot", 1
        )[0]
        self.assertIn(
            "queueCheckpointGate.persistQueue(player, mediaItemsSnapshot, currentMediaItemIndex, currentPosition)",
            live_snapshot,
        )
        self.assertNotIn("currentQueue).toPersistQueue", live_snapshot)

    def test_auto_installs_are_intercepted_at_the_session_player_after_resolution(self):
        callback = (PLAYBACK / "MediaLibrarySessionCallback.kt").read_text()
        resolver = callback.split("override fun onSetMediaItems", 1)[1].split(
            "override fun onAddMediaItems", 1
        )[0]
        self.assertIn("scope.future(Dispatchers.IO)", resolver)
        self.assertNotIn("installExternalTimeline", resolver)
        session_factory = self.service.split("private fun sessionFacingPlayer()", 1)[1].split(
            "private var secondaryCrossfadePlayer", 1
        )[0]
        self.assertIn("queueSessionTimelinePlayer", session_factory)
        self.assertIn("installExternalTimeline(queue, title, target = target, install = install)", session_factory)
        self.assertIn("target === currentPlayer()", self.session)
        installer = self.service.split("internal fun installExternalTimeline", 1)[1].split("fun clearAutomix", 1)[0]
        self.assertLess(installer.index("if (target !== player) return"), installer.index("invalidatePlaylistWork()"))
        for method in ("setMediaItems", "setMediaItem"):
            overrides = re.findall(r"override fun " + method + r"\([^\n]*", self.session)
            self.assertEqual(3, len(overrides), method)
        self.assertIn("override fun clearMediaItems() = install(emptyList())", self.session)
        self.assertIn("if (removal) gate.onUserRemoval() else gate.onUserAddition()", self.session)
        self.assertIn("gate.persistenceQueue.takeIf { retained }", self.session)

    def test_all_listen_together_direct_setters_and_clears_use_the_shared_installer(self):
        manager = (ANDROID / "listentogether/ListenTogetherManager.kt").read_text()
        setters = [line for line in manager.splitlines() if re.search(r"\bplayer\.setMediaItems\(", line)]
        clears = [line for line in manager.splitlines() if re.search(r"\bplayer\.clearMediaItems\(", line)]
        self.assertEqual(6, len(setters))
        self.assertEqual(1, len(clears))
        for line in setters + clears:
            self.assertIn("connection.service.installExternalTimeline", line)
            self.assertIn("target = player", line)

    def test_loader_cancellation_failure_and_supersession_finish_their_pending_state(self):
        invalidation = self.service.split("private fun invalidatePlaylistWork()", 1)[1].split(
            "private fun publishPlaylistLoadState", 1
        )[0]
        cancellation = self.service.split("fun cancelPlaylistLoading()", 1)[1].split(
            "fun retryPlaylistLoading()", 1
        )[0]
        self.assertIn("onReplacementFinished(currentQueue)", invalidation)
        self.assertIn("onReplacementFinished(currentQueue)", cancellation)
        self.assertIn("saveQueueToDisk()", cancellation)
        finite = self.service.split("private fun startFinitePlaylistLoad", 1)[1].split("fun playQueue(", 1)[0]
        initial = self.service.split("fun playQueue(", 1)[1].split("private fun applyCurrentFirstShuffleOrder", 1)[0]
        for loader in (finite, initial):
            self.assertIn("onReplacementFinished(queue)", loader)
        transaction = self.gate.split("fun installExternalTimeline", 1)[1].split("fun onUserClear", 1)[0]
        self.assertLess(transaction.index("install()"), transaction.index("acceptIdentity()"))
        self.assertIn("finally", transaction)
        self.assertIn("save()", transaction)

    def test_radio_acceptance_follows_insertion_and_blocks_nested_recommendation_work(self):
        seamless = self.service.split("fun startRadioSeamlessly()", 1)[1].split(
            "internal fun installExternalTimeline", 1
        )[0]
        self.assertIn("installExternalTimeline(radioQueue, initialStatus.title)", seamless)
        self.assertNotIn("currentQueue = radioQueue", seamless)
        self.assertIn("attachRadio = { installExternalTimeline", self.service)
        self.assertIn("timelineIdentity = { queueCheckpointGate.persistenceQueue }", self.service)
        self.assertIn("cancelRecommendations = false", self.service)
        self.assertIn("!queueCheckpointGate.timelineMutationInProgress", self.service)
        infinite = (PLAYBACK / "AndroidInfiniteQueue.kt").read_text()
        self.assertLess(infinite.index("if (append.isNotEmpty())"), infinite.index("attachRadio(source)"))
        self.assertLess(infinite.index("target.addMediaItems(append)"), infinite.index("attachRadio(source)"))
        self.assertIn("if (source !== timelineIdentity()) attachRadio(source)", infinite)

    def test_automatic_filtering_stays_separate_from_explicit_empty_acceptance(self):
        filtering = self.service.split("private inline fun removeQueueItems", 1)[1].split(
            "private suspend fun restorePersistentQueue", 1
        )[0]
        self.assertNotIn("installExternalTimeline", filtering)
        self.assertNotIn("onUserRemoval", filtering)
        self.assertIn("currentQueue as? YouTubePlaylistQueue", filtering)
        removal = self.gate.split("fun onUserRemoval()", 1)[1].split("private fun acceptUserIdentity", 1)[0]
        self.assertNotIn("initialTimelineCleared", removal)
        self.assertIn("emptyTimelineAccepted = mediaItemCount() == 0", removal)
        self.assertIn("if (emptyTimelineAccepted)", removal)
        self.assertIn("persistenceQueue = EmptyQueue", removal)
        self.assertIn("savedTitle = null", removal)
        blocked = self.gate.split("fun blocked(): Boolean", 1)[1]
        self.assertIn("unresolvedFinite(queue()) || unresolvedFinite(persistenceQueue)", blocked)
        self.assertIn("!finite.confirmedExhausted || finite.snapshot.continuation != null", blocked)


if __name__ == "__main__":
    unittest.main()
