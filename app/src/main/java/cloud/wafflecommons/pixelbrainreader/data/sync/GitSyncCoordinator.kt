package cloud.wafflecommons.pixelbrainreader.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single process-wide lock that serializes every MULTI-STEP git sequence, and the background
 * vault writers that must not run in the middle of one:
 *  - [SyncOrchestrator.executeFullSyncCycle] (commit → pull → health/Google → commit → push),
 *  - [cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository.syncRepository]
 *    (explicit saves: commit → pull → push),
 *  - [VaultPushScheduler] (batched background commit → push),
 *  - [ForegroundRefresher] (health + habit JSON writes, which would dirty the tree mid-rebase).
 *
 * `JGitProvider`'s per-operation `gitMutex` already prevents index corruption, but it does
 * NOT stop one caller's commit→pull→push from interleaving with another's in the gap
 * between operations. That interleaving leaves one side pushing a stale ref
 * (non-fast-forward) → a dropped sync. This lock closes that gap.
 *
 * Contract (to avoid deadlock — [Mutex] is NOT reentrant):
 *  - Acquire it AROUND a whole sequence, never around a single git op.
 *  - Never acquire it from a code path already reachable while it is held. In particular
 *    the orchestrator's cycle must not call `FileRepository.syncRepository` (verified: it
 *    only uses `saveFileLocally`/`readFile`/`pushDirtyFiles`, none of which take this lock).
 *  - Automatic orchestrator cycles use `tryLock` (overlapping cycles are dropped); user-initiated
 *    syncs, explicit saves and the background writers wait for it, so their work is never lost.
 */
@Singleton
class GitSyncCoordinator @Inject constructor() {
    val mutex = Mutex()
}
