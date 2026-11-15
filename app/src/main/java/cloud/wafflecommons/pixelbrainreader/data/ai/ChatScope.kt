package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a scoped question asks about (K2, 12.20; the owner's D5–D7): one note, or a folder and
 * everything under it. Private notes can't be asked (D7). Pure.
 */
data class ChatScope(val path: String, val isFolder: Boolean) {

    /** The chip's words: the note's title, or the folder as people say it. */
    val label: String
        get() = if (isFolder) {
            projectDisplayName(path.substringAfterLast('/'))
        } else {
            path.substringAfterLast('/').removeSuffix(".md")
        }

    /** Whether a passage of [fileId] is inside this scope. */
    fun contains(fileId: String): Boolean = if (isFolder) fileId.startsWith("$path/") else fileId == path

    /** How the question that used it keeps it, in its `sources` (D7: shown again in the history). */
    fun encode(): String = MARK + (if (isFolder) FOLDER else NOTE) + path

    companion object {
        private const val MARK = "scope:"
        private const val NOTE = "note:"
        private const val FOLDER = "folder:"
        private const val PRIVATE_ROOT = "99_Private"

        fun note(path: String): ChatScope? = path.trim('/').takeIf { allows(it, isFolder = false) }?.let { ChatScope(it, isFolder = false) }

        fun folder(path: String): ChatScope? = path.trim('/').takeIf { allows(it, isFolder = true) }?.let { ChatScope(it, isFolder = true) }

        /** A note (`.md`) or a folder outside the private vault. */
        fun allows(path: String, isFolder: Boolean): Boolean {
            val clean = path.trim('/')
            if (clean.isBlank() || clean == PRIVATE_ROOT || clean.startsWith("$PRIVATE_ROOT/")) return false
            return isFolder || clean.endsWith(".md")
        }

        /** The scope a question kept in its sources, or null. */
        fun decode(sources: List<String>): ChatScope? {
            val stored = sources.firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK) ?: return null
            return when {
                stored.startsWith(FOLDER) -> folder(stored.removePrefix(FOLDER))
                stored.startsWith(NOTE) -> note(stored.removePrefix(NOTE))
                else -> null
            }
        }
    }
}

/**
 * The scope grounded questions answer from, set by a note's header or a folder's menu and kept
 * until it is cleared. Every Chat screen reads this one: a Chat screen kept alive in the saved back
 * stack used to take it while navigation showed a new one without it. Lives as long as the process.
 */
@Singleton
class ChatScopeRepository @Inject constructor() {
    private val _scope = MutableStateFlow<ChatScope?>(null)
    val scope: StateFlow<ChatScope?> = _scope.asStateFlow()

    fun offer(scope: ChatScope) {
        _scope.value = scope
    }

    fun clear() {
        _scope.value = null
    }
}
