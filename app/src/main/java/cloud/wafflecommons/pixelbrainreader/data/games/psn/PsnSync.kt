package cloud.wafflecommons.pixelbrainreader.data.games.psn

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameNames
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.PsnDuration
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.SessionDiff
import cloud.wafflecommons.pixelbrainreader.data.games.TrophyMarkers
import cloud.wafflecommons.pixelbrainreader.data.games.TrophyRecord
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySection
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySetFile
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync did, for the settings page's line. */
data class PsnSyncSummary(val titles: Int, val sessions: Int, val trophySets: Int, val pages: Int)

/**
 * One PSN sync (RFC-009 §7, D3): read the played games and the trophies, then write what changed to
 * the vault. Every network call comes first; the writes run in one block under the git lock, like
 * the other background writers, so none lands in the middle of a pull. A failure writes nothing but
 * the status line ("PSN paused: …"), and never touches the vault sync.
 */
@Singleton
class PsnSync @Inject constructor(
    private val account: PsnAccount,
    private val client: PsnClient,
    private val games: GamesRepository,
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val dashboardRepository: DailyDashboardRepository
) {
    private val running = Mutex()

    suspend fun sync(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Result<PsnSyncSummary> {
        if (VaultWriteGate.readOnly) {
            return Result.failure(PsnException(PsnException.Kind.NOT_CONFIGURED, "Read-only device: PlayStation syncs on your phone."))
        }
        if (!account.configured || !account.isConnected()) {
            return Result.failure(PsnException(PsnException.Kind.NOT_CONFIGURED, "PlayStation isn't connected."))
        }
        return running.withLock {
            try {
                val summary = run(now, zone)
                account.setStatus(PsnStatus(true, now))
                Result.success(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PsnException) {
                Log.w(TAG, "PSN sync stopped: ${e.kind}")
                account.setStatus(PsnStatus(false, now, e.kind, e.message.orEmpty()))
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "PSN sync failed", e)
                val failure = PsnException(PsnException.Kind.API, "The PlayStation sync failed: ${e.javaClass.simpleName}.")
                account.setStatus(PsnStatus(false, now, failure.kind, failure.message.orEmpty()))
                Result.failure(failure)
            }
        }
    }

    private suspend fun run(now: Long, zone: ZoneId): PsnSyncSummary {
        // --- 1. Read (network) -------------------------------------------------------------
        var access = account.accessToken(now)
        val played = try {
            client.playedGames(access)
        } catch (e: PsnException) {
            if (e.kind != PsnException.Kind.TOKEN_EXPIRED) throw e
            access = account.accessToken(now, force = true)
            client.playedGames(access)
        }
        val trophyTitles = client.trophyTitles(access).associateBy { it.npCommunicationId }
        val previous = games.snapshot()
        val before = previous?.titles?.associateBy { it.titleId }.orEmpty()

        // A title's trophy set, asked once and then kept in the snapshot (a few ids per sync).
        val unknown = played.map { it.titleId }
            .filter { id -> before[id]?.let { old -> old.trophySet == null && !old.trophyLookedUp } ?: true }
            .take(MAX_SET_LOOKUPS)
        val looked = if (unknown.isEmpty()) emptyMap() else client.setsForTitles(access, unknown)

        val current = PsnSnapshot(
            syncedAt = Instant.ofEpochMilli(now).toString(),
            titles = played.map { title ->
                val old = before[title.titleId]
                val setId = old?.trophySet ?: looked[title.titleId]?.firstOrNull()?.npCommunicationId
                val trophies = setId?.let(trophyTitles::get)
                PsnTitleState(
                    titleId = title.titleId,
                    name = title.name.ifBlank { title.localizedName.orEmpty() },
                    category = title.category,
                    playSeconds = PsnDuration.seconds(title.playDuration) ?: old?.playSeconds ?: 0L,
                    playCount = title.playCount,
                    firstPlayed = title.firstPlayedDateTime,
                    lastPlayed = title.lastPlayedDateTime,
                    imageUrl = title.imageUrl,
                    conceptTitleIds = title.concept?.titleIds.orEmpty(),
                    trophySet = setId,
                    trophyService = trophies?.npServiceName ?: old?.trophyService ?: looked[title.titleId]?.firstOrNull()?.npServiceName,
                    trophySetVersion = trophies?.trophySetVersion ?: old?.trophySetVersion,
                    trophiesUpdated = trophies?.lastUpdatedDateTime ?: old?.trophiesUpdated,
                    progress = trophies?.progress ?: old?.progress,
                    earned = trophies?.earnedTrophies?.total ?: old?.earned,
                    defined = trophies?.definedTrophies?.total ?: old?.defined,
                    trophyLookedUp = old?.trophyLookedUp == true || title.titleId in unknown
                )
            }
        )

        val sessions = SessionDiff.sessions(previous, current, zone, LAST_PLAYED_IS_START)
        val pages = games.pages()
        val links = autoLinked(games.links(), current, pages)

        // Trophy sets of the linked titles that moved since the last sync (or were never saved).
        val setFiles = mutableListOf<TrophySetFile>()
        current.titles.filter { links.pageOf(it.titleId) != null && it.trophySet != null }.distinctBy { it.trophySet }.forEach { title ->
            val set = title.trophySet ?: return@forEach
            val service = title.trophyService ?: "trophy2"
            val saved = games.trophySet(set)
            val moved = saved == null || before[title.titleId]?.trophiesUpdated != title.trophiesUpdated
            if (!moved) return@forEach
            val definitions = if (saved == null || saved.version != title.trophySetVersion || saved.trophies.isEmpty()) {
                client.trophies(access, set, service).trophies
            } else {
                null
            }
            val earned = client.earned(access, set, service).trophies.associateBy { it.trophyId }
            val records = definitions?.map { d ->
                TrophyRecord(
                    id = d.trophyId, name = d.trophyName.orEmpty(), detail = d.trophyDetail.orEmpty(), grade = d.trophyType,
                    hidden = d.trophyHidden, group = d.trophyGroupId, earnedAt = earned[d.trophyId]?.takeIf { it.earned }?.earnedDateTime
                )
            } ?: saved?.trophies?.map { t -> t.copy(earnedAt = earned[t.id]?.takeIf { it.earned }?.earnedDateTime ?: t.earnedAt) }.orEmpty()
            setFiles += TrophySetFile(set, service, title.trophySetVersion, trophyTitles[set]?.trophyTitleName ?: title.name, records)
        }

        // --- 2. Write (the vault, under the git lock) --------------------------------------
        var pagesTouched = 0
        gitSyncCoordinator.mutex.withLock {
            // Linked again from the file on disk: a link the owner made during the sync is kept.
            games.updateLinks { onDisk -> autoLinked(onDisk, current, pages) }
            setFiles.forEach { games.saveTrophySet(it) }
            // Every run: a page linked by hand since gets its section without waiting for a new trophy.
            pagesTouched = refreshPages(pages, games.links(), current, zone)
            // Without its sessions saved, the snapshot isn't either: the next run replays from the same
            // baseline (the sessions are keyed on it), so no play is lost or counted twice.
            val days = games.addSessions(sessions)
                ?: throw PsnException(PsnException.Kind.API, "The sessions couldn't be saved; the next sync tries again.")
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            days.filter { it.isBefore(today) }.forEach { games.applyPastDay(it, today) }
            // Today's line comes with the next burn (23:50 after the evening run), even on a day with
            // nothing else on the board.
            if (today in days) dashboardRepository.ensureDay(today)
            games.saveSnapshot(current)
        }
        return PsnSyncSummary(current.titles.size, sessions.size, setFiles.size, pagesTouched)
    }

    /** Each linked page's 🏆 section and trophy markers, from its sets' files. */
    private suspend fun refreshPages(pages: List<GamePage>, links: GameLinks, current: PsnSnapshot, zone: ZoneId): Int {
        var touched = 0
        pages.forEach { page ->
            val sets = links.titlesOf(page.path)
                .mapNotNull { id -> current.titles.firstOrNull { it.titleId == id }?.trophySet }
                .distinct()
                .mapNotNull { games.trophySet(it) }
            if (sets.isEmpty()) return@forEach
            val earnedOn: (String, Int) -> LocalDate? = { set, id ->
                sets.firstOrNull { it.set == set }?.trophies?.firstOrNull { it.id == id }?.earnedAt
                    ?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
            }
            val lines = TrophySection.lines(sets, zone)
            if (games.editPage(page.path) { TrophyMarkers.tick(TrophySection.apply(it, lines), earnedOn) }) touched++
        }
        return touched
    }

    /** New links for titles no page claims yet, by name (the concept's too); hidden titles stay out. */
    private fun autoLinked(links: GameLinks, current: PsnSnapshot, pages: List<GamePage>): GameLinks {
        var next = links
        current.titles.forEach { title ->
            if (next.pageOf(title.titleId) != null || title.titleId in next.hidden) return@forEach
            // A sibling version already linked (the PS4 and PS5 ids of one game) takes this one along.
            val sibling = title.conceptTitleIds.firstNotNullOfOrNull { next.pageOf(it) }
            val page = sibling ?: GameNames.pageForTitle(title.name, pages)?.path ?: return@forEach
            next = next.link(title.titleId, page)
        }
        return next
    }

    companion object {
        private const val TAG = "PsnSync"
        private const val MAX_SET_LOOKUPS = 50

        /**
         * Which end of the latest session PSN's `lastPlayedDateTime` marks. False (its end) until the
         * live check on the owner's account says otherwise (RFC-009 §7).
         */
        const val LAST_PLAYED_IS_START = false
    }
}
