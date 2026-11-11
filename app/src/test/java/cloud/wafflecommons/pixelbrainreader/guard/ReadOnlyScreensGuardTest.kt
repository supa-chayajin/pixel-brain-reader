package cloud.wafflecommons.pixelbrainreader.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settled decision #18 has "a UI that offers no write" among its layers (12.15, S2): every page whose
 * action changes the vault stays readable on a read-only device, its controls off, and says where
 * to change it. A new page that writes the vault joins [noticed] and shows `ReadOnlyNotice`.
 * (The tablet, a read-only device on the real vault, is where the controls themselves are checked:
 * the JVM tests don't render Compose.)
 */
class ReadOnlyScreensGuardTest {

    private val sourceRoot: File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "cloud/wafflecommons/pixelbrainreader") }
        .first { it.isDirectory }

    /** Pages that change the vault, each showing `ReadOnlyNotice` on a read-only device. */
    private val noticed = listOf(
        // Settings
        "ui/settings/HabitConfigScreen.kt",
        "ui/homeconfig/HomeConfigScreen.kt",
        "ui/modes/ModesSettingsScreen.kt",
        "ui/settings/SettingsScreen.kt",
        // The toolkit
        "ui/work/MissionLogScreen.kt",
        "ui/work/BoardScreen.kt",
        "ui/work/HandoffScreen.kt",
        "ui/work/InboxScreen.kt",
        "ui/work/LeaveScreen.kt",
        "ui/work/MeetingsScreen.kt",
        "ui/work/SnippetsScreen.kt",
        "ui/work/ObjectivesScreen.kt",
        "ui/work/EvidenceScreen.kt",
        "ui/work/ProjectsScreen.kt",
        // The tabs
        "ui/lifeos/HabitDashboardScreen.kt",
        "ui/homeos/ChoreDashboardScreen.kt"
    )

    /** Pages that handled it before 12.15 in their own words, and controls that only hide. */
    private val ownWords = listOf(
        "ui/settings/FeedsScreen.kt",
        "ui/settings/MoodTagSettingsScreen.kt",
        "ui/meetings/MeetingPrepScreen.kt",
        "ui/work/ExplainErrorScreen.kt",
        "ui/daily/DailyNoteScreen.kt",
        "ui/daily/GratitudeSection.kt",
        "ui/components/NoteTimeMachineSheet.kt"
    )

    @Test
    fun `every page that changes the vault says so on a read-only device`() {
        val missing = noticed.filterNot { File(sourceRoot, it).readText().contains("ReadOnlyNotice(") }
        assertTrue("No ReadOnlyNotice in: $missing", missing.isEmpty())
    }

    @Test
    fun `the pages that handled it before still check the device`() {
        val missing = ownWords.filterNot { File(sourceRoot, it).readText().contains("VaultWriteGate.readOnly") }
        assertTrue("No read-only check in: $missing", missing.isEmpty())
    }
}
