package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceFigures
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceRepository
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import cloud.wafflecommons.pixelbrainreader.data.work.VaultPasswordMissingException
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class MonthlyReportsTest {

    private val october = YearMonth.of(2026, 10)
    private val lastNight = LocalDateTime.of(2026, 10, 31, 23, 50)
    private val workPath = "Travail/Rapports/Rapport d'activité 2026-10.md"
    private val personalPath = "00_Inbox/Rapport d'activité personnel 2026-10.md"

    private val evidenceRepository: EvidenceRepository = mockk()
    private val missionLogRepository: MissionLogRepository = mockk()
    private val modesRepository: ModesRepository = mockk(relaxed = true)
    private val fileRepository: FileRepository = mockk()
    private val draftWriter: DraftWriter = mockk()
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val repository = MonthlyReportsRepository(evidenceRepository, missionLogRepository, modesRepository, fileRepository, draftWriter, userPrefs)
    private val written = mutableMapOf<String, String>()

    private val workFigures = EvidenceFigures(
        listOf(LogEntry(LocalDateTime.of(2026, 10, 12, 9, 0), LogKind.FACT, "Mise en production")), emptyList(), scope = EvidenceScope.WORK
    )
    private val personalFigures = EvidenceFigures(
        emptyList(),
        listOf(WorkNote("20_Projects/21_App/Plan.md", "Plan", "✅ Terminé", LocalDate.of(2026, 10, 20), null, emptyList(), "")),
        scope = EvidenceScope.PERSONAL
    )

    @Before
    fun setUp() {
        every { userPrefs.monthlyReportsWritten } returns flowOf("2026-09")
        every { modesRepository.config } returns MutableStateFlow(ModesConfig(workFolders = listOf("Travail")))
        coEvery { missionLogRepository.reloadSettings() } returns MissionLogSettings()
        coEvery { evidenceRepository.figures(any(), EvidenceScope.WORK) } returns workFigures
        coEvery { evidenceRepository.figures(any(), EvidenceScope.PERSONAL) } returns personalFigures
        coEvery { fileRepository.fileExists(any()) } returns false
        coEvery { draftWriter.write(any(), any()) } answers { written[firstArg()] = secondArg(); firstArg() }
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    // --- When and where ---

    @Test
    fun `a month is due once its last day reaches the nightly run`() {
        assertEquals(YearMonth.of(2026, 11), MonthlyReports.lastDue(LocalDateTime.of(2026, 11, 30, 23, 50)))
        assertEquals(october, MonthlyReports.lastDue(LocalDateTime.of(2026, 11, 30, 8, 0)))
        assertEquals(october, MonthlyReports.lastDue(LocalDateTime.of(2026, 11, 1, 0, 30)))
        assertEquals(YearMonth.of(2026, 12), MonthlyReports.lastDue(LocalDateTime.of(2026, 12, 31, 23, 50)))
        assertEquals(YearMonth.of(2027, 1), MonthlyReports.lastDue(LocalDateTime.of(2027, 2, 27, 23, 50)))
        assertEquals(YearMonth.of(2027, 2), MonthlyReports.lastDue(LocalDateTime.of(2027, 2, 28, 23, 50)))
    }

    @Test
    fun `the work report goes to the first work folder, or to the inbox without one`() {
        assertEquals(workPath, MonthlyReports.workPath(october, listOf("Travail/", "Autre")))
        assertEquals("00_Inbox/Rapport d'activité 2026-10.md", MonthlyReports.workPath(october, emptyList()))
        assertEquals(personalPath, MonthlyReports.personalPath(october))
    }

    // --- Writing ---

    @Test
    fun `with work folders, the work report goes to the first one and the personal one to the inbox`() = runTest {
        val reports = repository.writeDue(lastNight)

        assertEquals(listOf(WrittenReport(workPath, EvidenceScope.WORK), WrittenReport(personalPath, EvidenceScope.PERSONAL)), reports)
        assertTrue(written.getValue(workPath).contains("Mise en production"))
        assertFalse(written.getValue(workPath).contains("## Synthèse"))
        assertTrue(written.getValue(workPath).contains("type: "))
        coVerify { userPrefs.setMonthlyReportsWritten("2026-10") }
    }

    @Test
    fun `without work folders there is one report, in the inbox`() = runTest {
        every { modesRepository.config } returns MutableStateFlow(ModesConfig())

        val reports = repository.writeDue(lastNight)

        assertEquals(listOf(WrittenReport("00_Inbox/Rapport d'activité 2026-10.md", EvidenceScope.WORK)), reports)
        coVerify(exactly = 0) { evidenceRepository.figures(any(), EvidenceScope.PERSONAL) }
    }

    @Test
    fun `an encrypted log keeps its work report encrypted beside it`() = runTest {
        coEvery { missionLogRepository.reloadSettings() } returns MissionLogSettings(encrypted = true)
        val encrypted = "99_Private/Mission log/Rapports/Rapport d'activité 2026-10.md.enc"
        coEvery { missionLogRepository.writeEncryptedReport("Rapport d'activité 2026-10", any()) } returns encrypted

        val reports = repository.writeDue(lastNight)

        assertEquals(WrittenReport(encrypted, EvidenceScope.WORK), reports.first())
        assertFalse(written.containsKey(workPath))
    }

    @Test
    fun `a report with nothing measured is not written`() = runTest {
        coEvery { evidenceRepository.figures(any(), EvidenceScope.WORK) } returns EvidenceFigures(emptyList(), emptyList())

        assertEquals(listOf(WrittenReport(personalPath, EvidenceScope.PERSONAL)), repository.writeDue(lastNight))
    }

    @Test
    fun `a failing work report spares the personal one, and the month is tried again`() = runTest {
        coEvery { evidenceRepository.figures(any(), EvidenceScope.WORK) } throws VaultPasswordMissingException()

        assertEquals(listOf(WrittenReport(personalPath, EvidenceScope.PERSONAL)), repository.writeDue(lastNight))
        coVerify(exactly = 0) { userPrefs.setMonthlyReportsWritten(any()) }
    }

    @Test
    fun `a report already on disk is never written twice`() = runTest {
        coEvery { fileRepository.fileExists(workPath) } returns true

        assertEquals(listOf(WrittenReport(personalPath, EvidenceScope.PERSONAL)), repository.writeDue(lastNight))
    }

    @Test
    fun `a month written before, or a read-only device, writes nothing`() = runTest {
        every { userPrefs.monthlyReportsWritten } returns flowOf("2026-10")
        assertEquals(emptyList<WrittenReport>(), repository.writeDue(lastNight))

        every { userPrefs.monthlyReportsWritten } returns flowOf("2026-09")
        VaultWriteGate.readOnly = true
        assertEquals(emptyList<WrittenReport>(), repository.writeDue(lastNight))
        coVerify(exactly = 0) { draftWriter.write(any(), any()) }
    }
}
