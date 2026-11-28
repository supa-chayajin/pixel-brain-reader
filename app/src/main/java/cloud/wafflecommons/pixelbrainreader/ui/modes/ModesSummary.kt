package cloud.wafflecommons.pixelbrainreader.ui.modes

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Modes hub's rows (13.4, S2): each says what its page holds now, in a few words. Pure. The
 * modes are called by the owner's names for them, never by fixed ones.
 */
object ModesSummary {

    /** "Work · Personal · Off · Toolkit": the three modes and the toolkit, as named. */
    fun names(appearance: ModeAppearance): String =
        (ContextMode.entries.map { appearance.label(it) } + appearance.toolkitName).joinToString(" · ")

    /** How many folders count as work: "3 folders", or "No folder yet". */
    fun work(config: ModesConfig): String = when (val count = config.workFolders.size) {
        0 -> "No folder yet"
        1 -> "1 folder"
        else -> "$count folders"
    }

    /** What the Personal mode shows on the project screens: "Every <Personal> project", or "4 projects". */
    fun projects(config: ModesConfig, appearance: ModeAppearance): String = when (val count = config.personalProjects.size) {
        0 -> "Every ${appearance.label(ContextMode.PERSONAL)} project"
        1 -> "1 project"
        else -> "$count projects"
    }

    /**
     * "Off" without a schedule, else its days and hours and the mode the rest of the time:
     * "Mon–Fri, 09:00–18:00, then Off". With the custody calendar on and two different modes, both:
     * "…, then Off with <child>, Personal without".
     */
    fun schedule(config: ModesConfig, appearance: ModeAppearance): String {
        if (!config.scheduleEnabled) return "Off"
        val days = days(config.workDays)
        val hours = "${config.workStart}–${config.workEnd}"
        val outside = appearance.label(config.outsideHoursMode)
        val custody = config.custody
        val then = if (custody.isActive && config.withChildMode != config.outsideHoursMode) {
            "${appearance.label(config.withChildMode)} with ${custody.label}, $outside without"
        } else {
            outside
        }
        return "$days, $hours, then $then"
    }

    /**
     * "Off" while the custody calendar is off; else its rhythm and this week: "Every other Friday ·
     * with <label> this week", or "· say how this week goes" until the rhythm is set.
     */
    fun custody(config: ModesConfig, today: LocalDate): String {
        val custody = config.custody
        if (!custody.enabled) return "Off"
        val day = custody.handoverDayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val week = when {
            !custody.isActive -> "say how this week goes"
            custody.withChildOn(today) -> "with ${custody.label} this week"
            else -> "without ${custody.label} this week"
        }
        return "Every other $day · $week"
    }

    /** "2 leaves · 3 key dates", one of the two alone, or "Nothing planned". */
    fun dates(config: ModesConfig): String {
        val parts = listOfNotNull(
            config.leavePeriods.size.takeIf { it > 0 }?.let { if (it == 1) "1 leave" else "$it leaves" },
            config.keyDates.size.takeIf { it > 0 }?.let { if (it == 1) "1 key date" else "$it key dates" }
        )
        return if (parts.isEmpty()) "Nothing planned" else parts.joinToString(" · ")
    }

    /**
     * ISO days (1 = Monday) as the week reads: a run of three days or more as "Mon–Fri", shorter
     * runs day by day ("Sat, Sun"); "No work day" for none.
     */
    internal fun days(isoDays: List<Int>): String {
        val sorted = isoDays.filter { it in 1..7 }.distinct().sorted()
        if (sorted.isEmpty()) return "No work day"
        val runs = mutableListOf<MutableList<Int>>()
        sorted.forEach { day ->
            val last = runs.lastOrNull()
            if (last != null && last.last() == day - 1) last += day else runs += mutableListOf(day)
        }
        return runs.joinToString(", ") { run ->
            if (run.size >= 3) "${short(run.first())}–${short(run.last())}" else run.joinToString(", ") { short(it) }
        }
    }

    private fun short(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
}
