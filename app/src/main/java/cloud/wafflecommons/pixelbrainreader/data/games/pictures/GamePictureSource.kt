package cloud.wafflecommons.pixelbrainreader.data.games.pictures

import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each page's picture (13.4, GP1), read once per call: the links, both snapshots and the owner's
 * covers, then [GameArtRules.of] for every page. It only reads (a read-only device shows pictures
 * too). A picture is decoration: when a read fails, the pages get their initials, never an error.
 */
@Singleton
class GamePictureSource @Inject constructor(private val games: GamesRepository) {

    /** [pages]' pictures, by page path. */
    suspend fun artFor(pages: List<GamePage>): Map<String, GameArt> {
        if (pages.isEmpty()) return emptyMap()
        return try {
            val links = games.links()
            val psn = games.snapshot()
            val xbox = games.xboxSnapshot()
            val covers = games.coverFiles(pages)
            // On IO: an own picture's last change is read from the disk.
            withContext(Dispatchers.IO) {
                pages.associate { page ->
                    val (psnTitles, xboxTitles) = GameArtRules.titlesOf(page, links, psn, xbox)
                    val art = GameArtRules.of(page, covers[page.path], psnTitles, xboxTitles)
                    page.path to (art.ownFile?.let { art.copy(ownFileStamp = it.lastModified()) } ?: art)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pages.associate { it.path to GameArtRules.initialsOnly(it.title) }
        }
    }

    /** [page]'s picture. */
    suspend fun artFor(page: GamePage): GameArt = artFor(listOf(page))[page.path] ?: GameArtRules.initialsOnly(page.title)
}
