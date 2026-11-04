package cloud.wafflecommons.pixelbrainreader.data.capture

/**
 * Where a line typed into the evening check-in notification goes. The evening flavour of
 * [CaptureRouter]: a plain line is about the day (a timeline moment, not a scrap), a to-do written
 * at night is for tomorrow, and an idea lands in the scratchpad (the Second Brain text is edited
 * on screen only).
 */
object CheckInRouting {

    enum class Destination { MOMENT, GRATITUDE, TASK_TOMORROW, SCRAP }

    data class Routed(val destination: Destination, val text: String, val guess: CaptureGuess)

    /** [forceGratitude]: the reply came from the "Thankful for…" field. */
    fun route(raw: String, forceGratitude: Boolean = false): Routed? {
        val guess = CaptureRouter.guess(raw)
        if (guess.text.isBlank()) return null
        if (forceGratitude) return Routed(Destination.GRATITUDE, guess.text, guess)
        val destination = when (guess.kind) {
            CaptureKind.GRATITUDE -> Destination.GRATITUDE
            CaptureKind.TASK -> Destination.TASK_TOMORROW
            CaptureKind.IDEA -> Destination.SCRAP
            CaptureKind.MOMENT, CaptureKind.SCRAP -> Destination.MOMENT
        }
        val text = if (guess.kind == CaptureKind.IDEA) "💡 ${guess.text}" else guess.text
        return Routed(destination, text, guess)
    }

    /** The three one-tap moods of the notification: score (1..5) and the label the app stores. */
    val MOODS: List<Triple<Int, String, String>> = listOf(
        Triple(4, "Good", "🙂 Good"),
        Triple(3, "Okay", "😐 Okay"),
        Triple(2, "Bad", "😔 Hard")
    )
}
