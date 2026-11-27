package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementMarkers
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSection
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePageCreation
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.PagePlan
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.di.AppScopeErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What one Xbox sync did, for the settings page's line. [pagesCreated]: pages made for played games (XB4). */
data class XboxSyncSummary(val titles: Int, val sessions: Int, val achievementSets: Int, val pages: Int, val pagesCreated: Int)

/**
 * One Xbox sync (13.3, XB-D), PsnSync's frame: read the title history, the minutes played and the
 * achievements that moved, then write what changed to the vault. Every network call comes first;
 * the writes run in one block under the git lock, like the other background writers, so none lands
 * in the middle of a pull. A failure writes nothing but the status line ("Xbox paused: …"), and
 * never touches the vault sync. A played title no page claims gets a page (XB4), linked and given
 * its achievements in the same sync. Nothing runs on a read-only device.
 */
@Singleton
class XboxSync @Inject constructor(
    private val account: XboxAccount,
    private val client: XboxClient,
    private val games: GamesRepository,
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val dashboardRepository: DailyDashboardRepository
) {
    private val running = Mutex()

    /**
     * [baseline]: the first sync of a new sign-in (Connect with no sign-in kept: the first one, or one
     * after Disconnect) reads and saves everything as usual but writes no session, and keeps no minutes
     * from before it, so play counts from now on. The sign-in also marks it ([XboxAccount.baselinePending])
     * until a sync saves its snapshot: a first sync that fails or is cancelled hands it to the next run,
     * the nightly ones included. Signing in again after the sign-in expired (its tokens still kept)
     * starts none: the play since the last snapshot is kept.
     */
    suspend fun sync(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        baseline: Boolean = false
    ): Result<XboxSyncSummary> {
        if (VaultWriteGate.readOnly) {
            return Result.failure(XboxException(XboxException.Kind.NOT_CONFIGURED, "Read-only device: Xbox syncs on your phone."))
        }
        if (!account.configured || !account.isConnected()) {
            return Result.failure(XboxException(XboxException.Kind.NOT_CONFIGURED, "Xbox isn't connected."))
        }
        return running.withLock {
            try {
                val summary = run(now, zone, baseline || account.baselinePending())
                account.setStatus(XboxStatus(true, now))
                Result.success(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: XboxException) {
                Log.w(TAG, "Xbox sync stopped: ${e.kind}")
                account.setStatus(XboxStatus(false, now, e.kind, e.message.orEmpty()))
                Result.failure(e)
            } catch (e: Exception) {
                // The type and stack only: a message could hold an address with the account's id in it.
                Log.e(TAG, "Xbox sync failed: " + AppScopeErrors.describe(e))
                val failure = XboxException(XboxException.Kind.API, "The Xbox sync failed: ${e.javaClass.simpleName}.")
                account.setStatus(XboxStatus(false, now, failure.kind, failure.message.orEmpty()))
                Result.failure(failure)
            }
        }
    }

    private suspend fun run(now: Long, zone: ZoneId, baseline: Boolean): XboxSyncSummary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // --- 1. Read (network; nothing written) ---------------------------------------------
        val list = withSession(now) { client.titleHistory(it) }
        val previous = games.xboxSnapshot()
        val before = previous?.titles?.associateBy { it.titleId }.orEmpty()
        val minutes = withSession(now) { client.minutesPlayed(it, list.titles) }
        val listed = list.titles.map { title -> stateOf(title, before[title.titleId], minutes[title.titleId], baseline) }
        val listedIds = listed.mapTo(HashSet()) { it.titleId }
        val seen = XboxSnapshot(
            syncedAt = Instant.ofEpochMilli(now).toString(),
            complete = !list.full,
            // A title pushed off the history (past 1,000) is kept as it was: a sync never removes one.
            titles = listed + previous?.titles.orEmpty().filter { it.titleId !in listedIds }
        )
        // A new sign-in's baseline writes no session: the snapshot saved below is where the count starts.
        val sessions = if (baseline) emptyList() else XboxSessionDiff.sessions(previous, seen, zone, LAST_PLAYED_IS_START)

        // A played title no page claims gets one (XB4): where the owner's game pages already are.
        val pages = games.pages()
        val claimed = XboxLinking.autoLinked(games.links(), seen, pages)
        val layout = GamePageCreation.layout(pages)
        val plans = GamePageCreation.plan(
            candidates = XboxLinking.candidates(claimed, seen),
            pages = pages,
            takenPaths = layout?.let { games.notePathsUnder(it.root) }.orEmpty(),
            console = GameConsole.XBOX
        )
        // The achievements of the titles about to get a page come with this sync.
        val links = plans.fold(claimed) { acc, plan -> plan.titleIds.fold(acc) { a, id -> a.linkConsole(GameConsole.XBOX, id, plan.path) } }

        // Achievements of the linked titles that moved since the last sync (or were never saved),
        // the games played last first, a few a sync: a large library fills in over several nights.
        val files = mutableListOf<AchievementSetFile>()
        val due = seen.titles
            .filter { links.consolePage(GameConsole.XBOX, it.titleId) != null }
            .map { it to games.achievementSet(it.titleId) }
            .filter { (title, saved) -> XboxAchievements.moved(title, saved) }
            .sortedWith(compareBy(nullsLast(reverseOrder<Instant>())) { XboxTimes.instant(it.first.lastPlayed) })
            .take(MAX_ACHIEVEMENT_TITLES)
        for ((title, saved) in due) {
            val fetched = try {
                withSession(now) { client.achievements(it, title.titleId, title.achievementSource) }
            } catch (e: XboxException) {
                when (e.kind) {
                    // The sign-in itself failed: nothing more can be read.
                    XboxException.Kind.SIGN_IN_EXPIRED, XboxException.Kind.CLIENT_REJECTED,
                    XboxException.Kind.ACCOUNT_REFUSED, XboxException.Kind.NOT_CONFIGURED -> throw e
                    // Asked to wait, or no network: what was read is kept, the rest waits for the next sync.
                    XboxException.Kind.RATE_LIMITED, XboxException.Kind.NETWORK -> {
                        Log.i(TAG, "Xbox achievements: ${e.kind}, the rest waits for the next sync")
                        break
                    }
                    else -> {
                        Log.i(TAG, "Xbox achievements skipped for one title: ${e.kind}")
                        continue
                    }
                }
            }
            // An empty answer never replaces a file that holds achievements: the earned dates kept in
            // it (an offline 360 unlock's first-seen date among them) would be lost for good.
            if (fetched.isEmpty() && saved?.achievements?.isNotEmpty() == true) {
                Log.i(TAG, "Xbox achievements: an empty answer, the file kept")
                continue
            }
            files += XboxAchievements.merge(title.titleId, title.name, title.achievementSource, fetched, saved, Instant.ofEpochMilli(now))
        }
        // titlehub's own totals read 0 for Xbox One titles: a title read now takes its file's.
        val read = files.associateBy { it.titleId }
        val current = seen.copy(titles = seen.titles.map { title ->
            val file = read[title.titleId] ?: return@map title
            title.copy(totalAchievements = file.achievements.size, totalGamerscore = file.totalGamerscore)
        })

        val template = if (plans.isEmpty()) null else games.otherTemplate()
        val newPages = plans.map { plan -> GamePageCreation.render(plan, template, startedOf(plan, current, read, zone), today) }

        // --- 2. Write (the vault, under the git lock) ----------------------------------------
        var pagesTouched = 0
        var created = emptyList<GamePage>()
        gitSyncCoordinator.mutex.withLock {
            created = games.createPages(newPages)
            // Read again: the pages just created, and any made meanwhile.
            val pagesNow = games.pages()
            val createdPaths = created.mapTo(HashSet()) { it.path }
            // Linked again from the file on disk: a link the owner made during the sync is kept, and a
            // created page takes its titles unless one was linked or hidden meanwhile.
            games.updateLinks { onDisk ->
                newPages.filter { it.plan.path in createdPaths }.fold(XboxLinking.autoLinked(onDisk, current, pagesNow)) { acc, page ->
                    page.plan.titleIds.fold(acc) { a, id ->
                        if (a.consolePage(GameConsole.XBOX, id) == null && !a.isHidden(GameConsole.XBOX, id)) {
                            a.linkConsole(GameConsole.XBOX, id, page.plan.path)
                        } else {
                            a
                        }
                    }
                }
            }
            files.forEach { games.saveAchievementSet(it) }
            // Every run: a page linked by hand since gets its section without waiting for a new achievement.
            pagesTouched = refreshPages(pagesNow, games.links(), zone)
            // Without its sessions saved, the snapshot isn't either: the next run replays from the same
            // baseline (the sessions are keyed on it), so no play is lost or counted twice.
            val days = games.addSessions(sessions)
                ?: throw XboxException(XboxException.Kind.API, "The sessions couldn't be saved; the next sync tries again.")
            days.filter { it.isBefore(today) }.forEach { games.applyPastDay(it, today) }
            // Today's line comes with the next burn (23:50 after the evening run), even on a day with
            // nothing else on the board.
            if (today in days) dashboardRepository.ensureDay(today)
            // The baseline ends with its snapshot on disk, not before: the count starts from this one.
            if (games.saveXboxSnapshot(current) && baseline) account.baselineSaved()
        }
        return XboxSyncSummary(current.titles.size, sessions.size, files.size, pagesTouched, created.size)
    }

    /**
     * A title as this sync saw it; what the answer leaves out is kept from the last sync, except a
     * baseline's minutes: a title whose minutes weren't read (a 429 on its batch) starts at its next
     * reading instead of from the count before the new sign-in.
     */
    private fun stateOf(title: XboxTitle, old: XboxTitleState?, minutes: Long?, baseline: Boolean): XboxTitleState {
        val summary = title.achievement
        val source = summary?.sourceVersion ?: 0
        // A 360 title's totals are right in titlehub; a One title's read 0 there, its file has them.
        val legacyTotals = summary?.takeIf { source == 1 }
        return XboxTitleState(
            titleId = title.titleId,
            name = title.name.ifBlank { old?.name.orEmpty() },
            mediaItemType = title.mediaItemType,
            achievementSource = source,
            modernTitleId = title.modernTitleId?.takeIf { it.isNotBlank() },
            minutesPlayed = if (baseline) minutes else minutes ?: old?.minutesPlayed,
            lastPlayed = XboxTimes.instant(title.titleHistory?.lastTimePlayed)?.toString() ?: old?.lastPlayed,
            // 360 icons are cleartext http: only an https icon is kept.
            imageUrl = title.displayImage?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: old?.imageUrl,
            currentAchievements = summary?.currentAchievements,
            currentGamerscore = summary?.currentGamerscore,
            totalAchievements = old?.totalAchievements ?: legacyTotals?.totalAchievements?.takeIf { it > 0 },
            totalGamerscore = old?.totalGamerscore ?: legacyTotals?.totalGamerscore?.takeIf { it > 0 },
            progress = summary?.progressPercentage?.toInt(),
            visible = title.titleHistory?.visible ?: true
        )
    }

    /**
     * A new page's `started` (Xbox has no first-played date): the earliest of its titles' first
     * earned achievement (this sync's files, else the saved ones) and their last played days.
     */
    private suspend fun startedOf(plan: PagePlan, current: XboxSnapshot, read: Map<String, AchievementSetFile>, zone: ZoneId): LocalDate? {
        val moments = plan.titleIds.flatMap { id ->
            val file = read[id] ?: games.achievementSet(id)
            val firstUnlock = file?.achievements?.mapNotNull { XboxTimes.instant(it.earnedAt) }?.minOrNull()
            val lastPlayed = current.titles.firstOrNull { it.titleId == id }?.let { XboxTimes.instant(it.lastPlayed) }
            listOfNotNull(firstUnlock, lastPlayed)
        }
        return moments.minOrNull()?.atZone(zone)?.toLocalDate()
    }

    /** Each linked page's 🎖️ section and achievement markers, from its titles' files. */
    private suspend fun refreshPages(pages: List<GamePage>, links: GameLinks, zone: ZoneId): Int {
        var touched = 0
        pages.forEach { page ->
            val files = links.consoleTitles(GameConsole.XBOX, page.path)
                .mapNotNull { games.achievementSet(it) }
                .filter { it.achievements.isNotEmpty() }
            if (files.isEmpty()) return@forEach
            val earnedOn: (String, String) -> LocalDate? = { titleId, id ->
                files.firstOrNull { it.titleId == titleId }?.achievements?.firstOrNull { it.id == id }?.earnedAt
                    ?.let { XboxTimes.instant(it) }?.atZone(zone)?.toLocalDate()
            }
            val lines = AchievementSection.lines(files, zone)
            if (games.editPage(page.path) { AchievementMarkers.tick(AchievementSection.apply(it, lines), earnedOn) }) touched++
        }
        return touched
    }

    /** [block] with the session; a refused token renews it once (a second refusal: the sign-in has ended). */
    private suspend fun <T> withSession(now: Long, block: suspend (XboxSession) -> T): T {
        val session = account.session(now)
        return try {
            block(session)
        } catch (e: XboxException) {
            if (e.kind != XboxException.Kind.TOKEN_EXPIRED) throw e
            val renewed = account.session(now, force = true)
            try {
                block(renewed)
            } catch (again: XboxException) {
                if (again.kind != XboxException.Kind.TOKEN_EXPIRED) throw again
                throw XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in was refused: Connect again.")
            }
        }
    }

    companion object {
        private const val TAG = "XboxSync"

        /** Which end of a session titlehub's lastTimePlayed marks: its end, until the Fold's live check says otherwise. */
        const val LAST_PLAYED_IS_START = false

        /** Titles whose achievements are read in one sync, the games played last first (a 429 stops sooner). */
        const val MAX_ACHIEVEMENT_TITLES = 25
    }
}
