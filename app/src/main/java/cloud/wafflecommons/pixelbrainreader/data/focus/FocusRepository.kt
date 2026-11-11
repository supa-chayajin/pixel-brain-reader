package cloud.wafflecommons.pixelbrainreader.data.focus

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The focus session (D9, owner 2026-11-11): one at a time, kept on the phone, stepped every five
 * minutes by `FocusWorker`; a session that runs to its end earns XP and posts "Focus done", whose
 * "Log it" writes a mission-log fact in Work mode. Stopping early ends quietly. Never on a
 * read-only device (#18).
 */
@Singleton
class FocusRepository @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val platform: FocusPlatform,
    private val gitFileAges: GitFileAges,
    private val fileContentDao: FileContentDao,
    private val grantXp: GrantXpUseCase,
    private val modesRepository: ModesRepository,
    private val missionLogRepository: MissionLogRepository
) {
    val session: Flow<FocusSession?> = preferences.focusSession.map(FocusSession::decode)

    suspend fun current(): FocusSession? = FocusSession.decode(preferences.focusSession.first())

    /** Starts a session of [minutes]; a running one is replaced. Null on a read-only device. */
    suspend fun start(minutes: Int, now: Long = System.currentTimeMillis()): FocusSession? {
        if (VaultWriteGate.refuses()) return null
        val session = FocusSession(now, minutes.coerceIn(1, 600))
        preferences.setFocusSession(session.encode())
        platform.cancelDone()
        platform.postRunning(session, now)
        platform.schedule(session.nextStep(now) - now)
        return session
    }

    /** Ends the session now, quietly: no XP, no notification. */
    suspend fun stop() {
        preferences.setFocusSession(null)
        platform.cancelSchedule()
        platform.cancelRunning()
    }

    /** One step: the bar moves on, or the session ends. A step with no session clears what is left. */
    suspend fun step(now: Long = System.currentTimeMillis()) {
        val session = current()
        if (session == null) {
            platform.cancelRunning()
            return
        }
        if (session.isOver(now)) {
            finish(session)
        } else {
            platform.postRunning(session, now)
            platform.schedule(session.nextStep(now) - now)
        }
    }

    private suspend fun finish(session: FocusSession) {
        preferences.setFocusSession(null)
        platform.cancelRunning()
        if (VaultWriteGate.readOnly) return
        grantXp(FocusSession.xpFor(session.minutes))
        val notes = edited(session)
        platform.postDone(session.minutes, notes, canLog = !modesRepository.currentVisibility().muteWorkNotifications)
    }

    /** The notes of [session], kept only while they exist and aren't generated. */
    suspend fun edited(session: FocusSession): List<String> = try {
        val candidates = FocusNotes.edited(gitFileAges.history(), session)
        candidates.filter { path ->
            val content = fileContentDao.getContent(path)
            content != null && !MirrorNotes.isGenerated(path, content)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** "Log it": the session as a mission-log fact, in Work mode only. */
    suspend fun log(minutes: Int, notes: List<String>, now: LocalDateTime = LocalDateTime.now()) {
        platform.cancelDone()
        if (VaultWriteGate.refuses()) return
        if (modesRepository.currentVisibility().workContextHidden) return
        missionLogRepository.add(LogEntry(at = now, kind = LogKind.FACT, text = FocusNotes.logText(minutes, notes), tags = listOf(FocusNotes.TAG)))
    }
}
