package cloud.wafflecommons.pixelbrainreader.data.modes

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/** A stretch of whole days (both ends included) that leaves the rhythm: school holidays, a swapped weekend. */
@Serializable
data class CustodyException(val start: String, val end: String, val withChild: Boolean, val label: String = "") {
    fun contains(date: LocalDate): Boolean = runCatching {
        !date.isBefore(LocalDate.parse(start)) && !date.isAfter(LocalDate.parse(end))
    }.getOrDefault(false)
}

/**
 * Shared custody (owner feedback, 2026-11-13): the child is home every other week, from one
 * handover day to the next. [arrival] is a handover day on which the child arrives; the rhythm
 * repeats every 14 days whatever the week numbers say, so a 53-week year doesn't break it. Kept in
 * the vault with the rest of Modes; [name] is typed by the owner and never written in this repository.
 *
 * One calendar, read two ways:
 * - [withChildOn], for habits: the child wakes up at home. The departure day counts, the arrival day doesn't.
 * - [withChildAt], for the modes: on a handover day the change happens at the handover moment
 *   (the workday's end), so the evening already follows the new week.
 *
 * An [exceptions] range overrides both for its whole days; when two overlap, the later start wins.
 */
@Serializable
data class CustodySchedule(
    val enabled: Boolean = false,
    val name: String = "",
    /** ISO day of week of the handover (5 = Friday), kept while no week is set yet. */
    val handoverDay: Int = DayOfWeek.FRIDAY.value,
    /** A handover day on which the child arrives (ISO date); blank until the owner says how this week goes. */
    val arrival: String = "",
    val exceptions: List<CustodyException> = emptyList()
) {
    fun arrivalDate(): LocalDate? = runCatching { LocalDate.parse(arrival) }.getOrNull()

    /** On, with its rhythm set: the only state in which it changes anything. */
    val isActive: Boolean get() = enabled && arrivalDate() != null

    /** How the screens call the child: "With <label>". */
    val label: String get() = name.trim().ifEmpty { DEFAULT_NAME }

    val handoverDayOfWeek: DayOfWeek
        get() = arrivalDate()?.dayOfWeek ?: DayOfWeek.of(handoverDay.coerceIn(1, 7))

    fun exceptionOn(date: LocalDate): CustodyException? =
        exceptions.filter { it.contains(date) }.maxByOrNull { it.start }

    /** Days since an arrival, 0..13; null without a rhythm. */
    private fun cycleDay(date: LocalDate): Long? =
        arrivalDate()?.let { ChronoUnit.DAYS.between(it, date).mod(CYCLE_DAYS) }

    /** Whether the child wakes up at home on [date] (habits). */
    fun withChildOn(date: LocalDate): Boolean {
        exceptionOn(date)?.let { return it.withChild }
        val day = cycleDay(date) ?: return false
        return day in 1..WEEK
    }

    /** Whether the child is home at [moment] (modes); on a handover day the change happens at [handover]. */
    fun withChildAt(moment: LocalDateTime, handover: LocalTime): Boolean {
        val date = moment.toLocalDate()
        exceptionOn(date)?.let { return it.withChild }
        val day = cycleDay(date) ?: return false
        val handedOver = !moment.toLocalTime().isBefore(handover)
        return when (day) {
            0L -> handedOver
            WEEK -> !handedOver
            else -> day in 1 until WEEK
        }
    }

    /** This week, true when the child arrives on its handover day, false when they leave; null without a rhythm. */
    fun arrivesInWeekOf(date: LocalDate): Boolean? =
        cycleDay(handoverIn(date))?.let { it == 0L }

    /** Sets the rhythm from the week of [today]: the child [arrives] on its handover day, or leaves on it. */
    fun withThisWeek(today: LocalDate, arrives: Boolean): CustodySchedule {
        val handover = handoverIn(today)
        return copy(arrival = (if (arrives) handover else handover.minusDays(WEEK)).toString(), handoverDay = handoverDayOfWeek.value)
    }

    /** Moves the handover to [day] of the same weeks. */
    fun withHandoverDay(day: DayOfWeek): CustodySchedule =
        copy(handoverDay = day.value, arrival = arrivalDate()?.with(ChronoField.DAY_OF_WEEK, day.value.toLong())?.toString() ?: arrival)

    private fun handoverIn(date: LocalDate): LocalDate = date.with(ChronoField.DAY_OF_WEEK, handoverDayOfWeek.value.toLong())

    companion object {
        const val DEFAULT_NAME = "your child"
        private const val WEEK = 7L
        private const val CYCLE_DAYS = 14L
    }
}
