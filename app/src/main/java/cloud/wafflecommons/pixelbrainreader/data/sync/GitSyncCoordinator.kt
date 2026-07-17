package cloud.wafflecommons.pixelbrainreader.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single process-wide lock that serializes every MULTI-STEP git sync sequence
 * against one another — currently [SyncOrchestrator.executeFullSyncCycle] and
 * [cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository.syncRepository].
 *
 * `JGitProvider`'s per-operation `gitMutex` already prevents index corruption, but it does
 * NOT stop one caller's commit→pull→push from interleaving with another's in the gap
 * between operations. That interleaving leaves one side pushing a stale ref
 * (non-fast-forward) → a dropped sync. This lock closes that gap.
 *
 * Contract (to avoid deadlock — [Mutex] is NOT reentrant):
 *  - Acquire it AROUND a whole commit→pull→push sequence, never around a single git op.
 *  - Never acquire it from a code path already reachable while it is held. In particular
 *    the orchestrator's cycle must not call `FileRepository.syncRepository` (verified: it
 *    only uses `saveFileLocally`/`readFile`/`pushDirtyFiles`, none of which take this lock).
 *  - The orchestrator uses `tryLock` (drops overlapping foreground cycles); explicit,
 *    user-initiated saves use `withLock` (wait, then run) so they are never silently lost.
 */
@Singleton
class GitSyncCoordinator @Inject constructor() {
    val mutex = Mutex()
}
