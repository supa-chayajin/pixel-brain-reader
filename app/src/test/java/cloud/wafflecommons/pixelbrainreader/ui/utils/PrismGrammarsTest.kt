package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.prism4j.Prism4j
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for the vendored Prism4j grammars behind [PrismHelper]. Each
 * sample must tokenize into more than one node with at least one real token —
 * a grammar that silently matches nothing would leave code monochrome again
 * without failing anywhere else.
 */
class PrismGrammarsTest {

    private val prism = PrismHelper.prism

    private val samples = mapOf(
        "kotlin" to """
            // greet the user
            fun main(args: Array<String>) {
                val name = "world"
                println("hello ${'$'}name")
            }
        """.trimIndent(),
        "java" to """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("hello"); // greet
                }
            }
        """.trimIndent(),
        "javascript" to """
            // greet
            const name = "world";
            function greet() { return `hi ${'$'}{name}`; }
        """.trimIndent(),
        "json" to """{"name": "pixel", "count": 3, "ok": true}""",
        "yaml" to """
            date: 2026-07-20
            tags:
              - daily
              - "life os"
        """.trimIndent(),
        "bash" to """
            #!/bin/bash
            # rebuild the app
            NAME="pixel"
            if [ -f build.gradle.kts ]; then
                echo "hello ${'$'}NAME"
            fi
        """.trimIndent(),
        "python" to """
            # greet
            def main(name="world"):
                print(f"hello {name}")
        """.trimIndent(),
        "markup" to """<a href="https://example.com">link</a>""",
        "markdown" to """
            # Title

            Some **bold** text and a [link](https://example.com).
        """.trimIndent(),
        "csharp" to """
            #region Setup
            [Serializable]
            public sealed record User(string Name) {
                public static decimal Price = 9.99m; // price
                var path = @"C:\temp";
            }
        """.trimIndent(),
        "php" to """
            <?php
            namespace App\Http;
            # a shell-style comment
            function greet(${'$'}name) { return "hi " . ${'$'}name ?? null; }
        """.trimIndent(),
        "toml" to """
            # app settings
            [server.http]
            port = 8_080
            "quoted.key" = 'literal'
            started = 2026-10-18T09:00:00Z
            enabled = true
        """.trimIndent()
    )

    /** Tokens of [lang] in [sample], by type, flattened. */
    private fun tokenTypes(lang: String, sample: String): Set<String> {
        val types = mutableSetOf<String>()
        fun walk(nodes: List<Prism4j.Node>) {
            nodes.filterIsInstance<Prism4j.Syntax>().forEach { syntax ->
                types += syntax.type()
                walk(syntax.children())
            }
        }
        walk(prism.tokenize(sample, prism.grammar(lang)!!))
        return types
    }

    @Test
    fun `the hand-written grammars find their own constructs`() {
        assertTrue(tokenTypes("csharp", samples.getValue("csharp")).containsAll(listOf("preprocessor", "attribute", "keyword", "string", "number", "comment")))
        assertTrue(tokenTypes("php", samples.getValue("php")).containsAll(listOf("delimiter", "package", "shell-comment", "variable", "keyword", "boolean")))
        assertTrue(tokenTypes("toml", samples.getValue("toml")).containsAll(listOf("comment", "table", "key", "string", "date", "number", "boolean")))
    }

    @Test
    fun `all bundled languages resolve to a grammar`() {
        val locator = PrismHelper.ManualGrammarLocator()
        for (lang in locator.languages()) {
            assertNotNull("grammar '$lang' must resolve", prism.grammar(lang))
        }
    }

    @Test
    fun `aliases resolve to their canonical grammar`() {
        val aliases = mapOf(
            "kt" to "kotlin", "sh" to "bash", "shell" to "bash", "yml" to "yaml",
            "md" to "markdown", "py" to "python", "js" to "javascript", "html" to "markup",
            "cs" to "csharp", "c#" to "csharp"
        )
        for ((alias, canonical) in aliases) {
            val grammar = prism.grammar(alias)
            assertNotNull("alias '$alias' must resolve", grammar)
            assertEquals("alias '$alias' must be the $canonical grammar", canonical, grammar!!.name())
        }
    }

    @Test
    fun `unknown language returns null and does not throw`() {
        assertNull(prism.grammar("definitely-not-a-language"))
    }

    @Test
    fun `every sample tokenizes into real tokens`() {
        for ((lang, sample) in samples) {
            val grammar = prism.grammar(lang)
            assertNotNull("grammar '$lang' must resolve", grammar)
            val nodes = prism.tokenize(sample, grammar!!)
            assertTrue("'$lang' must produce multiple nodes, got ${nodes.size}", nodes.size > 1)
            assertTrue(
                "'$lang' must produce at least one syntax token",
                nodes.any { it is Prism4j.Syntax }
            )
        }
    }
}
