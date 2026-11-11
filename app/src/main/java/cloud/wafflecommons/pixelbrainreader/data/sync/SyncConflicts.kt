package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeFormatter
import org.eclipse.jgit.merge.MergeResult
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** A file both the phone and GitHub changed (S1): both texts, and git's merge with each disagreement marked. */
data class ConflictFile(val path: String, val mine: String?, val theirs: String?, val merged: String?) {
    /** The app's data files get keep mine / keep theirs only (owner, 2026-11-11). */
    val isData: Boolean get() = path.endsWith(".json", ignoreCase = true)
}

/** What the owner chose for one conflicted file. */
sealed interface ConflictChoice {
    data object Mine : ConflictChoice
    data object Theirs : ConflictChoice
    data class Edited(val text: String) : ConflictChoice
    /** GitHub's version stays in place; the phone's is saved beside it ([SyncConflicts.copyPath]). */
    data object Both : ConflictChoice
}

/** The conflict a sync stopped on, remembered inside `.git` so a fresh clone starts clean. */
data class PendingConflict(val remote: String, val paths: List<String>, val backups: List<String>)

/** A file conflicts without a choice: GitHub moved again while the owner was choosing. */
class ConflictChangedException(val paths: List<String>) : Exception("New conflicts: ${paths.joinToString()}")

/**
 * The sync conflict's git side (S1, owner 2026-11-11): what conflicts, and the resolution — the
 * phone's unpushed commits folded into one commit, replayed on top of GitHub's (a rebase, never a
 * merge commit), each conflicted file settled by its choice. Runs on an open [Git] the caller holds
 * the git lock for.
 */
object SyncConflicts {

    const val MARKER_FILE = "pixelbrain-sync-conflict"
    const val PHONE_COPY = "téléphone"
    const val STOPPED_MESSAGE = "Sync stopped: files changed both here and on GitHub. Resolve them from Daily."
    const val PAUSED_MESSAGE = "Sync paused until the conflict is resolved: open it from Daily."
    private val LABELS = listOf("base", PHONE_COPY, "GitHub")

    // --- The remembered conflict ---

    fun encode(pending: PendingConflict): String = buildString {
        append("remote ").append(pending.remote).append('\n')
        pending.paths.forEach { append("path ").append(it).append('\n') }
        pending.backups.forEach { append("backup ").append(it).append('\n') }
    }

    fun decode(text: String): PendingConflict? {
        val lines = text.lines().filter { it.isNotBlank() }
        val remote = lines.firstOrNull { it.startsWith("remote ") }?.removePrefix("remote ")?.trim() ?: return null
        return PendingConflict(
            remote = remote,
            paths = lines.filter { it.startsWith("path ") }.map { it.removePrefix("path ") },
            backups = lines.filter { it.startsWith("backup ") }.map { it.removePrefix("backup ") }
        )
    }

    fun read(gitDir: File): PendingConflict? = File(gitDir, MARKER_FILE).takeIf { it.isFile }?.let { decode(it.readText()) }

    fun write(gitDir: File, pending: PendingConflict) {
        VaultWriteGate.check()
        File(gitDir, MARKER_FILE).writeText(encode(pending))
    }

    fun clear(gitDir: File) {
        File(gitDir, MARKER_FILE).delete()
    }

    /** "Notes/Plan.md" → "Notes/Plan (téléphone).md". */
    fun copyPath(path: String): String {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "$dir${name.substring(0, dot)} ($PHONE_COPY)${name.substring(dot)}" else "$dir$name ($PHONE_COPY)"
    }

    // --- What conflicts ---

    /** The files a merge of [upstream] into HEAD can't settle, read in core: nothing is written. */
    fun inspect(repo: Repository, upstream: ObjectId): List<ConflictFile> {
        RevWalk(repo).use { walk ->
            val head = walk.parseCommit(repo.resolve("HEAD") ?: return emptyList())
            val theirs = walk.parseCommit(upstream)
            val merger = MergeStrategy.RECURSIVE.newMerger(repo, true) as ResolveMerger
            if (merger.merge(head, theirs)) return emptyList()
            val results = merger.mergeResults
            return merger.unmergedPaths.distinct().map { path ->
                @Suppress("UNCHECKED_CAST")
                val merged = (results[path] as? MergeResult<RawText>)?.let { result ->
                    ByteArrayOutputStream().also { out -> MergeFormatter().formatMerge(out, result, LABELS, StandardCharsets.UTF_8) }
                        .toString(StandardCharsets.UTF_8.name())
                }
                ConflictFile(path, textAt(repo, head, path), textAt(repo, theirs, path), merged)
            }
        }
    }

