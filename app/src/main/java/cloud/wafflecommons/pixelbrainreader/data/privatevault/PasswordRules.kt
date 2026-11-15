package cloud.wafflecommons.pixelbrainreader.data.privatevault

/**
 * What a new password for the private journal needs (12.18, V1; owner's D4: at least 12 characters,
 * nothing else). Every encrypted copy sits on GitHub, where a stolen copy can be guessed at offline
 * without limit: length is what protects it.
 */
object PasswordRules {
    const val MIN_LENGTH = 12

    /** Why the change can't go ahead, or null when it can. */
    fun problem(current: String, new: String, repeat: String): String? = when {
        current.isEmpty() -> "Type the current password."
        new.length < MIN_LENGTH -> "The new password needs at least $MIN_LENGTH characters."
        new != repeat -> "The two new passwords differ."
        new == current -> "The new password is the current one."
        else -> null
    }
}
