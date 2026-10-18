package cloud.wafflecommons.pixelbrainreader.data.web

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A manual check on real pages, never committed with them (they are copyrighted): put `page*.html`
 * and an `index.tsv` (name, …, url) in `app/build/clip-pages/`, run this test and read the note
 * (`.md`) written next to each page. Skipped when the folder is absent (always, in CI).
 */
class WebClipperRealPagesTest {

    @Test
    fun clipSavedPages() {
        val dir = File("build/clip-pages").takeIf { it.isDirectory }
        assumeTrue(dir != null && dir.isDirectory)
        val urls = File(dir, "index.tsv").readLines().associate { line ->
            val cols = line.split('\t')
            cols[0] to cols.last()
        }
        dir!!.listFiles { f -> f.name.matches(Regex("page\\d+\\.html")) }!!.sortedBy { it.name.length * 100 + it.name.hashCode() % 100 }.forEach { page ->
            val url = urls[page.nameWithoutExtension] ?: return@forEach
            val clip = WebClipper.clip(page.readText(), url)
            File(dir, page.nameWithoutExtension + ".md").writeText(WebClipNote.render(clip, clip.title, "00_Inbox", null, null, LocalDate.now()))
            val blocked = if (WebClipper.looksBlocked(page.readText())) "BLOCKED" else "-"
            println("${page.name}\t$blocked\t${clip.quality}\t${clip.words}\t${clip.title}")
        }
    }
}
