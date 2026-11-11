package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState
import cloud.wafflecommons.pixelbrainreader.data.gamification.PlayerProfile
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceFigures
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceRepository
import cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyReviewRepositoryTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val sundayNight = LocalDateTime.of(2026, 11, 15, 23, 50)
    private val w46 = "10_Journal/Weekly/2026-W46.md"

    private val noteRepository: NoteRepository = mockk()
    private val fileRepository: FileRepository = mockk()
    private val draftWriter: DraftWriter = mockk()
    private val dashboardDao: DailyDashboardDao = mockk()
    private val habitRepository: HabitRepository = mockk()
    private val moodRepository: MoodRepository = mockk()
    private val healthMetricsRepository: HealthMetricsRepository = mockk()
    private val choreRepository: ChoreRepository = mockk()
    private val gamificationRepository: GamificationRepository = mockk()
    private val gitFileAges: GitFileAges = mockk()
    private val fileContentDao: FileContentDao = mockk()
    private val evidenceRepository: EvidenceRepository = mockk()
    private val objectiveRepository: ObjectiveRepository = mockk()
    private val loadRecoveryRepository: LoadRecoveryRepository = mockk()
    private val missionLogRepository: MissionLogRepository = mockk()
    private val modesRepository: ModesRepository = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)

    private val repository = WeeklyReviewRepository(
        noteRepository, fileRepository, draftWriter, dashboardDao, habitRepository, moodRepository,
        healthMetricsRepository, choreRepository, gamificationRepository, gitFileAges, fileContentDao,
        evidenceRepository, objectiveRepository, loadRecoveryRepository, missionLogRepository, modesRepository, userPrefs
    )
    private val written = slot<String>()

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        every { userPrefs.weeklyReviewWritten } returns flowOf("2026-W45")
        coEvery { fileRepository.fileExists(any()) } returns false
        coEvery { draftWriter.write(any(), capture(written)) } answers { firstArg() }
        coEvery { noteRepository.getNoteContent(any()) } returns null
        coEvery { dashboardDao.getTasksSnapshot(any()) } returns emptyList()
        coEvery { habitRepository.getLogsBetweenFlow(any(), any()) } returns flowOf(emptyMap())
        coEvery { habitRepository.getHabitConfigs() } returns emptyList()
        coEvery { moodRepository.getMoodsBetween(any(), any()) } returns flowOf(emptyList())
        coEvery { healthMetricsRepository.getMetrics(any()) } returns null
        coEvery { choreRepository.getAllChoresStream() } returns flowOf(emptyList())
        coEvery { gamificationRepository.getStateSnapshot() } returns GamificationState()
        coEvery { gitFileAges.history() } returns FileHistory()
        coEvery { fileContentDao.getContent(any()) } returns null
        coEvery { evidenceRepository.figures(any(), any()) } returns EvidenceFigures(emptyList(), emptyList())
        coEvery { objectiveRepository.objectives(any()) } returns emptyList()
        coEvery { loadRecoveryRepository.report(any()) } throws IllegalStateException("no health in the background")
        coEvery { missionLogRepository.reloadSettings() } returns MissionLogSettings()
        every { modesRepository.config } returns MutableStateFlow(ModesConfig())
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `the due week is written once, with last week's plan carried over`() = runTest {
        coEvery { noteRepository.getNoteContent("10_Journal/Weekly/2026-W45.md") } returns
            "# Semaine 45\n\n${WeeklyReviewFormat.PLAN_HEADING}\n\n- Finir la refonte\n"

        assertEquals(w46, repository.writeDue(sundayNight, zone))

        coVerify(exactly = 1) { draftWriter.write(w46, any()) }
        coVerify { userPrefs.setWeeklyReviewWritten("2026-W46") }
        assertTrue(written.captured.contains("week: 2026-W46\n"))
        assertTrue(written.captured.contains(WeeklyReviewFormat.LAST_PLAN_HEADING + "\n\n- Finir la refonte\n"))
    }

    @Test
    fun `the week's XP is the total now minus the one last week's note kept`() = runTest {
        coEvery { noteRepository.getNoteContent("10_Journal/Weekly/2026-W45.md") } returns
            WeeklyReviewFormat.render(ReviewWeek(2026, 45), WeeklyFacts(xpTotal = 100))
        coEvery { gamificationRepository.getStateSnapshot() } returns
            GamificationState(profile = PlayerProfile(level = 2, currentXp = 20.0))

        repository.writeDue(sundayNight, zone)

        assertTrue(written.captured.contains("\nxp_total: 120\n"))
        assertTrue(written.captured.contains("- +20 XP · niveau 2\n"))
    }

    @Test
    fun `a week written before on this phone is not written again, even when its note was deleted`() = runTest {
        every { userPrefs.weeklyReviewWritten } returns flowOf("2026-W46")

        assertNull(repository.writeDue(sundayNight, zone))

        coVerify(exactly = 0) { draftWriter.write(any(), any()) }
    }

    @Test
    fun `a note already on disk is kept and remembered`() = runTest {
        coEvery { fileRepository.fileExists(w46) } returns true

        assertNull(repository.writeDue(sundayNight, zone))

        coVerify(exactly = 0) { draftWriter.write(any(), any()) }
        coVerify { userPrefs.setWeeklyReviewWritten("2026-W46") }
    }

    @Test
    fun `a read-only device writes nothing`() = runTest {
        VaultWriteGate.readOnly = true

        assertNull(repository.writeDue(sundayNight, zone))

        coVerify(exactly = 0) { draftWriter.write(any(), any()) }
        coVerify(exactly = 0) { userPrefs.setWeeklyReviewWritten(any()) }
    }

    @Test
    fun `on Sunday morning the week isn't due yet, and the one before was written`() = runTest {
        assertNull(repository.writeDue(LocalDateTime.of(2026, 11, 15, 8, 0), zone))

        coVerify(exactly = 0) { draftWriter.write(any(), any()) }
    }

    @Test
    fun `a source that fails leaves its section out and the note is still written`() = runTest {
        coEvery { habitRepository.getHabitConfigs() } throws IllegalStateException("unreadable habits")
        coEvery { moodRepository.getMoodsBetween(any(), any()) } returns
            flowOf(listOf(MoodEntity(1L, "2026-11-10", "09:00", 4, "", "", null)))

        assertEquals(w46, repository.writeDue(sundayNight, zone))

        assertFalse(written.captured.contains("Habitudes"))
        assertTrue(written.captured.contains("- Moyenne : 4,0/5 sur 1 jour"))
    }

    @Test
    fun `work notes are only counted, with the work folders read from disk first`() = runTest {
        every { modesRepository.config } returns MutableStateFlow(ModesConfig(workFolders = listOf("Travail")))
        val inWeek = millis(LocalDateTime.of(2026, 11, 12, 10, 0))
        coEvery { gitFileAges.history() } returns FileHistory(
            lastChanged = mapOf("Travail/Compte rendu.md" to inWeek, "30_Areas/Perso.md" to inWeek),
            firstSeen = mapOf("Travail/Compte rendu.md" to inWeek, "30_Areas/Perso.md" to inWeek)
        )
        coEvery { fileContentDao.getContent(any()) } returns "# Une note\n"

        repository.writeDue(sundayNight, zone)

        coVerify { modesRepository.reload() }
        assertTrue(written.captured.contains("- Créées (1) : [[30_Areas/Perso]]\n"))
        assertTrue(written.captured.contains("- Dans les dossiers de travail : 1 créée\n"))
        assertFalse(written.captured.contains("Compte rendu"))
    }
}
