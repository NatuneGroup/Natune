# Android queue checkpoint identity

The queue checkpoint keeps the requested `MusicService.currentQueue` separate from
the identity used to serialize the playing queue.
The object-stream persistence format is unchanged. YouTube still resolves through
`YTPlayerUtils` → InnerTubeX → NewPipe → Echo.

## Installation and acceptance

`QueueCheckpointGate.persistenceQueue` identifies the installed timeline. Its
title and finite occurrence mapping stay attached to that identity while another
queue prepares. A nonempty retained timeline remains eligible for queue, position
and teardown saves throughout preparation, empty radio results and failure.
`pendingReplacement` never blocks a checkpoint.

The initial loader clear is a temporary mutation, rather than installation.
Loader clear/preload/insertion callbacks cannot accept user edits inside the
scoped mutation. Successful preload/page installation advances the identity.
User edits after the loader's owned clear can accept that finite queue without
changing its loader retry flags. Edits before the clear retain the previous
identity, including after cancellation during suspended preload filtering.

Failure, cancellation and supersession abandon their pending request and request
a checkpoint when the owning loader exits. The playlist dialog does so directly,
because it invalidates the ticket before the cancelled coroutine's `finally`.
Non-transient timeline changes also drop pending replacement state. The scoped
mutation guard unwinds in `finally`; recommendation work cannot start inside it.

An empty timeline belonging to an unresolved finite playlist still preserves the
last meaningful checkpoint. This covers filtered preloads, failed first pages,
pending/failed continuations, and manual additions subsequently removed by
filtering. Explicit user removal to empty, explicit clear, confirmed finite
exhaustion and external installation can accept emptiness without waiting for a
loader. An accepted empty removal binds the empty-list persistence identity;
subsequent nonempty loader insertion resets empty acceptance before any filtering.
Startup restoration and its chunked hydration retain their existing
transient guards and explicitly bind the restored identity.

Explicit clear/removal and successful external-empty installation also record an
accepted empty checkpoint independently of timeline notifications. Scheduled and
teardown saves honor that acceptance when a filtered cold restore installed no
items and a later clear produces no timeline event. The empty snapshot still
requires completed restoration and a truly empty player; automatic detachment
alone cannot bypass the startup guard or unresolved finite protection. Later
nonempty timelines and new replacements invalidate the earlier empty acceptance.
`QueueEmptyCheckpointTest` covers both writers against the object-stream file,
including removal of the old playlist, title, items and continuation. It uses no
URI conversion; JVM execution remains a CI gate.

## External installation routes

All external installations use `MusicService.installExternalTimeline` and the
small `installServiceQueueTimeline` helper. Its pure `externalTimelineReplacesQueue`
decision requires both the installed identity and proof of the same occurrence
objects to recognize a replay. Replays preserve hydration/backfill jobs, finite
requests, retry/UI state, generations, pending replacement state and currentQueue;
their scoped mutation skips identity acceptance. A pending replacement can still
finish after a tap on the retained queue. A new occurrence list or source refresh
without that proof cancels obsolete work and invalidates initial-load generations.
The new identity is accepted after the
mutation succeeds; a thrown mutation retains the previous identity and requests
a checkpoint. Installers targeting a replaced player are ignored before queue
ownership changes; stale session edits cannot accept the active player's emptiness.

| Entry point | Route |
| --- | --- |
| Android Auto `onSetMediaItems`, voice and browse results | `sessionFacingPlayer` wraps the actual player in `SessionTimelinePlayer`; all six `setMediaItem(s)` overloads run the shared installer after resolution |
| Auto existing-queue tap and add-current-to-playlist replay | The same occurrence objects preserve the installed identity/title, current request, hydration, finite load and retry state |
| MediaSession add/remove/replace/move and clear | Adds/removes/reorders use explicit edit acceptance; clear uses the shared external installer |
| Listen Together `SYNC_QUEUE`, preserving current song/position | Both direct setters use the shared installer |
| Listen Together state with no current track | Queue setter and explicit clear use the shared installer |
| Listen Together current track absent from its queue | Single-track fallback setter uses the shared installer |
| Listen Together current track present in its queue | Indexed queue setter uses the shared installer |
| Listen Together state without a queue | Single-track setter uses the shared installer |
| Seamless radio | Tail removal/insertion runs inside the shared installer; an empty result retains the previous identity |
| Automix/infinite queue bootstrap and continuation | A source that does not yet own the persistence identity runs through the shared installer after a nonempty append; it does not cancel its own recommendation job |
| Current audio-source refresh | Timeline recreation runs through the shared installer with the existing persistence identity and title |

