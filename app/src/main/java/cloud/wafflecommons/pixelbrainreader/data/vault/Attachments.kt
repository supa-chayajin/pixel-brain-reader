package cloud.wafflecommons.pixelbrainreader.data.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where a dropped or pasted file goes (E6), following the vault's own Obsidian setting
 * (`.obsidian/app.json` → `attachmentFolderPath`), and how it is named: Obsidian's
 * "Pasted image 20261018143200.png".
 */
object Attachments {

    const val FALLBACK_FOLDER = "99_System/Assets"

    /** The `attachmentFolderPath` of an `app.json`, or null when absent or unreadable. */
    fun settingOf(appJson: String?): String? = appJson?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["attachmentFolderPath"]?.jsonPrimitive?.content }.getOrNull()
    }

    /**
     * The folder for a note at [notePath]: `./` or `./sub` is relative to the note's folder, `/` is
     * the vault root, anything else a vault path; no setting falls back to [FALLBACK_FOLDER].
     */
    fun folder(setting: String?, notePath: String): String {
        val noteFolder = notePath.substringBeforeLast('/', "")
        val value = setting?.trim() ?: return FALLBACK_FOLDER
        return when {
            value == "/" || value.isEmpty() -> ""
            value == "./" || value == "." -> noteFolder
            value.startsWith("./") -> listOf(noteFolder, value.removePrefix("./").trim('/')).filter { it.isNotEmpty() }.joinToString("/")
            else -> value.trim('/')
        }
    }

    fun fileName(now: LocalDateTime, mime: String, original: String? = null): String {
        val extension = extensionOf(mime) ?: original?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.lowercase() ?: "bin"
        val stem = if (mime.startsWith("image/")) "Pasted image" else original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Pasted file"
        return "$stem ${now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}.$extension"
    }

    fun extensionOf(mime: String): String? = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "application/pdf" -> "pdf"
        else -> null
    }

    /** The embed Obsidian resolves by name. */
    fun embed(path: String): String = "![[${path.substringAfterLast('/')}]]"
}
