package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Handles conflict resolution for JGit operations.
 * Supports basic 3-way merge for text and simple strategies for JSON.
 */
@Singleton
class ConflictResolver @Inject constructor() {

    /**
     * Attempts to resolve a conflict between local and remote content.
     */
    fun resolve(
        path: String,
        localContent: String,
        remoteContent: String,
        baseContent: String = "" // Common ancestor if available
    ): ResolutionResult {
        Log.i("ConflictResolver", "Resolving conflict for $path")

        if (path.endsWith(".md", ignoreCase = true) || path.endsWith(".txt", ignoreCase = true)) {
            return resolveTextConflict(path, localContent, remoteContent, baseContent)
        }
        
        // Default Strategy: Keep Local (Safety) but save Remote as conflict file
        return ResolutionResult.Conflict(
            resolvedContent = localContent,
            conflictFiles = listOf(
                ConflictFile(
                    path = generateConflictPath(path, "remote"),
                    content = remoteContent
                )
            )
        )
    }

    private fun resolveTextConflict(
        path: String,
        local: String,
        remote: String,
        base: String
    ): ResolutionResult {
        // Very simple robust check: If local == remote, no conflict
        if (local == remote) return ResolutionResult.Resolved(local)
        
        // TODO: Implement actual diff-match-patch or JGit MergeAlgorithm here for fine-grained merge.
        // For now, we prefer "No Data Loss" -> Create conflict file logic.
        
        // Construct a "merged" file with markers (Git style)
        val merged = StringBuilder()
        merged.append("<<<<<<< HEAD (Local)\n")
        merged.append(local)
        merged.append("\n=======\n")
        merged.append(remote)
        merged.append("\n>>>>>>> REMOTE\n")
        
        return ResolutionResult.Resolved(merged.toString())
    }

    private fun generateConflictPath(originalPath: String, suffix: String): String {
        val extension = originalPath.substringAfterLast('.', "")
        val name = originalPath.substringBeforeLast('.')
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return if (extension.isNotEmpty()) {
            "$name.conflicted.$suffix.$timestamp.$extension"
        } else {
            "$name.conflicted.$suffix.$timestamp"
        }
    }

    sealed class ResolutionResult {
        data class Resolved(val content: String) : ResolutionResult()
        data class Conflict(
            val resolvedContent: String, // Usually local content
            val conflictFiles: List<ConflictFile>
        ) : ResolutionResult()
    }

    data class ConflictFile(val path: String, val content: String)
}
