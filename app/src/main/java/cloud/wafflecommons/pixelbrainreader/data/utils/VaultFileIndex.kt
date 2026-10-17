package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * The vault's attachments (every file that isn't a markdown note), resolvable the way Obsidian
 * resolves `![[embed]]` targets: by vault-relative path first, then by bare file name anywhere in
 * the vault — so `![[image.png]]` finds `assets/2024/image.png`.
 *
 * Built in memory from the Room file index (never by walking the filesystem), so rendering a note
 * never touches the disk to find its images.
 */
class VaultFileIndex(paths: Collection<String>) {

    private val byPath: Map<String, String> = paths.associateBy { it.lowercase() }

    /** Several attachments can share a name: the shallowest one wins (closest to the vault root). */
    private val byName: Map<String, String> = paths
        .groupBy { it.substringAfterLast('/').lowercase() }
        .mapValues { (_, candidates) -> candidates.minWith(compareBy({ depth(it) }, { it })) }

    val size: Int get() = byPath.size

    /** Vault-relative path of the attachment [target] points to (`|size`/`#anchor` ignored), or null. */
    fun resolve(target: String): String? {
        val key = target.substringBefore('|').substringBefore('#').trim()
            .removePrefix("./").removePrefix("/").lowercase()
        if (key.isEmpty()) return null
        return byPath[key] ?: byName[key.substringAfterLast('/')]
    }

    companion object {
        val EMPTY = VaultFileIndex(emptyList())

        /** True for files an embed can point to: not notes, not encrypted, not private or hidden. */
        fun isAttachment(path: String): Boolean {
            val lower = path.lowercase()
            return !lower.endsWith(".md") &&
                !lower.endsWith(".enc") &&
                !lower.startsWith("99_private/") &&
                path.split('/').none { it.startsWith(".") }
        }

        private fun depth(path: String) = path.count { it == '/' }
    }
}
