package cloud.wafflecommons.pixelbrainreader.data.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Shared logic for replacing "Mustache" placeholders in templates.
 * Supported Placeholders:
 * - {{date}}: YYYY-MM-DD
 * - {{time}}: HH:mm
 * - {{title}}: The provided title (filename without extension)
 */
object TemplateEngine {

    fun apply(templateContent: String, title: String): String {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        
        return templateContent
            .replace("{{date}}", dateStr)
            .replace("{{time}}", timeStr)
            .replace("{{title}}", title)
    }
}
