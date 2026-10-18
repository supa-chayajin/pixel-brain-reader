package cloud.wafflecommons.pixelbrainreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.ui.utils.PrismHelper
import io.noties.prism4j.Prism4j
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hand-written Prism4j grammars compiled and run on ANDROID's regex engine (ICU): the JVM
 * grammar tests can't catch a pattern ICU rejects. Run on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class GrammarsDeviceTest {

    @Test
    fun handWrittenGrammarsTokenizeOnIcu() {
        val samples = mapOf(
            "csharp" to "#region A\n[Serializable]\npublic record User(string Name) { var p = @\"C:\\\\x\"; }",
            "php" to "<?php\nnamespace App\\Http;\n# comment\nfunction f(\$a) { return \$a ?? null; }",
            "toml" to "# c\n[server]\nport = 8_080\nstarted = 2026-10-18T09:00:00Z",
            "bash" to "#!/bin/bash\nif [ -f x ]; then echo \"\$HOME\"; fi"
        )
        val prism = PrismHelper.prism
        samples.forEach { (lang, code) ->
            val nodes = prism.tokenize(code, prism.grammar(lang)!!)
            assertTrue("$lang must tokenize on ICU", nodes.any { it is Prism4j.Syntax })
        }
    }
}