Finite loaders and startup hydration bind their owned identity directly.
Hydration's prefix/suffix additions and prepared secondary/crossfade timeline
copies preserve the same logical queue. User insertion and removal/undo retain
their explicit acceptance/timeline callbacks. Automatic filtering remains
separate and never invokes external installation or user-removal acceptance.

## Replay, detachment and loading guards

The service decision, rather than only the session wrapper, owns the replay
exception. `ExternalQueueTimelineInstallationTest` drives the actual installation
helper with a two-second startup backfill and a suspended first-page request. A
same-occurrence seek leaves those jobs live and the checkpoint includes their
later items. Equal media IDs on new MediaItems still replace the queue.

Every runtime source reset goes through `QueueCheckpointGate.onSourceDetached`.
The service's `detachQueueSource` clears the source/title and obsolete initial-load
guard, then requests a checkpoint. Automatic filtering of a non-finite source,
disabling its automatic loading, clear-with-undo and stop/clear all use it. The
gate routes external empty/list acceptance, user clear and empty removal through
the same method. Generic preparation assigns a requested queue, rather than
resetting an installed source; its identity still advances only on installation.

Detachment keeps an unresolved finite checkpoint while automatic filtering leaves
the timeline empty. The finite filtering path also keeps its actual source and
continuation loader. If an obsolete non-finite request drops a previous finite
source, the gate remembers that the source was detached: an unrelated manual
addition then becomes an ordinary list. Explicit empty acceptance and confirmed
exhaustion can detach immediately. A restored list cannot inherit the old album
identity or continuation; `QueueSourceDetachmentTest` checks actual serialization
and `toContinuationQueue`, as well as checkpoint/position/teardown eligibility.

`queueLoadingAllowed` suppresses finite starts during hydration, finite insertion,
playback suppression and the gate's synchronous timeline mutation. Both timeline
and media-item transition callbacks use `evaluateAcceptedQueueLoading`; they
cannot start an old finite loader or trigger automatic loading inside that scope.
`installServiceQueueTimeline` evaluates again after successful identity acceptance
and scope exit. The accepted queue decides whether more finite items are needed;
finite membership still ignores the recommendations toggle and repeat policy.

Finite and whole-action loaders publish UI state with their ownership ticket.
On every stale exit, the last publisher alone may clear its loading/dialog state
and retry action. An older coroutine cannot clear a newer loader's state or job.
Dismissal changes dialog visibility while preserving its publisher. Existing
infinite-queue generation cleanup is unchanged. Queue loading/traversal/shuffle
helpers live in small files; the moved shuffle and recommendation policies retain
their previous behavior and allow MusicService to shrink.

## Android Auto offline recovery

The final session path is `NowPlayingMetadataPlayer` → `OutputFadingPlayer` →
`ErrorRecoveringPlayer` → `SessionTimelinePlayer` → the raw service player.
Recovery validates offline availability before its indexed `setMediaItems`
call reaches the queue wrapper. The wrapper's captured target still matches the
active raw service player. This preserves checkpoint acceptance and stale-player
rejection for all six recovery setter overloads, including the additional setter
inside `ErrorRecoveringPlayer.replaceItems`.

`MediaLibrarySessionCallback.onSetMediaItems` keeps every offline selection path: voice/library/local playback, retained Home/online/pinned browse
snapshots and indexed queue taps. It returns resolved items to Media3, whose actual
setter uses the composed session player. Offline queue taps retain the complete
occurrence list and use `selection.sourceIndex`, so recovery preserves hydration
and finite loading even when unavailable rows precede the tapped duplicate.
`AutoQueueMediaIds.resolvePosition` still rejects stale stamped positions.
`AutoOfflineAvailability`, cache/download eligibility, `AutoOfflineBrowseSnapshots`
and automatic `skipUnavailableOfflineItem` behavior remain intact; skip changes
position without replacing or pruning the queue.