    fun mergeBase(repo: Repository, a: ObjectId, b: ObjectId): RevCommit? = RevWalk(repo).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(a))
        walk.markStart(walk.parseCommit(b))
        walk.next()
    }

    // --- The resolution ---

    /**
     * Stages everything (the [backups] deleted first), folds the phone's commits since the merge base
     * into one, replays it onto [upstream] and settles each conflicted file by its choice. [review]
     * runs on the staged result before the commit (the confidentiality guard); it throws to refuse.
     * A conflict without a choice aborts the rebase and throws [ConflictChangedException]. Returns HEAD.
     */
    fun resolve(
        git: Git,
        upstream: ObjectId,
        choices: Map<String, ConflictChoice>,
        backups: List<String>,
        message: String,
        author: PersonIdent,
        review: (Repository) -> Unit = {}
    ): ObjectId {
        VaultWriteGate.check()
        val repo = git.repository
        val workTree = repo.workTree
        backups.forEach { File(workTree, it).delete() }
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()

        val head = repo.resolve("HEAD") ?: throw IllegalStateException("No commit to resolve")
        val base = mergeBase(repo, head, upstream) ?: throw IllegalStateException("No common history with GitHub")

        // Fold: the index keeps the phone's tree, the branch moves back to the base.
        git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(base.name).call()
        val folded = if (git.status().call().hasUncommittedChanges()) {
            git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
        } else {
            null
        }
        if (folded == null) {
            // Nothing of the phone's is left to replay (only backups): take GitHub's as it is.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(upstream.name).call()
            return upstream
        }

        var result = git.rebase().setUpstream(upstream).call()
        if (result.status == RebaseResult.Status.STOPPED || result.status == RebaseResult.Status.CONFLICTS) {
            val conflicting = git.status().call().conflicting.toList()
            val unchosen = conflicting.filter { it !in choices }
            if (unchosen.isNotEmpty()) {
                abort(git)
                throw ConflictChangedException(unchosen)
            }
            val theirsCommit = RevWalk(repo).use { it.parseCommit(upstream) }
            val mineCommit = RevWalk(repo).use { it.parseCommit(folded.id) }
            for (path in conflicting) {
                when (val choice = choices.getValue(path)) {
                    ConflictChoice.Mine -> place(git, path, textAt(repo, mineCommit, path))
                    ConflictChoice.Theirs -> place(git, path, textAt(repo, theirsCommit, path))
                    is ConflictChoice.Edited -> place(git, path, choice.text)
                    ConflictChoice.Both -> {
                        place(git, path, textAt(repo, theirsCommit, path))
                        textAt(repo, mineCommit, path)?.let { place(git, copyPath(path), it) }
                    }
                }
            }
            try {
                review(repo)
            } catch (e: Exception) {
                abort(git)
                throw e
            }
            result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
            if (result.status == RebaseResult.Status.NOTHING_TO_COMMIT) {
                result = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
            }
        }
        if (!result.status.isSuccessful) {
            abort(git)
            throw IllegalStateException("The resolution didn't apply: ${result.status}")
        }
        return repo.resolve("HEAD")
    }

    private fun abort(git: Git) {
        try {
            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rebase may already be gone; the repository is left as the abort found it.
        }
    }

    /** Writes [text] at [path] and stages it; null deletes the file. */
    private fun place(git: Git, path: String, text: String?) {
        val file = File(git.repository.workTree, path)
        if (text == null) {
            file.delete()
            git.rm().addFilepattern(path).call()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(text)
            git.add().addFilepattern(path).call()
        }
    }

    private fun textAt(repo: Repository, commit: RevCommit, path: String): String? =
        TreeWalk.forPath(repo, path, commit.tree)?.use { walk ->
            String(repo.open(walk.getObjectId(0)).bytes, StandardCharsets.UTF_8)
        }
}
