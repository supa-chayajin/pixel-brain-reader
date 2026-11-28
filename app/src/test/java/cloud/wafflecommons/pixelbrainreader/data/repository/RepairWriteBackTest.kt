package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChoreDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HomeRoomDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Settings › Vault & device › Repair's "Write them back to the vault" (13.4, S7): an unreadable file
 * is kept aside, a file whose items the app holds none of is left and named, and a failed write is
 * reported instead of "Written back".
 */
class RepairWriteBackTest {

    private val configFile = "10_Journal/data/habits/config.json"
    private val roomsFile = "10_Journal/data/home/rooms.json"
    private val choresFile = "10_Journal/data/home/chores.json"

    private val disk = mutableMapOf<String, String>()
    private val written = mutableMapOf<String, String>()
    private val fileRepository: FileRepository = mockk(relaxed = true) {
        coEvery { readFile(any()) } answers { disk[firstArg()] }
        coEvery { saveFileLocally(any(), any()) } answers { written[firstArg()] = secondArg() }
        every { moveAsideCorrupt(any()) } answers { disk.remove(firstArg<String>()); File("moved-aside") }
        coEvery { syncRepository(any(), any()) } returns Result.success(Unit)
    }
    private var habits = listOf<HabitConfigEntity>()
    private val habitDao: HabitDao = mockk { coEvery { getAllConfigs() } answers { habits } }
    private var rooms = listOf<HomeRoomEntity>()
    private val homeRoomDao: HomeRoomDao = mockk { every { getAllRoomsAsFlow() } answers { flowOf(rooms) } }
    private val choreDao: ChoreDao = mockk { every { getAllChoresAsFlow() } answers { flowOf(emptyList()) } }
    private val secretManager: SecretManager = mockk { every { getRepoInfo() } returns ("owner" to "vault") }
    private val widgets: WidgetUpdateManager = mockk(relaxed = true)
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)

    private val choreRepository = ChoreRepository(choreDao, homeRoomDao, fileRepository, database, widgets, pushScheduler)
    private val habitRepository = HabitRepository(fileRepository, habitDao, choreRepository, secretManager, widgets, pushScheduler)

    private fun habit(id: String) = HabitConfigEntity(id = id, title = id, description = "", frequency = emptyList(), targetValue = 0.0, unit = "")

    @Test
    fun `a file whose items the app holds none of is left as it is and named, the rest written and synced`() = runBlocking {
        habits = listOf(habit("read"))
        rooms = listOf(HomeRoomEntity(id = "kitchen", name = "Kitchen", createdAt = 1L))
        disk[choresFile] = """[{"id":"dishes","name":"Dishes","roomId":"kitchen"}]"""

        val left = habitRepository.performBulkConfigSync()

        assertEquals(listOf(VaultListFile.CHORES), left)
        assertTrue(written.getValue(configFile).contains("\"read\""))
        assertTrue(written.getValue(roomsFile).contains("\"kitchen\""))
        assertTrue(choresFile !in written)
        assertEquals("""[{"id":"dishes","name":"Dishes","roomId":"kitchen"}]""", disk[choresFile])
        coVerify { fileRepository.syncRepository("owner", "vault") }
    }

    @Test
    fun `an app with nothing yet writes nothing over the vault's lists`() = runBlocking {
        disk[configFile] = """[{"id":"read","title":"Read"}]"""
        disk[roomsFile] = """[{"id":"kitchen","name":"Kitchen"}]"""
        disk[choresFile] = """[{"id":"dishes","name":"Dishes","roomId":"kitchen"}]"""

        val left = habitRepository.performBulkConfigSync()

        assertEquals(listOf(VaultListFile.HABITS, VaultListFile.ROOMS, VaultListFile.CHORES), left)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `an unreadable rooms file is kept aside before the app's rooms replace it`() = runBlocking {
        rooms = listOf(HomeRoomEntity(id = "kitchen", name = "Kitchen", createdAt = 1L))
        disk[roomsFile] = """[{"id":"kitchen","name":"""

        habitRepository.performBulkConfigSync()

        verify { fileRepository.moveAsideCorrupt(roomsFile) }
        assertTrue(written.getValue(roomsFile).contains("\"kitchen\""))
    }

    @Test
    fun `a habits file that fails to write is reported, never written back`() = runBlocking {
        habits = listOf(habit("read"))
        coEvery { fileRepository.saveFileLocally(configFile, any()) } throws IOException("disk full")

        try {
            habitRepository.performBulkConfigSync()
            fail("The failed write should reach Repair")
        } catch (e: IOException) {
            // Repair shows "Writing back failed."
        }
        coVerify(exactly = 0) { fileRepository.syncRepository(any(), any()) }
    }

    @Test
    fun `an unreadable habits file that can't be kept aside is reported and stays`() = runBlocking {
        habits = listOf(habit("read"))
        disk[configFile] = "{ broken"
        every { fileRepository.moveAsideCorrupt(configFile) } returns null

        try {
            habitRepository.performBulkConfigSync()
            fail("A file that can't be kept aside should stop the write-back")
        } catch (e: IOException) {
            // Repair shows "Writing back failed."
        }
        assertTrue(configFile !in written)
        assertEquals("{ broken", disk[configFile])
    }

    @Test
    fun `a habit save keeps an unreadable config aside too`() = runBlocking {
        habits = listOf(habit("read"))
        disk[configFile] = "{ broken"

        habitRepository.exportConfigToJson()

        verify { fileRepository.moveAsideCorrupt(configFile) }
        assertTrue(written.getValue(configFile).contains("\"read\""))
    }
}
