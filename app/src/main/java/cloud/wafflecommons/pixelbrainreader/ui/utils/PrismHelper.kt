package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.languages.Prism_bash
import io.noties.prism4j.languages.Prism_clike
import io.noties.prism4j.languages.Prism_java
import io.noties.prism4j.languages.Prism_javascript
import io.noties.prism4j.languages.Prism_json
import io.noties.prism4j.languages.Prism_kotlin
import io.noties.prism4j.languages.Prism_markdown
import io.noties.prism4j.languages.Prism_markup
import io.noties.prism4j.languages.Prism_python
import io.noties.prism4j.languages.Prism_yaml
import java.util.concurrent.ConcurrentHashMap

object PrismHelper {

    /**
     * Direct-dispatch locator over the vendored grammars (see
     * io/noties/prism4j/languages/README.md). No annotation processor and no
     * Class.forName, so R8 needs no extra keep rules. Unknown languages return
     * null and Markwon renders the fence as plain text — the pre-v10 behavior.
     */
    class ManualGrammarLocator : GrammarLocator {

        // Not computeIfAbsent: kotlin/markdown grammars recursively fetch their
        // base grammar (clike/markup) through the same locator, and a recursive
        // computeIfAbsent on ConcurrentHashMap throws. A lost race just builds
        // a grammar twice, which is harmless.
        private val cache = ConcurrentHashMap<String, Prism4j.Grammar>()

        override fun grammar(prism4j: Prism4j, name: String): Prism4j.Grammar? {
            val key = canonicalName(name)
            cache[key]?.let { return it }
            val created = createGrammar(prism4j, key) ?: return null
            cache.putIfAbsent(key, created)
            return cache[key]
        }

        override fun languages(): Set<String> = setOf(
            "clike", "kotlin", "java", "javascript", "json", "yaml",
            "bash", "python", "markup", "markdown"
        )

        private fun createGrammar(prism4j: Prism4j, name: String): Prism4j.Grammar? =
            when (name) {
                "clike" -> Prism_clike.create(prism4j)
                "kotlin" -> Prism_kotlin.create(prism4j)
                "java" -> Prism_java.create(prism4j)
                "javascript" -> Prism_javascript.create(prism4j)
                "json" -> Prism_json.create(prism4j)
                "yaml" -> Prism_yaml.create(prism4j)
                "bash" -> Prism_bash.create(prism4j)
                "python" -> Prism_python.create(prism4j)
                "markup" -> Prism_markup.create(prism4j)
                "markdown" -> Prism_markdown.create(prism4j)
                else -> null
            }

        private fun canonicalName(name: String): String = when (name.lowercase()) {
            "kt", "kts" -> "kotlin"
            "js", "jsx", "ts" -> "javascript"
            "sh", "shell", "zsh" -> "bash"
            "yml" -> "yaml"
            "md" -> "markdown"
            "py" -> "python"
            "html", "xml", "svg" -> "markup"
            else -> name.lowercase()
        }
    }

    val prism: Prism4j by lazy {
        Prism4j(ManualGrammarLocator())
    }
}
