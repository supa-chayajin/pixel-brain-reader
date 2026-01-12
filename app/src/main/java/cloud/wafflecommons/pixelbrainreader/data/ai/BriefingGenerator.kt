package cloud.wafflecommons.pixelbrainreader.data.ai

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BriefingGenerator @Inject constructor() {
    
    private val quotes = listOf(
        "Make it work, make it right, make it fast. - Kent Beck",
        "First, solve the problem. Then, write the code. - John Johnson",
        "Simplicity is the soul of efficiency. - Austin Freeman",
        "Code is read much more often than it is written. - Guido van Rossum",
        "The only way to go fast, is to go well. - Robert C. Martin",
        "Clean code always looks like it was written by someone who cares. - Robert C. Martin",
        "Premature optimization is the root of all evil. - Donald Knuth"
    )

    fun getDailyQuote(): String {
        return quotes.random()
    }
}
