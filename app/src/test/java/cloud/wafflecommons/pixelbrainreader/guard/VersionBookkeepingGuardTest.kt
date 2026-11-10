package cloud.wafflecommons.pixelbrainreader.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settled decision #10: every version ships its CHANGELOG section. A bump without its section (or a
 * section added under an older version) fails the build instead of reaching a release.
 */
class VersionBookkeepingGuardTest {

    // Unit tests run from the module directory; the repository root works too.
    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull { it.isFile } ?: error("none of ${paths.toList()} found from ${File(".").absolutePath}")

    private val buildFile = firstExisting("build.gradle.kts", "app/build.gradle.kts")
    private val changelog = firstExisting("../CHANGELOG.md", "CHANGELOG.md")

    private val versionName: String =
        Regex("""versionName\s*=\s*"([^"]+)"""").find(buildFile.readText())?.groupValues?.get(1)
            ?: error("no versionName in ${buildFile.path}")

    private val sections: List<String> =
        Regex("""^## \[(\d+\.\d+\.\d+)]""", RegexOption.MULTILINE).findAll(changelog.readText()).map { it.groupValues[1] }.toList()

    @Test
    fun `the app's version has its CHANGELOG section`() {
        assertTrue("CHANGELOG.md has no [$versionName] section", versionName in sections)
    }

    @Test
    fun `the newest CHANGELOG section is the app's version`() {
        assertEquals(versionName, sections.firstOrNull())
    }
}
