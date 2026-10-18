package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Writes generated drafts (handovers, briefings, reports) without ever replacing one the user may have edited. */
@Singleton
class DraftWriter @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    /** Writes [content] at [path], or at "name (2).md"… when it exists; returns the path written. */
    suspend fun write(path: String, content: String): String {
        var target = path
        var n = 2
        while (fileRepository.fileExists(target)) {
            target = "${path.removeSuffix(".md")} ($n).md"
            n++
        }
        fileRepository.saveFileLocally(target, content)
        pushScheduler.requestPush()
        return target
    }
}
