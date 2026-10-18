package cloud.wafflecommons.pixelbrainreader.data.web

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.net.URI
import java.time.LocalDate

/**
 * The note an import becomes: the vault's frontmatter (standard keys, then the clipping's source,
 * author, publication and capture dates), one H1, a source callout, the hero image, an optional
 * on-device summary, the passage the user shared, then the article. Shared by the share screen and
 * the background importer. The note is a generated document: its words are French.
 */
object WebClipNote {

    private val UNSAFE_NAME = Regex("""[\\/:*?"<>|#^\[\]\r\n\t]""")
    private val SPACES = Regex("""\s+""")
    private val H1 = Regex("""^#\s+(.*)$""")

    /** A title as a file name: no path or link characters, no trailing dots, at most 80 characters. */
    fun fileName(title: String): String =
        title.replace(UNSAFE_NAME, " ").replace(SPACES, " ").trim().trimEnd('.').take(80).trim().ifEmpty { "Clipping" }

    fun render(clip: WebClip, title: String, folder: String, summary: String?, passage: String?, today: LocalDate): String = buildString {
        header(title, folder, clip.url, clip.author, clip.published, today, kind = if (clip.isVideo) "video" else "web")
        val source = clip.siteName ?: host(clip.url)
        append("> [!info] Source\n> ")
        append(listOfNotNull("[${linkText(source)}](${clip.url})", clip.author, clip.published).joinToString("  ·  "))
        append("\n\n")
        clip.heroImage?.let { append("![](").append(it).append(")\n\n") }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { callout("[!abstract] Résumé (Gemini Nano, sur l'appareil)", it) }
        passage?.trim()?.takeIf { it.isNotEmpty() }?.let { callout("[!quote] Passage partagé", it) }
        if (clip.quality != ClipQuality.FULL) {
            append("> [!warning] Extrait partiel\n> Seule une partie de la page a pu être lue : l'article complet est à la source.\n\n")
        }
        append("---\n\n").append(body(clip.markdown, title)).append('\n')
    }

    /** When the page couldn't be read: the link, why, the page's description and what was shared with it. */
    fun linkOnly(
        url: String,
        title: String,
        folder: String,
        reason: String,
        description: String?,
        passage: String?,
        today: LocalDate
    ): String = buildString {
        header(title, folder, url, author = null, published = null, today = today)
        append("> [!info] Source\n> [").append(linkText(host(url))).append("](").append(url).append(")\n\n")
        append("> [!warning] Page non importée\n> ").append(reason.replace(SPACES, " ").trim()).append("\n\n")
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        passage?.trim()?.takeIf { it.isNotEmpty() }?.let { callout("[!quote] Passage partagé", it) }
        while (endsWith("\n\n")) setLength(length - 1)
    }

    /** Text shared without a link (a selection, a message). */
    fun text(title: String, folder: String, markdown: String, today: LocalDate): String = buildString {
        append(VaultSchema.frontmatter(title = title, path = "${folder.trim('/')}/note.md", date = today, tags = listOf("clipping")))
        append("\n# ").append(oneLine(title)).append("\n\n")
        append(body(markdown, title)).append('\n')
    }

    /** Why the page couldn't be read, for the note. */
    fun reason(problem: PageProblem, status: Int?, detail: String?): String {
        val http = status?.let { " (HTTP $it)" }.orEmpty()
        return when (problem) {
            PageProblem.NOT_PUBLIC -> "Cette adresse n'est pas une page web publique : l'import est refusé."
            PageProblem.NOT_HTML -> "Ce lien ne mène pas à une page web${detail?.let { " ($it)" }.orEmpty()}."
            PageProblem.GONE -> "La page n'existe plus$http."
            PageProblem.PAYWALL -> "La page est réservée aux abonnés$http."
            PageProblem.REFUSED -> "Le site a refusé la lecture de la page$http."
            PageProblem.BLOCKED -> "Le site demande une vérification humaine : seule l'adresse a pu être gardée."
            PageProblem.SERVER -> "Le site ne répond pas correctement$http."
            PageProblem.NETWORK -> "La page n'a pas pu être chargée (réseau)."
            PageProblem.NO_TEXT -> "La page n'a pas de texte lisible (construite par des scripts ou réservée aux abonnés) : seule l'adresse a pu être gardée."
        }
    }

    /**
     * The body under the note's own H1 (rule R3): a first line repeating the title goes, other H1s
     * become H2. Code blocks are left alone.
     */
    private fun body(markdown: String, title: String): String {
        var inFence = false
        var first = true
        val lines = markdown.trim().lines().mapNotNull { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            val heading = if (inFence) null else H1.find(line)?.groupValues?.get(1)?.trim()
            val result = when {
                heading == null -> line
                first && heading.equals(oneLine(title), ignoreCase = true) -> null
                else -> "#$line"
            }
            if (line.isNotBlank()) first = false
            result
        }
        return lines.joinToString("\n").trim()
    }

    private fun StringBuilder.callout(header: String, text: String) {
        append("> ").append(header).append('\n')
        text.lines().forEach { line -> append(if (line.isBlank()) ">" else "> ${line.trimEnd()}").append('\n') }
        append('\n')
    }

    private fun StringBuilder.header(
        title: String,
        folder: String,
        url: String,
        author: String?,
        published: String?,
        today: LocalDate,
        kind: String = "web"
    ) {
        append(
            VaultSchema.frontmatter(
                title = title,
                path = "${folder.trim('/')}/clipping.md",
                date = today,
                tags = listOf("clipping", kind),
                extra = buildList {
                    add("source" to VaultSchema.doubleQuoted(url))
                    author?.let { add("author" to VaultSchema.doubleQuoted(it)) }
                    published?.let { add("published" to VaultSchema.singleQuoted(it)) }
                    add("captured" to VaultSchema.singleQuoted(today.toString()))
                }
            )
        )
        append("\n# ").append(oneLine(title)).append("\n\n")
    }

    private fun oneLine(text: String): String = text.replace(SPACES, " ").trim()

    /** A link's text: brackets would end it early. */
    private fun linkText(text: String): String = text.replace("[", "(").replace("]", ")")

    private fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
