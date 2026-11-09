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
        cls.installer = (PLAYBACK / "ExternalQueueTimelineInstallation.kt").read_text()
        cls.loading = (PLAYBACK / "QueueLoadingPolicy.kt").read_text()

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

    def test_explicit_empty_without_a_timeline_event_reaches_both_save_paths(self):
        snapshot = self.service.split("internal fun snapshotQueue()", 1)[1].split(
            "internal suspend fun writeQueueSnapshot", 1
        )[0]
        self.assertIn("if (queueCheckpointGate.blocked()) return null", snapshot)
        self.assertIn(
            "queueCheckpointGate.canSnapshotEmpty(hasQueueTimelineChanges, queueRestoreCompleted.value)",
            snapshot,
        )
        self.assertIn(
            "hasAcceptedEmptyCheckpoint get() = queueCheckpointGate.hasAcceptedEmptyCheckpoint",
            self.service,
        )
        teardown = (PLAYBACK / "QueueTeardownPersistence.kt").read_text()
        self.assertIn(
            "shouldSave(enabled, player.mediaItemCount, hasQueueTimelineChanges, hasAcceptedEmptyCheckpoint)",
            teardown,
        )
        self.assertIn("snapshotQueue()", teardown)

    def test_auto_installs_are_intercepted_at_the_session_player_after_resolution(self):
        callback = (PLAYBACK / "MediaLibrarySessionCallback.kt").read_text()
        resolver = callback.split("override fun onSetMediaItems", 1)[1].split(
            "override fun onAddMediaItems", 1
        )[0]
        self.assertIn("scope.future(Dispatchers.IO)", resolver)
        self.assertNotIn("installExternalTimeline", resolver)
        session_factory = self.service.split("private fun recoveringPlayer()", 1)[1].split(
            "private var secondaryCrossfadePlayer", 1
        )[0]
        self.assertIn("queueSessionTimelinePlayer", session_factory)
        self.assertIn("installExternalTimeline(queue, title, target = target", session_factory)
        self.assertIn("retainsInstalledOccurrences = replay", session_factory)
        self.assertIn("ErrorRecoveringPlayer", session_factory)
        self.assertIn("OutputFadingPlayer(recoveringPlayer(), outputFades)", session_factory)
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
        self.assertIn("onSourceDetached(acceptEmptyTimeline = true)", removal)
        blocked = self.gate.split("fun blocked(): Boolean", 1)[1]
        self.assertIn("unresolvedFinite(queue()) || unresolvedFinite(persistenceQueue)", blocked)
        self.assertIn("!finite.confirmedExhausted || finite.snapshot.continuation != null", blocked)

    def test_service_replay_decision_guards_all_cancellation_and_preserves_identity(self):
        installer = self.service.split("internal fun installExternalTimeline", 1)[1].split(
            "private fun detachQueueSource", 1
        )[0]
        self.assertIn("installServiceQueueTimeline(queueCheckpointGate, queue, retainsInstalledOccurrences", installer)
        obsolete = installer.split("cancelObsoleteWork = {", 1)[1].split("install = install", 1)[0]
        for action in ("cancelRestoredQueueHydration()", "invalidatePlaylistWork()",
                       "cancelInfiniteQueueBootstrap()", "initialQueueLoadGeneration++", "queueRemovals.reset()"):
            self.assertIn(action, obsolete)
        self.assertIn("!retainsInstalledOccurrences || requestedQueue == null || requestedQueue !== installedQueue", self.installer)
        self.assertIn("if (replacement) cancelObsoleteWork()", self.installer)
        self.assertIn("retainIdentity = !replacement", self.installer)
        self.assertIn("if (!retainIdentity) onExternalTimelineInstalled(acceptIdentity())", self.gate)
        self.assertIn("retained, mutation", self.session)

    def test_all_empty_source_resets_detach_through_one_gate_method(self):
        reset = self.service.split("private fun detachQueueSource", 1)[1].split("fun clearAutomix", 1)[0]
        self.assertEqual(1, len(re.findall(r"currentQueue\s*=\s*EmptyQueue", self.service)))
        for statement in ("currentQueue = EmptyQueue", "queueTitle = null", "initialQueueLoadInProgress = false",
                          "queueCheckpointGate.onSourceDetached(acceptEmptyTimeline)", "saveQueueToDisk()"):
            self.assertIn(statement, reset)
        filtering = self.service.split("private inline fun removeQueueItems", 1)[1].split(
            "private suspend fun restorePersistentQueue", 1
        )[0]
        self.assertLess(filtering.index("currentQueue as? YouTubePlaylistQueue"), filtering.index("detachQueueSource()"))
        disabled = self.service.split("fun onInfiniteQueueDisabled()", 1)[1].split("fun onInfiniteQueueEnabled", 1)[0]
        self.assertIn("currentQueue !is YouTubePlaylistQueue", disabled)
        self.assertIn("detachQueueSource()", disabled)
        for start, end in (("fun clearQueueWithUndo", "fun onInfiniteQueueDisabled"),
                           ("fun stopAndClearPlayback", "private fun trimResolutionCaches")):
            self.assertIn("detachQueueSource(acceptEmptyTimeline = true)", self.service.split(start, 1)[1].split(end, 1)[0])
        detached = self.gate.split("fun onSourceDetached", 1)[1].split("fun onUserClear", 1)[0]
        self.assertIn("mediaItemCount() == 0 && unresolvedFinite(persistenceQueue)", detached)
        self.assertIn("persistenceQueue = EmptyQueue", detached)
        self.assertIn("savedTitle = null", detached)
        self.assertIn("sourceDetached && queue() === EmptyQueue", self.gate)

    def test_transition_loading_waits_for_the_accepted_queue_and_scoped_mutation_exit(self):
        finite = self.service.split("private fun startFinitePlaylistLoad", 1)[1].split("fun playQueue(", 1)[0]
        self.assertIn("!queueLoadingAllowed()", finite)
        allowed = self.service.split("private fun queueLoadingAllowed", 1)[1].split("private fun setFiniteShuffleIndices", 1)[0]
        self.assertIn("queueCheckpointGate.timelineMutationInProgress", allowed)
        self.assertIn("evaluateAcceptedQueueLoading", allowed)
        self.assertIn("!suppressed && !hydrating && !finiteMutation && !timelineMutation", self.loading)
        evaluation = self.loading.split("internal fun evaluateAcceptedQueueLoading", 1)[1].split("internal fun Player", 1)[0]
        self.assertLess(evaluation.index("if (!allowed) return"), evaluation.index("let(startFinite)"))
        self.assertLess(evaluation.index("if (!allowed) return"), evaluation.index("considerRecommendations(queue)"))
        transition = self.service.split("override fun onMediaItemTransition", 1)[1].split("private fun armNextTrackPrefetchWatcher", 1)[0]
        timeline = self.service.split("override fun onTimelineChanged", 1)[1].split("override fun onDeviceInfoChanged", 1)[0]
        self.assertIn("evaluateQueueLoading", transition)
        self.assertNotIn("startFinitePlaylistLoad", transition)
        self.assertIn("evaluateQueueLoading()", timeline)
        self.assertLess(self.installer.index("gate.installExternalTimeline"), self.installer.index("evaluateLoading()"))

    def test_stale_loading_cleanup_belongs_to_the_last_publisher(self):
        publisher = (PLAYBACK / "PlaylistLoadStatePublisher.kt").read_text()
        self.assertIn("if (publisher !== owner) return false", publisher)
        finite = self.service.split("private fun startFinitePlaylistLoad", 1)[1].split("fun playQueue(", 1)[0]
        self.assertIn("PlaylistLoadState(loading = true, showDialog = needsInitial), ticket", finite)
        cleanup = finite.split("else if (finitePlaylistPublisher.release(ticket))", 1)[1]
        self.assertIn("retryFinitePlaylistLoad = null", cleanup)
        self.assertIn("setFinitePlaylistState(PlaylistLoadState())", cleanup)
        whole = self.service.split("fun wholeYouTubePlaylist", 1)[1].split("private fun queueLoadingAllowed", 1)[0]
        self.assertIn("wholePlaylistPublisher.release(ownership)", whole)
        self.assertIn("!ownership.isCurrent(playlistActionGeneration, initialQueueLoadGeneration)", whole)
        dismiss = self.service.split("fun dismissPlaylistLoadError", 1)[1].split("fun reloadFinitePlaylist", 1)[0]
        self.assertIn("finitePlaylistPublisher.owner", dismiss)
        self.assertIn("wholePlaylistPublisher.owner", dismiss)

    def test_offline_recovery_setters_forward_through_the_queue_installer(self):
        factory = self.service.split("private fun recoveringPlayer()", 1)[1].split(
            "private var secondaryCrossfadePlayer", 1
        )[0]
        self.assertRegex(factory, r"ErrorRecoveringPlayer\(\s*queueSessionTimelinePlayer\(player, queueCheckpointGate")
        self.assertIn("mediaLibrarySessionCallback.autoOfflineSongs", factory)
        self.assertIn("waitingForNetworkConnection.value = false; recoveryRetry.cancelPending()", factory)
        recovering = (PLAYBACK / "ErrorRecoveringPlayer.kt").read_text()
        replacement = recovering.split("private fun replaceItems", 1)[1].split("override fun setMediaItems", 1)[0]
        self.assertIn("target.setMediaItems(items, selection.sourceIndex, selection.startPositionMs)", replacement)
        for method in ("setMediaItems", "setMediaItem"):
            self.assertEqual(3, len(re.findall(r"override fun " + method + r"\(", recovering)))
        self.assertIn("skipUnavailableOfflineItem(player, mediaLibrarySessionCallback.autoOfflineSongs)", self.service)

    def test_offline_auto_resolution_snapshots_and_stale_tap_rejection_survive_the_merge(self):
        callback = (PLAYBACK / "MediaLibrarySessionCallback.kt").read_text()
        resolver = callback.split("override fun onSetMediaItems", 1)[1].split("override fun onAddMediaItems", 1)[0]
        for path in ("offlineSnapshotItems", "offlineOnlinePlaylistItems", "queueEntryPlayback", "pinnedPlaylistPlayback"):
            self.assertIn(path, resolver)
        self.assertIn("autoOfflineSongs.onUserRetry()", resolver)
        self.assertNotIn(".setMediaItems(", resolver)
        snapshots = (PLAYBACK / "AutoOfflineBrowseSnapshots.kt").read_text()
        self.assertIn("browseFolderUpdates.snapshot(parentId)", snapshots)
        self.assertIn("resolveAutoPlaybackItem(item)", snapshots)
        queue_ids = (PLAYBACK / "AutoQueueMediaIds.kt").read_text()
        self.assertIn("it in queueMediaIds.indices && queueMediaIds[it] == entry.songMediaId", queue_ids)
        extensions = (PLAYBACK / "AutoBrowseExtensions.kt").read_text()
        tap = extensions.split("internal suspend fun MediaLibrarySessionCallback.queueEntryPlayback", 1)[1].split(
            "internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistEntries", 1
        )[0]
        self.assertIn("AutoQueueMediaIds.resolvePosition", tap)
        self.assertIn("MediaSession.MediaItemsWithStartPosition(items, selection.sourceIndex, selection.startPositionMs)", tap)


if __name__ == "__main__":
    unittest.main()
