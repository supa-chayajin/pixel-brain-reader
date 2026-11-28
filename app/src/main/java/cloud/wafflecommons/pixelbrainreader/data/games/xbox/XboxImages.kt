package cloud.wafflecommons.pixelbrainreader.data.games.xbox

/**
 * Xbox's picture addresses made https (13.4, pure). titlehub sends most of them as plain http on
 * Microsoft's two image hosts; both serve the same pictures over https (the second under another
 * name). Never http: any other http address, or one without a scheme, is no picture.
 */
object XboxImages {

    /** Scheme-and-host prefixes, trailing `/` included, and their https form; matched ignoring case. */
    private val REWRITES = listOf(
        "http://store-images.s-microsoft.com/" to "https://store-images.s-microsoft.com/",
        "http://images-eds.xboxlive.com/" to "https://images-eds-ssl.xboxlive.com/",
        "https://images-eds.xboxlive.com/" to "https://images-eds-ssl.xboxlive.com/"
    )

    /**
     * [raw] trimmed and made https: the two hosts' http addresses rewritten (the rest of the address
     * kept byte for byte), any other https address unchanged (the picture loader's host list decides
     * later), anything else null.
     */
    fun https(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        REWRITES.forEach { (from, to) ->
            if (text.regionMatches(0, from, 0, from.length, ignoreCase = true)) return to + text.substring(from.length)
        }
        return text.takeIf { it.startsWith("https://", ignoreCase = true) }
    }
}
