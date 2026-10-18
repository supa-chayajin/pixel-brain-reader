package cloud.wafflecommons.pixelbrainreader.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's java.util.regex runs on ICU, not on the JVM's engine — and unit tests run on the JVM.
 * A pattern the JVM accepts can still throw PatternSyntaxException on the device: 10.2.0 shipped
 * `(?U)` in VoiceCompiler's companion object and crashed on every launch while every unit test
 * passed. This guard rejects the JVM-only regex features in app sources:
 *  - the `U` inline flag / UNICODE_CHARACTER_CLASS (use explicit `(?<![\p{L}\p{N}])` lookarounds:
 *    ICU's `\b`/`\w` are already Unicode-aware, the JVM's are not, so lookarounds agree on both);
 *  - Java-only property classes such as `\p{javaLowerCase}`.
 */
class AndroidRegexGuardTest {

    private val sourceRoot: File = listOf("src/main/java", "app/src/main/java")
        .map { File(it) }
        .first { it.isDirectory }

    private val jvmOnly = listOf(
        Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]""") to "inline (?U) flag (UNICODE_CHARACTER_CLASS) — ICU rejects it",
        Regex("""UNICODE_CHARACTER_CLASS""") to "UNICODE_CHARACTER_CLASS — unsupported on Android",
        Regex("""\\\\p\{java""") to "\\p{java…} property class — JVM-only"
    )

    @Test
    fun `no JVM-only regex features in app sources`() {
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filterNot { it.invariantSeparatorsPath.contains("/io/noties/prism4j/") } // vendored grammars, not ours
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    val code = line.trim()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@flatMap emptyList()
                    jvmOnly.filter { (pattern, _) -> pattern.containsMatchIn(line) }
                        .map { (_, why) -> "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}  $why" }
                }
            }
            .toList()
        assertTrue(
            "Regex features the device rejects (Android regex = ICU):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
