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
        // Settings (13.4: the home writes nothing, its pages say it)
        "ui/settings/HabitConfigScreen.kt",
        "ui/homeconfig/HomeConfigScreen.kt",
        "ui/modes/ModesSettingsScreen.kt",
        "ui/settings/FeedsScreen.kt",
        // 13.4: the Modes hub's short pages, Navigation (the tabs each mode shows), Repair (write back)
        "ui/modes/ModesPages.kt",
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
        "ui/work/WorkshopScreen.kt",
        // The tabs
        "ui/lifeos/HabitDashboardScreen.kt",
        "ui/homeos/ChoreDashboardScreen.kt",
        // Games (12.17, RFC-009)
        "ui/games/GamesScreen.kt",
        "ui/games/GamePageScreen.kt",
        "ui/games/PsnSettingsScreen.kt",
        // 13.3: the Xbox sign-in (XB-E)
        "ui/games/XboxSettingsScreen.kt"
    )

    /** Pages that handled it before 12.15 in their own words, and controls that only hide. */
    private val ownWords = listOf(
        "ui/settings/MoodTagSettingsScreen.kt",
        "ui/meetings/MeetingPrepScreen.kt",
        "ui/work/ExplainErrorScreen.kt",
        "ui/daily/DailyNoteScreen.kt",
        "ui/daily/GratitudeSection.kt",
        "ui/components/NoteTimeMachineSheet.kt",
        // Life Stats' experiments card (12.21, L2): New and Remove only where the vault can change.
        "ui/lifestats/ExperimentsCard.kt",
        // 13.3: controls that only hide. The open map's edits in its Maps menu (a full-screen browser has no room
        // for the notice), and the share screen's "Add as a game map" (never reached on a read-only
        // device: MainActivity finishes a share there).
        "ui/games/GameMapScreen.kt",
        "ui/share/ShareImportScreen.kt",
        // 13.4 (P5): a shared image's choice, a photo note or a game's picture (never reached on a
        // read-only device either: MainActivity finishes a share there; it asks nothing if it were).
        "ui/share/SharedImageChooser.kt",
        // 13.4 (S-b): the pages that took the Settings home's Health Connect and Google cards say in
        // their own words what doesn't run on a read-only device (the day's health file, the import).
        "ui/settings/HealthSettingsScreen.kt",
        "ui/settings/GoogleSettingsScreen.kt",
        // 13.4 (F-c): the Feeds tab's Import only hides; a tap opens the browser (the marks are the device's own).
        "ui/feeds/FeedsTabScreen.kt"
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
