package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import cloud.wafflecommons.pixelbrainreader.ui.review.ReviewNoteViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class ReviewNotificationsTest {

    private val work = WrittenReport("Travail/Rapports/Rapport d'activité 2026-10.md", EvidenceScope.WORK)
    private val personal = WrittenReport("00_Inbox/Rapport d'activité personnel 2026-10.md", EvidenceScope.PERSONAL)
    private val encrypted = WrittenReport("99_Private/Mission log/Rapports/Rapport d'activité 2026-10.md.enc", EvidenceScope.WORK)

    @Test
    fun `a personal report is always announced, a work one only in Work mode`() {
        assertTrue(ReviewNotifications.announceMonthly(listOf(personal), workMode = false, hasWorkFolders = true))
        assertTrue(ReviewNotifications.announceMonthly(listOf(work), workMode = true, hasWorkFolders = true))
        assertFalse(ReviewNotifications.announceMonthly(listOf(work), workMode = false, hasWorkFolders = true))
        assertTrue(ReviewNotifications.announceMonthly(listOf(work, personal), workMode = false, hasWorkFolders = true))
    }

    @Test
    fun `without work folders the one report is announced, an encrypted one never`() {
        val inbox = WrittenReport("00_Inbox/Rapport d'activité 2026-10.md", EvidenceScope.WORK)
        assertTrue(ReviewNotifications.announceMonthly(listOf(inbox), workMode = false, hasWorkFolders = false))
        assertFalse(ReviewNotifications.announceMonthly(listOf(encrypted), workMode = true, hasWorkFolders = true))
        assertFalse(ReviewNotifications.announceMonthly(emptyList(), workMode = true, hasWorkFolders = true))
    }

    // --- Where a tap leads ---

    private val fileRepository: FileRepository = mockk()
    private val modesRepository: ModesRepository = mockk()
    private val missionLogRepository: MissionLogRepository = mockk()
    private val opener = ReviewNoteViewModel(fileRepository, modesRepository, missionLogRepository)
    private val now = LocalDateTime.of(2026, 11, 16, 9, 0)

    @Before
    fun setUp() {
        coEvery { fileRepository.fileExists(any()) } returns true
        coEvery { missionLogRepository.reloadSettings() } returns MissionLogSettings()
        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.WORK, workFolders = listOf("Travail"))
    }

    @Test
    fun `the weekly key opens the last due week's review, when it exists`() = runTest {
        assertEquals("10_Journal/Weekly/2026-W46.md", opener.resolve(ReviewNoteViewModel.WEEKLY, now))
        coEvery { fileRepository.fileExists(any()) } returns false
        assertNull(opener.resolve(ReviewNoteViewModel.WEEKLY, now))
    }

    @Test
    fun `the monthly key opens the work report in Work mode, else the personal one`() = runTest {
        assertEquals(work.path, opener.resolve(ReviewNoteViewModel.MONTHLY, now))

        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL, workFolders = listOf("Travail"))
        assertEquals(personal.path, opener.resolve(ReviewNoteViewModel.MONTHLY, now))

        coEvery { modesRepository.currentVisibility() } returns Visibility(mode = ContextMode.WORK, workFolders = listOf("Travail"))
        coEvery { missionLogRepository.reloadSettings() } returns MissionLogSettings(encrypted = true)
        assertEquals(personal.path, opener.resolve(ReviewNoteViewModel.MONTHLY, now))
    }

    @Test
    fun `an unknown kind opens nothing`() = runTest {
        assertNull(opener.resolve("../settings", now))
        assertNull(opener.resolve(null, now))
    }
}
