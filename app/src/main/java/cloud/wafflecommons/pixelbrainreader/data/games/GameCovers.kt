package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDate

/** What a cover edit did (13.4, P5). */
enum class CoverEditResult { SAVED, NOTHING_TO_REMOVE, GONE, READ_ONLY, FAILED }

/**
 * The owner's own game picture (13.4, P5), pure: the `game_cover` key, its file, and how it resolves.
 *
 * The key holds a wikilink (`game_cover: "[[Glass_Tower (cover).jpg]]"`), so Obsidian follows a
 * rename, and the app's file sits in the page's own folder: `<page file name without .md> (cover).jpg`,
 * a JPEG the phone re-encoded. Both the key and the file name are persisted: never rename either.
 * A value the owner typed on the desktop is read too: an embed, a wikilink or a plain path, as long
 * as it names a picture file of the vault (never a private, hidden or traversing path).
 */
object GameCovers {

    const val KEY = "game_cover"
    const val SUFFIX = " (cover).jpg"
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    private const val DATE_UPDATED = "date_updated"
    private const val PRIVATE_ROOT = "99_Private"

    /** Characters a link or a desktop's file system refuses in a name: each becomes a space. */
    private const val UNSAFE = "[]|#^\\/:*?\"<>"

    /**
     * The app's picture file for the page [pageKey] (its file name without `.md`): the key with
     * [SUFFIX], each character a link or a file system refuses (and each control character) made a
     * space, runs of spaces collapsed, trimmed, "Game" when nothing is left. For a key without those
     * characters (every page the syncs create) this is exactly `<key> (cover).jpg`.
     */
    fun fileName(pageKey: String): String {
        val spaced = buildString {
            for (ch in pageKey) append(if (ch in UNSAFE || ch.isISOControl()) ' ' else ch)
        }
        val collapsed = buildString {
            var space = false
            for (ch in spaced) {
                if (ch == ' ') {
                    space = true
                } else {
                    if (space && isNotEmpty()) append(' ')
                    space = false
                    append(ch)
                }
            }
        }
        return collapsed.ifEmpty { "Game" } + SUFFIX
    }

    /** The app's picture file of [page]: [fileName] in the page's own folder (just the name at the vault's root). */
    fun ownPath(page: GamePage): String {
        val name = fileName(page.key)
        return if (page.folder.isEmpty()) name else "${page.folder}/$name"
    }

    fun link(fileName: String): String = "[[$fileName]]"

    /**
     * What a `game_cover` value names (the value already unquoted): `![[x]]`, `[[x]]` or a plain path
     * `x`, cut at its first `|` and `#`, a leading `./` dropped. Null when blank, or when it can't be a
     * vault picture: a leading `/`, a backslash, a control character, an empty segment, a `.` or `..`
     * segment, a hidden one, a private first segment, or an extension other than jpg, jpeg, png, webp.
     */
    fun target(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()
        text = when {
            text.startsWith("![[") && text.endsWith("]]") -> text.substring(3, text.length - 2)
            text.startsWith("[[") && text.endsWith("]]") -> text.substring(2, text.length - 2)
            else -> text
        }
        text = text.substringBefore('|').substringBefore('#').trim()
        text = text.removePrefix("./")
        if (text.isEmpty() || text.startsWith("/") || '\\' in text || text.any { it.isISOControl() }) return null
        val segments = text.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (segments.first().equals(PRIVATE_ROOT, ignoreCase = true)) return null
        val extension = segments.last().substringAfterLast('.', "").lowercase()
        if (extension !in IMAGE_EXTENSIONS) return null
        return text
    }

    /**
     * The vault file [page]'s cover names, through the file index (Room's, never a walk of the disk):
     * a name alone in the page's folder first, then anywhere by name (the shallowest), a path by its
     * vault path (Obsidian's way). Only an attachment the same rules accept; null otherwise.
     */
    fun resolve(page: GamePage, index: VaultFileIndex): String? {
        val target = page.cover ?: return null
        val candidate = if ('/' in target) target else listOf(page.folder, target).filter { it.isNotEmpty() }.joinToString("/")
        val found = index.resolve(candidate) ?: return null
        return found.takeIf { VaultFileIndex.isAttachment(it) && target(it) != null }
    }

    /** True when [target] (a parsed `game_cover`) names [page]'s own file: its bare name or its path, ignoring case. */
    fun namesOwnFile(page: GamePage, target: String): Boolean =
        target.equals(fileName(page.key), ignoreCase = true) || target.equals(ownPath(page), ignoreCase = true)

    /** [content] with `game_cover` set to [fileName]'s link and `date_updated` moved; every other byte kept. */
    fun withCover(content: String, fileName: String, today: LocalDate): String =
        dated(content, FrontmatterLines.set(content, KEY, VaultSchema.doubleQuoted(link(fileName))), today)

    /** [content] without `game_cover` (and `date_updated` moved when something changed). */
    fun withoutCover(content: String, today: LocalDate): String =
        dated(content, FrontmatterLines.remove(content, KEY), today)

    /** [next] with today in `date_updated` when it differs from [content] and the page has that key. */
    private fun dated(content: String, next: String, today: LocalDate): String =
        if (next != content && FrontmatterLines.get(next, DATE_UPDATED) != null) {
            FrontmatterLines.set(next, DATE_UPDATED, VaultSchema.singleQuoted(today.toString()))
        } else {
            next
        }
}
