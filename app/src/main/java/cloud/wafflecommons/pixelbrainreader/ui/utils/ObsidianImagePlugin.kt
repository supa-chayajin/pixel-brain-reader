package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.markwon.AbstractMarkwonPlugin
import java.util.regex.Pattern

class ObsidianImagePlugin(
    private val vaultRootDir: java.io.File? = null
) : AbstractMarkwonPlugin() {

    private val pattern = Pattern.compile("!\\[\\[(.*?)\\]\\]")

    override fun processMarkdown(markdown: String): String {
        val matcher = pattern.matcher(markdown)
        val sb = StringBuffer()
        while (matcher.find()) {
            val rawTarget = matcher.group(1) ?: continue
            val cleanPath = rawTarget.substringBefore("|").trim()
            val resolvedFile = resolveVaultFile(cleanPath)
            val replacement = if (resolvedFile != null) {
                val uri = resolvedFile.toURI().toString()
                "![${resolvedFile.name}]($uri)"
            } else {
                // Transform ![[file.png]] -> <obsidian-image path="file.png" />
                "<obsidian-image path=\"$rawTarget\" />"
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun resolveVaultFile(relativePath: String): java.io.File? {
        val root = vaultRootDir ?: return null
        if (!root.exists()) return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val direct = java.io.File(rootCanonical, relativePath)
        val directCanonical = runCatching { direct.canonicalFile }.getOrNull()
        if (directCanonical != null &&
            directCanonical.path.startsWith(rootCanonical.path) &&
            directCanonical.isFile
        ) {
            return directCanonical
        }
        val targetName = java.io.File(relativePath).name
        return rootCanonical.walkTopDown()
            .onEnter { dir -> dir.name != ".git" && dir.name != "99_Private" }
            .firstOrNull { it.isFile && it.name.equals(targetName, ignoreCase = true) }
    }
}
