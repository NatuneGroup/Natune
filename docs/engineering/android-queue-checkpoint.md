# Android queue checkpoint identity

PR [#99](https://github.com/NatuneGroup/Natune/pull/99) separates the requested
`MusicService.currentQueue` from the identity used to serialize the playing queue.
The object-stream persistence format and YouTube resolution order are unchanged.

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

## External installation routes

All external replacements use `MusicService.installExternalTimeline`, which
cancels obsolete playlist/hydration work, invalidates initial-load generations,
and runs the shared gate transaction. The new identity is accepted after the
mutation succeeds; a thrown mutation retains the previous identity and requests
a checkpoint. Installers targeting a replaced player are ignored before queue
ownership changes; stale session edits cannot accept the active player's emptiness.

| Entry point | Route |
| --- | --- |
| Android Auto `onSetMediaItems`, voice and browse results | `sessionFacingPlayer` wraps the actual player in `SessionTimelinePlayer`; all six `setMediaItem(s)` overloads run the shared installer after resolution |
| Auto existing-queue tap and add-current-to-playlist replay | The same occurrence objects preserve the installed identity and title |
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

## Regression expectations updated

Existing assertions changed only where the previous blocking model was incorrect
or the fixture needed to express the real installation/clear hook:

| Existing test | Correct expectation and reason |
| --- | --- |
| `slowFinitePreparation_preservesThePreviousCheckpointBeforeTheInitialClear` | Requests during suspended preparation save current retained items, position and teardown under the previous identity; the later temporary empty clear still cannot erase that checkpoint |
| `unacceptedReplacement_blocksPositionOnlyWritesWithANonemptyTimeline` (renamed) | Retained nonempty items allow position saves under the previous identity before user acceptance |
| `emptyFilteredFinitePage_preservesThePreviousCheckpointThroughNextPageFailure` | The fixture's stop/clear invokes explicit clear acceptance, matching `stopAndClearPlayback`; changing only `currentQueue` no longer changes persistence identity |
| `pendingRadioReplacement_preservesTheOldCheckpointPastDebounceAndAtTeardown` (renamed) | Pending radio permits current-item, position and teardown checkpoints under the previous identity; filtering a resolved ordinary list to empty may save that empty list |
| `radioPreloadPresence_doesNotAcceptTheReplacementUntilItIsInstalled` | Preload metadata alone still cannot advance identity, but retained items remain saveable |
| `failedRadioInitialLoad_userAdditionAcceptsACheckpointAndTeardown` | A failed uninstalled radio leaves retained items and later manual additions under their previous list identity, not the failed radio's identity |
| `pendingRadioReplacement_userRemovalAcceptsTheEditedTimeline` | Removal edits the previous timeline and keeps its list identity |
| `pendingRadioReplacement_clearIdentitySavesEvenWithoutATimelineEvent` | Both the pending retained timeline and the explicit identity clear can request checkpoints |
| `startupRadioHydration_doesNotRequireAReplacementInstallation` | The fixture explicitly binds the restored identity at hydration completion, matching the service; no replacement request is required |
| `supersededInstallation_doesNotAcceptTheNewerReplacement` | A stale install hook cannot advance identity; the retained timeline remains saveable until the newer timeline installs |
| `anEmptyPlanPreservesTheRadioSourceAndBacksOffWithoutRefilling` (renamed) | A rejected recommendation plan backs off and retains the previous source identity because no radio items installed |
| `aTrulyExhaustedPageAppendsNothingAndDoesNotKeepAStalePage` | An empty recommendation page cannot attach a new radio identity; the existing retry/backoff behavior remains |

New Kotlin fixtures cover pending identity/title/occurrences, failed and empty
radio followed by external installation, suspended-preload cancellation followed
by add/remove/empty removal, thrown installation, every session setter overload,
same-occurrence Auto replay, session edits, explicit clear, empty-removal acceptance
followed by loader insertion/filtering, and continuation acceptance after an
uninstalled first page. Existing unresolved
finite-empty, manual-edit, hydration, persistence ordering and teardown tests stay.

The runnable Python source contracts verify installer wiring, scoped acceptance,
loader cleanup and the separation of filtering from explicit removal. The allowed
repository/Python checks are structural feedback. Kotlin tests and Android
compilation/runtime behavior require the orchestrator's separate validation;
no Gradle, JVM, Kotlin build, lint or Kotlin tests run locally.
