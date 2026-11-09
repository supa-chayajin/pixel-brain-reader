package cloud.wafflecommons.pixelbrainreader.data.mood

/**
 * Five emojis for the mood scores, from 1 (worst) to 5 (best).
 *
 * The score is the data; the emoji is only its face. Every screen, widget and notification shows a
 * mood through the ONE set picked in Settings ▸ RPG & Gamification Rules (saved on the device, like
 * the Daily layout), so picking another set re-skins the moods already logged too.
 */
data class MoodEmojiSet(val id: String, val name: String, val faces: List<String>) {

    init {
        require(faces.size == SCORES) { "A mood emoji set has $SCORES faces, not ${faces.size}" }
    }

    /** The face for a score; a score outside 1..5 is clamped. */
    fun forScore(score: Int): String = faces[score.coerceIn(1, SCORES) - 1]

    /** The face for a day's average score, on the thresholds the app has always used. */
    fun forAverage(average: Double): String = when {
        average.isNaN() -> faces[2]
        average < 1.8 -> faces[0]
        average < 2.6 -> faces[1]
        average < 3.4 -> faces[2]
        average < 4.2 -> faces[3]
        else -> faces[4]
    }

    companion object {
        const val SCORES = 5
    }
}

object MoodEmojiSets {

    /** The faces the check-in sheet has always shown: the default, so nothing changes until a set is picked. */
    val CLASSIC = MoodEmojiSet("classic", "Classic faces", listOf("😫", "😞", "😐", "🙂", "🤩"))

    val DEFAULT: MoodEmojiSet = CLASSIC

    /** What the settings dropdown offers, in this order. The ids are saved: never rename one. */
    val ALL: List<MoodEmojiSet> = listOf(
        CLASSIC,
        MoodEmojiSet("gentle", "Gentle faces", listOf("😢", "😕", "😐", "🙂", "😊")),
        MoodEmojiSet("expressive", "Expressive faces", listOf("😭", "😟", "😶", "😄", "🥳")),
        MoodEmojiSet("cats", "Cats", listOf("😿", "😾", "🐱", "😺", "😻")),
        MoodEmojiSet("weather", "Weather", listOf("⛈️", "🌧️", "☁️", "🌤️", "☀️")),
        MoodEmojiSet("moon", "Moon phases", listOf("🌑", "🌘", "🌗", "🌖", "🌕")),
        MoodEmojiSet("plants", "Plants", listOf("🥀", "🍂", "🌱", "🌿", "🌸")),
        MoodEmojiSet("hearts", "Hearts", listOf("💔", "🩶", "🤍", "💛", "💖")),
        MoodEmojiSet("numbers", "Numbers", listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"))
    )

    /** The set saved under [id]. None saved yet, or an id this version doesn't know: the default. */
    fun byId(id: String?): MoodEmojiSet = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