`OfflineSessionTimelinePlayerTest` covers the composed offline recovery path for
all six setters, the exact duplicate occurrence during hydration/finite loading,
and rejection of an unavailable explicit tap before checkpoint mutation. Python
contracts check the same wrapper order, offline resolver paths, retained
snapshots and stale-tap rejection. Offline protections and their tests:

| Offline protection | Covering test |
| --- | --- |
| Bounded connection failure, cache playback and validated retry semantics | `AutoOfflineAvailabilityTest.aSourceConnectionFailurePrecedesTheNetworkCallbackAndIsNotClearedByCachedPlayback`; `anExplicitRetryGoesOnlineOnlyWhenTheNetworkIsValidated` |
| Local/completed/fully cached rows keep their order and selected identity/position | `AutoOfflineSongsTest.offlineFilteringKeepsOnlyLocalCompletedAndFullyCachedRowsInOriginalOrder`; `filteringRemapsAnExplicitSelectionWithoutChangingItsIdentityOrPosition` |
| A missing or unavailable explicit tap cannot select another song | `AutoOfflineSongsTest.aMissingOrUnavailableTapNeverSubstitutesAnotherSong`; `ErrorRecoveringPlayerTest.anUnavailableExplicitTapOrRevokedCacheCannotChooseAnUnrelatedSong` |
| A stale stamped queue position cannot remap to an earlier duplicate occurrence | `AutoQueueMediaIdsTest.resolvePosition_rejectsAMovedEntryInsteadOfRemappingIt`; `resolvePosition_aTapFilteredOutOfItsStampedPositionNeverSelectsAnEarlierDuplicate` |
| Retained online browse snapshots keep visible order and are rechecked without a network reload | `AutoOfflineSongsTest.anOfflineTapReadsTheRetainedOnlineSnapshotInItsVisibleOrderWithoutReloading` |
| Offline navigation and automatic skipping preserve every queue entry and play intent | `ErrorRecoveringPlayerTest.offlineNextSkipsUnavailableItemsWithoutRemovingThemAndKeepsPlayIntent`; `anAutomaticTransitionOntoAnUnavailableEntrySkipsItWithoutChangingTheQueue` |
| Recovery prepares an idle source error once and rejects reentrant/superseded navigation | `ErrorRecoveringPlayerTest.everyTransportSeekPreparesAnIdleSourceErrorExactlyOnceAndKeepsPlayIntent`; `reentrantNavigationFromRecoveryAndSeekCallbacksCannotSkipTwice`; `aQueueChangedDuringRecoveryNeverReceivesTheOldSelection` |

## Checkpoint protections and regression coverage

Checkpoint protections, with the tests that retain them. The Python contracts
validate wiring only.

