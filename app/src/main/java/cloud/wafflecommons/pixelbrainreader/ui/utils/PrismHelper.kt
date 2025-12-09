package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j

object PrismHelper {
    
    // Manual Grammar Bundle
    // In a real scenario, we would use an annotation processor or manual definition of grammars.
    // For now, we provide a safe fallback ensuring no crashes if languages are missing.
    class ManualGrammarLocator : GrammarLocator {
        override fun grammar(prism4j: Prism4j, name: String): Prism4j.Grammar? {
            // Stub: Return null for now. 
            // In future, we can construct Prism4j.Grammar manually or use bundled ones.
            return null
        }

        override fun languages(): Set<String> {
            return emptySet()
        }
    }

    val prism: Prism4j by lazy {
        Prism4j(ManualGrammarLocator())
    }
}
