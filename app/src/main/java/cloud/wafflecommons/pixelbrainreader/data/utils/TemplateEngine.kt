package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fills a template's "Mustache" placeholders.
 *
 * `{{date}}` (YYYY-MM-DD), `{{time}}` (HH:mm) and `{{title}}` are filled automatically; every other
 * `{{variable}}` is a field of the template form (E1), filled from [apply]'s values — one left
 * blank stays as it is, to fill later. A template's own `date_created` / `date_updated` (frozen
 * the day it was written) become today's date.
 */
object TemplateEngine {

    private val VARIABLE = Regex("""\{\{\s*([A-Za-z0-9_\-]+)\s*\}\}""")
    private val AUTOMATIC = setOf("date", "time", "title")

    /** The form's fields: the template's variables in order of appearance, without the automatic ones. */
    fun variables(templateContent: String): List<String> =
        VARIABLE.findAll(templateContent).map { it.groupValues[1] }.filter { it.lowercase() !in AUTOMATIC }.distinct().toList()

    /** True for variables a date picker suits better than a text field. */
    fun isDateVariable(name: String): Boolean = name.lowercase().let { "date" in it || "deadline" in it || "echeance" in it || "due" in it }

    fun apply(templateContent: String, title: String, values: Map<String, String> = emptyMap(), now: LocalDateTime = LocalDateTime.now()): String {
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val filled = VARIABLE.replace(templateContent) { match ->
            when (val name = match.groupValues[1]) {
                "date" -> dateStr
                "time" -> timeStr
                "title" -> title
                else -> values[name]?.trim()?.takeIf { it.isNotEmpty() } ?: match.value
            }
        }
        var result = filled
        for (key in listOf("date_created", "date_updated")) {
            if (FrontmatterLines.get(result, key) != null) result = FrontmatterLines.set(result, key, VaultSchema.singleQuoted(dateStr))
        }
        return result
    }
}