| Protection | Covering test |
| --- | --- |
| Retained nonempty items, position and teardown keep the installed identity while radio or finite replacement prepares | `QueueCheckpointIdentityTest.pendingReplacement_writesCurrentItemsPositionAndTeardownWithThePreviousIdentity` |
| A pending finite replacement cannot borrow its own title or occurrence map for the retained queue | `QueueCheckpointIdentityTest.pendingFiniteReplacement_usesThePreviousOccurrenceMappingAndTitle` |
| Radio preload metadata alone cannot accept its identity | `QueueCheckpointReplacementTest.radioPreloadPresence_doesNotAcceptTheReplacementUntilItIsInstalled` |
| Failed/empty uninstalled radio keeps the previous checkpoint, including later external installation | `QueueCheckpointIdentityTest.failedOrEmptyRadioThenExternalInstallation_acceptsTheExternalIdentityAndKeepsSaving` |
| Slow finite preparation and the loader's temporary empty clear cannot erase the last checkpoint | `QueueCheckpointSchedulerTest.slowFinitePreparation_preservesThePreviousCheckpointBeforeTheInitialClear` |
| Initial failure without installation keeps the previous timeline and teardown checkpoint | `QueueCheckpointSchedulerTest.untouchedInitialFailure_preservesThePreviousCheckpointIncludingAtTeardown` |
| Cancellation during suspended preload filtering leaves before-clear edits under the retained identity | `QueueCheckpointIdentityTest.cancellationDuringSuspendedPreloadPreparation_keepsAddRemoveAndEmptyRemovalSaveable` |
| Manual additions after the owned clear can accept a finite identity without resetting retry state | `QueueCheckpointSchedulerTest.manualAdditionAfterInitialFailure_checkpointsWithoutChangingLoaderRetryState`; `manualAdditionDuringInitialRequest_remainsAcceptedAfterTheRequestFails` |
| An accepted manual finite timeline survives failure and is saved at teardown | `QueueCheckpointSchedulerTest.teardownAfterInitialFailure_writesTheAcceptedManualQueue` |
| Filtered preloads, pending/failed first pages and pending/failed continuations cannot accept unresolved emptiness | `QueueCheckpointSchedulerTest.preloadFilteredToEmpty_whileFirstPagePending_preservesThePlaylistCheckpoint`; `preloadFilteredToEmpty_afterFirstPageFailure_preservesThePlaylistCheckpoint`; `emptyFilteredFinitePage_preservesThePreviousCheckpointThroughNextPageFailure` |
| Automatic removal of a finite manual addition still preserves the unresolved saved checkpoint | `QueueCheckpointReplacementTest.automaticFilteringAfterFailedFirstPageAndManualAdd_preservesThePlaylistCheckpoint` |
| Empty acceptance never leaks from a manual removal through later loader insertion and filtering | `QueueCheckpointIdentityTest.loaderInsertionAfterAcceptedEmptyRemoval_doesNotAcceptLaterFilteringToEmpty` |
| A nonempty manual removal cannot accept a later automatically filtered empty queue | `QueueCheckpointSchedulerTest.nonemptyManualRemoval_doesNotAcceptALaterFilteredEmptyQueue` |
| Explicit last-item removal, clear and confirmed exhaustion can save emptiness | `QueueCheckpointSchedulerTest.manualRemovalOfLastPreloadAfterFirstPageFailure_acceptsAnEmptyCheckpointAndTeardown`; `preloadFilteredToEmpty_explicitClear_allowsAnEmptyCheckpoint`; `preloadFilteredToEmpty_afterConfirmedFinalPage_allowsAnEmptyCheckpoint` |
| A new finite replacement resets prior manual-queue acceptance | `QueueCheckpointSchedulerTest.newFiniteReplacement_resetsAcceptanceOfThePreviousManualQueue` |
| An exhausted but uninstalled replacement cannot override the previous unresolved finite source's phase | `QueueCheckpointIdentityTest.filteringThePreviousUnresolvedFiniteQueue_cannotUseAnUninstalledExhaustedReplacementsPhase` |
| Filtering every loaded finite item preserves the continuation; later accepted loading still checkpoints | `QueueCheckpointSchedulerTest.filteringAllLoadedFiniteItems_doesNotEraseTheContinuation`; `manualAdditionBeforeAcceptance_doesNotLetAFilteredEmptyPageEraseTheContinuation` |
| Nonempty retained queues allow position writes even before replacement acceptance | `QueueCheckpointSchedulerTest.retainedTimelineDuringFailedReplacement_savesPositionWithThePreviousIdentity` |
| Every MediaSession setter accepts only after the actual resolved timeline mutation | `SessionTimelinePlayerTest.everySessionSetter_acceptsTheExternalIdentityAfterItsActualMutation`; Python `test_auto_installs_are_intercepted_at_the_session_player_after_resolution` |
| Session replay keeps the previous identity/title; session edits and clear retain explicit acceptance | `SessionTimelinePlayerTest.queueTapReplayingTheSameOccurrences_keepsThePreviousQueueIdentityAndTitle`; `sessionAddAndRemoveAfterCancelledPreparation_keepsTheRetainedIdentityAndAcceptsEmptyRemoval`; `sessionClear_acceptsAnEmptyTimelineEvenWithAnUnresolvedFiniteReplacement` |
| Stale player setters and edits cannot accept the active player's unresolved emptiness | `SessionTimelinePlayerTest.staleSessionAfterPlayerReplacement_cannotAcceptAnUnresolvedEmptyTimeline` |
| Listen Together's six direct setters and clear all use the shared active-player installer | Python `test_all_listen_together_direct_setters_and_clears_use_the_shared_installer` |
| Thrown external mutations retain identity, unwind the scope and request a save | `QueueCheckpointIdentityTest.thrownExternalMutation_retainsIdentityAndReleasesItsTransientGuard` |
| Superseded loader installation cannot accept a newer pending identity | `QueueCheckpointReplacementTest.supersededInstallation_doesNotAcceptTheNewerReplacement` |
| Recommendation identity attaches only after nonempty insertion, and scoped installation suppresses nested recommendation work | Python `test_radio_acceptance_follows_insertion_and_blocks_nested_recommendation_work`; `AndroidInfiniteQueueRecommendationTest.anEmptyPlanKeepsThePreviousIdentityAndBacksOffWithoutRefilling`; `aContinuationForAnUninstalledSource_acceptsIdentityOnlyAfterAppendingItsItems` |
| Restore and chunked hydration defer snapshots and bind the restored identity | `QueueCheckpointSchedulerTest.restoreAndHydration_deferRequestsAndCheckpointThePostRestoreState`; `QueueCheckpointReplacementTest.startupRadioHydration_doesNotRequireAReplacementInstallation` |
| Finite mutation callbacks cannot snapshot a partially accepted page | `QueueCheckpointSchedulerTest.finiteMutation_defersSnapshottingUntilTheAcceptedStateIsComplete` |
| Debouncing/coalescing still writes the final queue, including changes while preference reads or writes suspend | `QueueCheckpointSchedulerTest.burstRequests_snapshotOnceAndWriteTheFinalQueue`; `requestsDuringPreferenceRead_coalesceBeforeMappingTheQueue`; `requestsDuringWrite_checkpointTheFinalQueueAfterTheInFlightWrite` |
| Disabled persistence never writes; reenabling saves accepted changes after restoration | `QueueCheckpointSchedulerTest.disabledSetting_neverSnapshotsOrWritesIncludingWhenDisabledDuringDebounce`; `persistenceReenabled_requestsACheckpointForChangesMadeWhileDisabled` |
| Queue generations/checkpoint ordering reject stale writes without losing a newer resume state | `QueueCheckpointSchedulerTest.generationBump_discardsAnInFlightOlderSave`; `generationBump_discardsADebouncedRequestUntilANewRequestArrives`; `newerTeardownSnapshot_rejectsAnOlderCheckpointThatArrivesLater`; `newerResumeState_doesNotDropAPendingQueueOrAllowOlderStateToOverwriteIt` |
| Previous-service teardown cannot overwrite newer user/service checkpoints; restoration waits before reading and advancing generation | `QueueCheckpointSchedulerTest.previousServiceTeardown_cannotOverwriteANewerServiceCheckpoint`; `replacementService_waitsForPendingTeardownBeforeReadingAndAdvancingGeneration`; `pendingTeardown_doesNotOverwriteANewerUserCheckpointWhileRestorationWaits` |
| Finite occurrence/manual/shuffle/position data and legacy object-stream readers remain compatible | `YouTubeFinitePlaylistRestoreTest.snapshotsAfterPageOneAndTwoResumeTo135WithoutLosingManualPositionOrShuffleOrder`; `priorRecordShapeDecodesThroughPlaybackCodecWithCompatibleDefaults`; `existingNonfiniteRecordShapesDecodeAndRadioRetainsItsQueueKind` |
| YouTube keeps the shipped primary → InnerTubeX → NewPipe → Echo order and terminal login/cancellation behavior | `YouTubeFailsafeChainContractTest.innerTubeXSuccessSkipsNewPipeEchoAndExternalPlugin`; `networkFailureFallsThroughToNewPipeWithoutEcho`; `echoIsLastAfterNetworkAndUnavailableFailures`; `invalidLoginContextIsTerminalWithOriginalErrorAndZeroFallbackCalls`; `primaryCancellationIsTerminalBeforeAnyFallback` |

Also covered: `ExternalQueueTimelineInstallationTest` (backfill, first-page/retry
retention, new-occurrence replacement and proof requirements),
`QueueSourceDetachmentTest` (album continuation, retained nonempty source reset,
every unresolved phase, retained finite membership and explicit/confirmed empty
detachment) and `QueueLoadingPolicyTest` (synchronous Auto/Listen Together callbacks,
accepted-queue evaluation, all stale ownership exits, newer publishers, whole
actions and loading guards). Python contracts also cover the service cancellation
decision, all reset hooks, post-mutation loading and publisher cleanup.
