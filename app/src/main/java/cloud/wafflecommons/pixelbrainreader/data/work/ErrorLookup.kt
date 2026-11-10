package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage

/** C8's pure steps: merging the past occurrences, the French prompt, where a bug note belongs. */
object ErrorLookup {

    const val MAX_OCCURRENCES = 5

    /** The golden set lists errors as questions: never a past occurrence of one. */
    const val GOLDEN_SET_PATH = "99_System/rag_golden_set.md"

    /** One search hit: a note and the words around the match. */
    data class Hit(val path: String, val excerpt: String)

    /** A note that met this error before. [viaWords]: found by the error's own words, not by meaning. */
    data class Occurrence(val path: String, val excerpt: String, val viaWords: Boolean, val isRunbook: Boolean = false)

    /**
     * Full-text lists first (one per query, best first) fused by reciprocal rank — an error's own
     * words are the strongest evidence — then the vector hits; each note once, at most [max].
     */
    fun merge(textLists: List<List<Hit>>, vectorHits: List<Hit>, max: Int = MAX_OCCURRENCES): List<Occurrence> {
        val fused = LinkedHashMap<String, Pair<Double, Hit>>()
        textLists.forEach { list ->
            list.forEachIndexed { rank, hit ->
                if (hit.path == GOLDEN_SET_PATH) return@forEachIndexed
                val score = 1.0 / (RRF_K + rank)
                val previous = fused[hit.path]
                fused[hit.path] = (previous?.first ?: 0.0) + score to (previous?.second ?: hit)
            }
        }
        val seen = HashSet<String>()
        val byWords = fused.values.sortedByDescending { it.first }.map { it.second }
            .filter { seen.add(it.path) }
            .map { Occurrence(it.path, it.excerpt.trim(), viaWords = true) }
        val byMeaning = vectorHits
            .filter { it.path != GOLDEN_SET_PATH && seen.add(it.path) }
            .map { Occurrence(it.path, it.excerpt.trim(), viaWords = false) }
        return (byWords + byMeaning).take(max)
    }

    /**
     * The prompt for Gemini Nano, in French like every prompt (settled decision #5) and within its
     * ~4,000-token window: the error's head with the middle of a long trace cut, then up to three
     * excerpts of the notes that met it. [generalKnowledge]: no note matched and the owner asked
     * anyway — the answer must say it comes from general knowledge.
     */
    fun prompt(trace: ErrorTrace, raw: String, occurrences: List<Occurrence>, generalKnowledge: Boolean): String = buildString {
        if (generalKnowledge) {
            append("Tu aides un développeur à comprendre une erreur. Ses notes n'en parlent pas : réponds d'après tes connaissances générales et dis-le.\n\n")
        } else {
            append("Tu aides un développeur à comprendre une erreur à partir de ses propres notes.\n\n")
        }
        append("Erreur (").append(trace.ecosystem.label).append(") :\n")
        append(cutMiddle(raw.trim(), ERROR_BUDGET)).append("\n\n")
        if (!generalKnowledge && occurrences.isNotEmpty()) {
            append("Ce que ses notes en disent :\n")
            occurrences.take(3).forEach { o ->
                append("[Note : ").append(o.path.substringAfterLast('/').removeSuffix(".md")).append("] ")
                append(o.excerpt.replace('\n', ' ').take(EXCERPT_BUDGET)).append('\n')
            }
            append('\n')
            append("Explique en trois phrases au plus : la cause probable, la note qui le dit, et la prochaine chose à vérifier. N'invente rien que ces notes ne disent pas.\n")
        } else {
            append("Explique en trois phrases au plus : ce que signifie l'erreur, sa cause la plus fréquente, et la première chose à vérifier.\n")
        }
        append(AiLanguage.DIRECTIVE)
    }

    /** [text] within [max] characters: its head and tail kept, the middle replaced by a marker. */
    fun cutMiddle(text: String, max: Int): String {
        if (text.length <= max) return text
        val keep = (max - CUT_MARKER.length) / 2
        return text.take(keep) + CUT_MARKER + text.takeLast(keep)
    }

    /**
     * The project whose name the error spells (a path, a package), for a bug note when no past
     * occurrence settles it: one project only, by a distinctive word of its folder name (five letters
     * or more, common words aside). Two or more candidates — or none — means the inbox.
     */
    fun projectNamed(roots: Collection<String>, raw: String): String? {
        val text = raw.lowercase()
        val named = roots.filter { root ->
            root.substringAfterLast('/').replace(Regex("""^[0-9]+_"""), "").split('_', '-', ' ')
                .map { it.lowercase() }
                .any { word -> word.length >= 5 && word !in GENERIC_NAME_WORDS && text.contains(word) }
        }
        return named.singleOrNull()
    }

    private const val RRF_K = 60
    private const val ERROR_BUDGET = 1_500
    private const val EXCERPT_BUDGET = 450
    private const val CUT_MARKER = "\n[…]\n"
    private val GENERIC_NAME_WORDS = setOf("project", "projects", "framework", "gateway", "tools", "notes", "mobile", "server", "client", "backend", "frontend")
}
