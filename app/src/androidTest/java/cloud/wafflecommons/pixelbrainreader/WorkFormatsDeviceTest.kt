package cloud.wafflecommons.pixelbrainreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ObjectiveFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v11 work formats' patterns on ANDROID's regex engine (ICU), which the JVM unit tests can't
 * exercise. Run on the emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class WorkFormatsDeviceTest {

    @Test
    fun missionLogLinesParseOnIcu() {
        val entry = MissionLogFormat.parseLine(
            "- 2026-10-18 09:40 — Base choisie, voir [[ADR 12]] [type:: décision] [role:: Lead] [minutes:: 30] #infra #sécurité"
        )!!
        assertEquals(LogKind.DECISION, entry.kind)
        assertEquals("Base choisie, voir [[ADR 12]]", entry.text)
        assertEquals(listOf("infra", "sécurité"), entry.tags)
        assertEquals(30, entry.minutes)
    }

    @Test
    fun objectiveMilestonesParseOnIcu() {
        val milestones = ObjectiveFormat.milestones("- [x] Inventaire 📅 2026-03-01\n- [ ] Rotation [due:: 2026-11-15]")
        assertEquals(listOf("Inventaire", "Rotation"), milestones.map { it.text })
        assertEquals("2026-11-15", milestones[1].due.toString())
    }
}
