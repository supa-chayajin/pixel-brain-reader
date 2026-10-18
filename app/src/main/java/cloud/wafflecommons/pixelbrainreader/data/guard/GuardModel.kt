package cloud.wafflecommons.pixelbrainreader.data.guard

import kotlinx.serialization.Serializable

/**
 * What the next commit would record for one path, as the confidentiality guard sees it.
 *
 * [oldText] is the committed version (null for a new file); [newText] is the staged version, or
 * null when the content can't be checked as text (binary, oversized, an encrypted private note) —
 * only the path is checked then. [oldPath] is set for renames and copies.
 */
data class StagedText(
    val path: String,
    val oldPath: String?,
    val oldText: String?,
    val newText: String?,
    val isNewPath: Boolean
)

enum class FindingKind { NAME, SECRET }

/**
 * One thing that holds the commit. [ruleKey] identifies what matched without storing it in clear
 * for secrets (`term:<folded term>` or `secret:<rule>:<hash>`); [line] is 1-based, 0 for the file
 * name. [excerpt] is the surrounding text with any secret masked.
 */
data class GuardFinding(
    val path: String,
    val kind: FindingKind,
    val ruleKey: String,
    val label: String,
    val line: Int,
    val excerpt: String
)

/** "This may be pushed": [scope] is a note path, or a folder path ending with `/`. */
@Serializable
data class AllowEntry(val scope: String, val ruleKey: String) {
    fun covers(finding: GuardFinding): Boolean =
        ruleKey == finding.ruleKey &&
            (finding.path == scope || (scope.endsWith("/") && finding.path.startsWith(scope)))
}

/** The guard's settings. They stay on the device (encrypted): written to the vault, the deny-list would be pushed. */
@Serializable
data class GuardSettings(
    val terms: List<String> = emptyList(),
    val detectSecrets: Boolean = true,
    val allowed: List<AllowEntry> = emptyList()
)

/**
 * The commit path's view of the guard, so JGit code can be tested on the JVM with a fake.
 * Implementations are called under the git lock, on an IO thread.
 */
interface CommitGuard {
    /** What holds this commit, after the allow-list. Empty means it may go ahead. */
    fun review(changes: List<StagedText>): List<GuardFinding>

    /** True when a commit message names a protected term; the commit then gets a neutral message. */
    fun mentionsProtectedTerm(message: String): Boolean

    companion object {
        /** Lets everything through (tests that aren't about the guard). */
        val NONE: CommitGuard = object : CommitGuard {
            override fun review(changes: List<StagedText>): List<GuardFinding> = emptyList()
            override fun mentionsProtectedTerm(message: String): Boolean = false
        }
    }
}

/** A commit the guard refused to record. The changes stay staged on the device until reviewed. */
class CommitHeldException(val findings: List<GuardFinding>) : Exception(holdMessage(findings)) {
    private companion object {
        fun holdMessage(findings: List<GuardFinding>): String {
            val notes = findings.map { it.path }.distinct().size
            val items = if (findings.size == 1) "1 possible confidential item" else "${findings.size} possible confidential items"
            val where = if (notes == 1) "1 note" else "$notes notes"
            return "Sync held: $items in $where. Review them in Settings > Confidentiality."
        }
    }
}
