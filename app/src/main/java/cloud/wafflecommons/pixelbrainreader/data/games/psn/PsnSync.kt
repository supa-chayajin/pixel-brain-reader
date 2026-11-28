package cloud.wafflecommons.pixelbrainreader.data.games.psn

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePageCreation
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.PageCandidate
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
import cloud.wafflecommons.pixelbrainreader.di.AppScopeErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync did, for the settings page's line. [pagesCreated]: pages made for played games (13.3, XB4). */
data class PsnSyncSummary(val titles: Int, val sessions: Int, val trophySets: Int, val pages: Int, val pagesCreated: Int = 0)

/**
 * One PSN sync (RFC-009 §7, D3): read the played games and the trophies, then write what changed to
 * the vault. Every network call comes first; the writes run in one block under the git lock, like
 * the other background writers, so none lands in the middle of a pull. A failure writes nothing but
 * the status line ("PSN paused: …"), and never touches the vault sync. Since 13.3 (XB4) a played
 * title that no page claims gets a page, linked and given its trophies in the same sync. Since 13.4
 * an unreadable links.json links, hides and creates nothing (the play is still kept), the day is the
 * one the writes happen on, and every trophy list of a title is read.
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

    /**
     * [baseline]: the first sync of a new sign-in (Connect with no sign-in kept: the first one, or one
     * after Disconnect) reads and saves everything as usual but writes no session, so play counts from
     * now on (13.3). The sign-in also marks it ([PsnAccount.baselinePending]) until a sync saves its
     * snapshot: a first sync that fails or is cancelled hands it to the next run, the nightly ones
     * included. A new NPSSO for an expired sign-in (its tokens still kept) starts none: the play since
     * the last snapshot is kept.
     */
    suspend fun sync(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        baseline: Boolean = false,
        writeClock: () -> Long = System::currentTimeMillis
    ): Result<PsnSyncSummary> {
        if (VaultWriteGate.readOnly) {
            return Result.failure(PsnException(PsnException.Kind.NOT_CONFIGURED, "Read-only device: PlayStation syncs on your phone."))
        }
        if (!account.configured || !account.isConnected()) {
            return Result.failure(PsnException(PsnException.Kind.NOT_CONFIGURED, "PlayStation isn't connected."))
        }
        return running.withLock {
            try {
                val summary = run(now, zone, baseline || account.baselinePending(), writeClock)
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

    private suspend fun run(now: Long, zone: ZoneId, baseline: Boolean, writeClock: () -> Long): PsnSyncSummary {
        // --- 1. Read (network) -------------------------------------------------------------
        val access = Access(account.accessToken(now))
        val played = withAccess(access, now) { client.playedGames(it) }
        val trophyTitles = withAccess(access, now) { client.trophyTitles(it) }.associateBy { it.npCommunicationId }
        val previous = games.snapshot()
        val before = previous?.titles?.associateBy { it.titleId }.orEmpty()

        // A title's trophy lists, asked once and then kept in the snapshot (a few ids per sync).
        val asked = PsnTrophySets.toLookUp(played, before, MAX_SET_LOOKUPS)
        val askedIds = asked.toSet()
        val looked = if (asked.isEmpty()) emptyMap() else withAccess(access, now) { client.setsForTitles(it, asked) }

        val current = PsnSnapshot(
            syncedAt = Instant.ofEpochMilli(now).toString(),
            titles = played.map { title ->
                val old = before[title.titleId]
                val sets = PsnTrophySets.setsOf(title.titleId, old, looked)
                val first = sets.firstOrNull()
                val trophies = first?.let(trophyTitles::get)
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
                    trophySet = first,
                    trophyService = trophies?.npServiceName ?: old?.trophyService
                        ?: looked[title.titleId]?.firstOrNull { it.npCommunicationId == first }?.npServiceName,
                    trophySetVersion = trophies?.trophySetVersion ?: old?.trophySetVersion,
                    trophiesUpdated = trophies?.lastUpdatedDateTime ?: old?.trophiesUpdated,
                    progress = trophies?.progress ?: old?.progress,
                    earned = trophies?.earnedTrophies?.total ?: old?.earned,
                    defined = trophies?.definedTrophies?.total ?: old?.defined,
                    trophyLookedUp = old?.trophyLookedUp == true || title.titleId in askedIds,
                    // A title not asked this time keeps what it had: one kept with a single list from
                    // before 13.4 stays due for its one more look.
                    trophySets = if (title.titleId in askedIds) sets else old?.trophySets.orEmpty()
                )
            }
        )

        // A new sign-in's baseline writes no session: the snapshot saved below is where the count starts.
        val sessions = if (baseline) emptyList() else SessionDiff.sessions(previous, current, zone, LAST_PLAYED_IS_START)
        val pages = games.pages()
        // The new pages' own dates are the sync's start day; the writes below use the day they happen on.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val read = games.linksRead()
        // An unreadable links.json is never acted on (13.4): no link, no page, no section.
        val claimed = if (read.readable) PsnLinking.autoLinked(read.links, current, pages) else read.links

        // A played title no page claims gets one (XB4): where the owner's game pages already are.
        val layout = GamePageCreation.layout(pages)
        val plans = if (!read.readable) {
            emptyList()
        } else {
            GamePageCreation.plan(
                candidates = current.titles
                    .filter { claimed.pageOf(it.titleId) == null && it.titleId !in claimed.hidden && it.playSeconds > 0 }
                    .map { PageCandidate(it.titleId, it.name, it.conceptTitleIds - it.titleId, instantOrNull(it.lastPlayed)) },
                pages = pages,
                takenPaths = layout?.let { games.notePathsUnder(it.root) }.orEmpty(),
                console = GameConsole.PSN
            )
        }
        // The trophies of the titles about to get a page come with this sync.
        val links = plans.fold(claimed) { acc, plan -> plan.titleIds.fold(acc) { a, id -> a.link(id, plan.path) } }

        // Every trophy list of the linked titles that moved since its file was saved (or was never saved).
        val setFiles = mutableListOf<TrophySetFile>()
        val lists = current.titles.filter { links.pageOf(it.titleId) != null }
            .flatMap { title -> title.allTrophySets.map { it to title } }
            .distinctBy { it.first }
        for ((set, title) in lists) {
            val saved = games.trophySet(set)
            val listed = trophyTitles[set]
            if (!PsnTrophySets.moved(saved, listed)) continue
            val fromLookup = looked.values.asSequence().flatten().firstOrNull { it.npCommunicationId == set }
            val service = listed?.npServiceName ?: fromLookup?.npServiceName ?: saved?.service ?: title.trophyService ?: "trophy2"
            val version = listed?.trophySetVersion ?: saved?.version
            val definitions = if (saved == null || saved.trophies.isEmpty() || saved.version != version) {
                withAccess(access, now) { client.trophies(it, set, service) }.trophies
            } else {
                null
            }
            val earned = withAccess(access, now) { client.earned(it, set, service) }.trophies.associateBy { it.trophyId }
            val records = definitions?.map { d ->
                TrophyRecord(
                    id = d.trophyId, name = d.trophyName.orEmpty(), detail = d.trophyDetail.orEmpty(), grade = d.trophyType,
                    hidden = d.trophyHidden, group = d.trophyGroupId, earnedAt = earned[d.trophyId]?.takeIf { it.earned }?.earnedDateTime
                )
            } ?: saved?.trophies?.map { t -> t.copy(earnedAt = earned[t.id]?.takeIf { it.earned }?.earnedDateTime ?: t.earnedAt) }.orEmpty()
            setFiles += TrophySetFile(set, service, title.trophySetVersion, trophyTitles[set]?.trophyTitleName ?: title.name, records)
        }

        val template = if (plans.isEmpty()) null else games.otherTemplate()
        val newPages = plans.map { plan ->
            val started = plan.titleIds.mapNotNull { id -> current.titles.firstOrNull { it.titleId == id }?.firstPlayed }
                .mapNotNull { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
                .minOrNull()
            GamePageCreation.render(plan, template, started, today)
        }

        // --- 2. Write (the vault, under the git lock) --------------------------------------
        var pagesTouched = 0
        var created = emptyList<GamePage>()
        var linksUnreadable = !read.readable
        gitSyncCoordinator.mutex.withLock {
            // The day the writes happen on: a sync that waited past the 23:50 burn, or past midnight.
            val writeDay = Instant.ofEpochMilli(writeClock()).atZone(zone).toLocalDate()
            var pagesNow: List<GamePage>? = null
            val linksNow = games.linksRead()
            if (linksNow.readable) {
                // The plans checked again against what is there now: a title linked or hidden during
                // the sync, a page made meanwhile, a path taken with another case.
                val pagesBefore = games.pages()
                val taken = layout?.let { games.notePathsUnder(it.root) }.orEmpty()
                val toCreate = GamePageCreation.recheck(newPages, linksNow.links, GameConsole.PSN, pagesBefore, taken)
                created = games.createPages(toCreate)
                // Read again: the pages just created, and any made meanwhile.
                val fresh = games.pages()
                pagesNow = fresh
                val createdPaths = created.mapTo(HashSet()) { it.path }
                // Linked again from the file on disk: a link the owner made during the sync is kept, and a
                // created page takes its titles unless one was linked or hidden meanwhile.
                games.updateLinks { onDisk ->
                    toCreate.filter { it.plan.path in createdPaths }.fold(PsnLinking.autoLinked(onDisk, current, fresh)) { acc, page ->
                        page.plan.titleIds.fold(acc) { a, id -> if (a.pageOf(id) == null && id !in a.hidden) a.link(id, page.plan.path) else a }
                    }
                }
            } else {
                linksUnreadable = true
            }
            setFiles.forEach { games.saveTrophySet(it) }
            // Every run: a page linked by hand since gets its section without waiting for a new trophy.
            val linksAfter = games.linksRead()
            if (linksAfter.readable) {
                pagesTouched = refreshPages(pagesNow ?: games.pages(), linksAfter.links, current, zone)
            } else {
                linksUnreadable = true
            }
            // Without its sessions saved, the snapshot isn't either: the next run replays from the same
            // baseline (the sessions are keyed on it), so no play is lost or counted twice.
            val days = games.addSessions(sessions)
                ?: throw PsnException(PsnException.Kind.API, "The sessions couldn't be saved; the next sync tries again.")
            days.filter { it.isBefore(writeDay) }.forEach { games.applyPastDay(it, writeDay) }
            // Today's line goes in at once (13.4): this sync may run after the 23:50 burn.
            if (writeDay in days) {
                dashboardRepository.ensureDay(writeDay)
                dashboardRepository.burnToDisk(writeDay)
            }
            // The baseline ends with its snapshot on disk, not before: the count starts from this one.
            if (games.saveSnapshot(current) && baseline) account.baselineSaved()
        }
        if (linksUnreadable) throw PsnException(PsnException.Kind.LINKS_UNREADABLE, GamesRepository.LINKS_UNREADABLE_MESSAGE)
        return PsnSyncSummary(current.titles.size, sessions.size, setFiles.size, pagesTouched, created.size)
    }

    /** Each linked page's 🏆 section and trophy markers, from every list of its titles. */
    private suspend fun refreshPages(pages: List<GamePage>, links: GameLinks, current: PsnSnapshot, zone: ZoneId): Int {
        var touched = 0
        pages.forEach { page ->
            val sets = links.titlesOf(page.path)
                .flatMap { id -> current.titles.firstOrNull { it.titleId == id }?.allTrophySets.orEmpty() }
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

    /** The access token of one sync: refreshed at most once, then kept for the rest of it. */
    private class Access(var token: String, var refreshed: Boolean = false)

    /**
     * [block] with the sync's access token (13.4: every data call). A token refused mid-sync (one that
     * had a minute left when the sync began) is refreshed once and kept; a second refusal, or a
     * refused refresh, is the sign-in's real end and goes up.
     */
    private suspend fun <T> withAccess(access: Access, now: Long, block: suspend (String) -> T): T =
        try {
            block(access.token)
        } catch (e: PsnException) {
            if (e.kind != PsnException.Kind.TOKEN_EXPIRED || access.refreshed) throw e
            access.refreshed = true
            access.token = account.accessToken(now, force = true)
            block(access.token)
        }

    private fun instantOrNull(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

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
