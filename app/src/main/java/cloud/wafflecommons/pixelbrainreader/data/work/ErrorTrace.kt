package cloud.wafflecommons.pixelbrainreader.data.work

/**
 * An error as pasted or shared (C8): what it says, where it came from, and the words worth
 * searching. Pure and conservative: ordinary text is not an error, so the share sheet offers
 * "Explain this error" only for a stack trace or an error line it recognises.
 *
 * Character classes are spelled out (`[A-Za-z0-9_]`, not `\w`): Android's regex engine is ICU,
 * whose `\w` is Unicode-aware where the JVM's (the unit tests') is not.
 */
data class ErrorTrace(
    val ecosystem: Ecosystem,
    /** The error line itself, as written. */
    val headline: String,
    /** The error or exception type when one is named, simple name ("TypeError", "IllegalStateException"). */
    val type: String?,
    /** What follows the type on the error line. */
    val message: String,
    /** The headline with what changes between occurrences replaced: two logs of one failure give one key. */
    val key: String,
    /** The first frames, top first, as written (at most [MAX_FRAMES]). */
    val frames: List<String>,
    /** Codes searched as they are: SQLSTATE[23000], ORA-00942, ECONNREFUSED, HTTP 503… */
    val codes: List<String>
) {
    enum class Ecosystem(val label: String) {
        JVM("Java/Kotlin"), PYTHON("Python"), PHP("PHP"), JAVASCRIPT("JavaScript"), DOTNET(".NET"), GO("Go"), MESSAGE("Error message")
    }

    /**
     * Short full-text queries, best first. The index ANDs every word of a query, so each one stays
     * at a few words: the type with the message's most distinctive words, each code, then the
     * distinctive words alone.
     */
    fun queries(): List<String> {
        val words = distinctiveWords(message)
        val queries = mutableListOf<String>()
        // A common type ("TypeError") only counts beside a distinctive word.
        if (type != null && (type !in GENERIC_TYPES || words.isNotEmpty())) queries += (listOf(type) + words.take(2)).joinToString(" ")
        codes.forEach { queries += it }
        if (words.size >= 2) queries += words.take(3).joinToString(" ")
        if (words.size == 1 && type == null) queries += words.first()
        return queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    companion object {
        const val MAX_FRAMES = 8

        private val JVM_FRAME = Regex("""^\s*at\s+[A-Za-z0-9_$.<>/\-]+\([^)]*\)\s*$""")
        private val DOTNET_FRAME = Regex("""^\s*at\s+[A-Za-z0-9_$.<>`\[\],]+\(.*\)(\s+in\s+.+:line\s+[0-9]+)?\s*$""")
        private val JS_FRAME = Regex("""^\s*at\s+.*:[0-9]+:[0-9]+\)?\s*$""")
        private val PYTHON_FRAME = Regex("""^\s*File\s+"[^"]+",\s+line\s+[0-9]+""")
        private val PHP_FRAME = Regex("""^#[0-9]+\s+\S.*$""")
        private val GO_FRAME = Regex("""^\s*/\S+\.go:[0-9]+""")

        private val PY_TRACEBACK = Regex("""^Traceback \(most recent call last\):""", RegexOption.MULTILINE)
        private val GO_PANIC = Regex("""^panic:\s*(.+)$""", RegexOption.MULTILINE)
        private val PHP_FATAL = Regex("""^(?:PHP\s+)?(?:Fatal error|Parse error|Warning|Notice):\s+(?:Uncaught\s+)?([A-Za-z_\\][A-Za-z0-9_\\]*)(?::\s*(.*?))?(?:\s+in\s+\S+:[0-9]+)?\s*$""")

        /** "TypeError: …", "java.lang.IllegalStateException: …", "Caused by: …", "Unhandled exception. System.X: …". */
        private val HEADLINE = Regex(
            """^(?:Exception in thread "[^"]*"\s+|Caused by:\s+|Unhandled exception\.\s+|Uncaught\s+)?([A-Za-z_$\\][A-Za-z0-9_$.\\]*(?:Error|Exception|Throwable|Exit|Interrupt))(?::\s*(.*))?$"""
        )

        private val CODE_PATTERNS = listOf(
            Regex("""SQLSTATE\[[0-9A-Z]{5}]"""),
            Regex("""ORA-[0-9]{5}"""),
            Regex("""(?<![A-Za-z0-9_])E(?:CONNREFUSED|CONNRESET|NOENT|ACCES|TIMEDOUT|ADDRINUSE|PIPE|PERM|HOSTUNREACH)(?![A-Za-z0-9_])"""),
            Regex("""(?<![A-Za-z0-9_])(?:OOMKilled|CrashLoopBackOff|ImagePullBackOff|Segmentation fault)(?![A-Za-z0-9_])"""),
            Regex("""(?<![0-9])5[0-9]{2}\s+(?:Internal Server Error|Bad Gateway|Service Unavailable|Gateway Timeout)""")
        )

        /** Types too common to search alone: they need a distinctive word beside them. */
        private val GENERIC_TYPES = setOf(
            "Error", "Exception", "TypeError", "ValueError", "RuntimeError", "RuntimeException", "IllegalStateException",
            "IllegalArgumentException", "NullPointerException", "KeyError", "IndexError", "AttributeError"
        )

        private val STOP_WORDS = setOf(
            "argument", "arguments", "must", "should", "cannot", "could", "would", "given", "called", "expected", "found",
            "value", "values", "type", "null", "none", "true", "false", "with", "from", "this", "that", "line", "file",
            "named", "module", "object", "string", "integer", "number", "greater", "than", "less", "equal", "instance",
            "uncaught", "error", "exception", "failed", "invalid", "unknown", "while", "when", "after", "before"
        )

        private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val HEX = Regex("""0x[0-9a-fA-F]+""")
        private val TIMESTAMP = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]+)?)?Z?""")
        private val PATH = Regex("""(?:[A-Za-z]:)?(?:[/\\][A-Za-z0-9_.@\-]+){2,}""")
        private val QUOTED = Regex("""'[^']*'|"[^"]*"""")
        private val LONG_NUMBER = Regex("""(?<![A-Za-z0-9_])[0-9]{3,}(?![A-Za-z0-9_])""")
        private val SPACES = Regex("""\s+""")
        private val WORD = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        /** The error in [text], or null when it does not read as one. */
        fun parse(text: String): ErrorTrace? {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val codes = CODE_PATTERNS.flatMap { p -> p.findAll(text).map { it.value }.toList() }.distinct()

            val python = PY_TRACEBACK.containsMatchIn(text)
            val goPanic = GO_PANIC.find(text)
            val counts = mapOf(
                Ecosystem.PYTHON to lines.count { PYTHON_FRAME.containsMatchIn(it) },
                Ecosystem.PHP to lines.count { PHP_FRAME.matches(it) },
                Ecosystem.GO to lines.count { GO_FRAME.containsMatchIn(it) },
                Ecosystem.DOTNET to lines.count { it.contains(":line ") && DOTNET_FRAME.matches(it) },
                Ecosystem.JAVASCRIPT to lines.count { JS_FRAME.matches(it) && !JVM_FRAME.matches(it) },
                Ecosystem.JVM to lines.count { JVM_FRAME.matches(it) && !it.contains(":line ") }
            )

            // The error line: Python reports it last, every other trace first.
            val candidates = if (python) lines.asReversed() else lines
            val headlineMatch = candidates.firstNotNullOfOrNull { line ->
                PHP_FATAL.matchEntire(line.trim())?.let { Triple(line.trim(), it.groupValues[1], it.groupValues[2]) }
                    ?: HEADLINE.matchEntire(line.trim())?.let { Triple(line.trim(), it.groupValues[1], it.groupValues[2]) }
            }
            val frameEcosystem = counts.filterValues { it > 0 }.maxByOrNull { it.value }?.key

            val (headline, type, message) = when {
                goPanic != null -> Triple(goPanic.value.trim(), "panic", goPanic.groupValues[1].trim())
                headlineMatch != null -> Triple(headlineMatch.first, headlineMatch.second.substringAfterLast('.').substringAfterLast('\\'), headlineMatch.third.trim())
                codes.isNotEmpty() -> {
                    val line = lines.first { l -> codes.any { it in l } }.trim()
                    Triple(line, null, line)
                }
                else -> return null
            }
            // A lone "SomethingError: x" in prose is too weak alone: it needs a frame, a code, or a short paste.
            if (goPanic == null && frameEcosystem == null && codes.isEmpty() && !python && lines.size > 3) return null

            val ecosystem = when {
                python -> Ecosystem.PYTHON
                goPanic != null -> Ecosystem.GO
                frameEcosystem != null -> frameEcosystem
                headline.contains("Fatal error") || headline.contains("\\") -> Ecosystem.PHP
                headlineMatch?.second?.let { it.startsWith("java.") || it.startsWith("kotlin.") || it.startsWith("android.") } == true -> Ecosystem.JVM
                headlineMatch?.second?.startsWith("System.") == true -> Ecosystem.DOTNET
                else -> Ecosystem.MESSAGE
            }
            val frames = lines.filter { line ->
                JVM_FRAME.matches(line) || DOTNET_FRAME.matches(line) || JS_FRAME.matches(line) ||
                    PYTHON_FRAME.containsMatchIn(line) || PHP_FRAME.matches(line) || GO_FRAME.containsMatchIn(line)
            }.take(MAX_FRAMES).map { it.trim() }
            return ErrorTrace(ecosystem, headline, type, message, keyOf(headline), frames, codes)
        }

        /** [line] with what changes between two occurrences of one failure replaced by placeholders. */
        fun keyOf(line: String): String = line
            .replace(UUID, "<uuid>")
            .replace(HEX, "<hex>")
            .replace(TIMESTAMP, "<time>")
            .replace(PATH) { m -> m.value.substringAfterLast('/').substringAfterLast('\\') }
            .replace(QUOTED, "'…'")
            .replace(LONG_NUMBER, "<n>")
            .replace(SPACES, " ")
            .trim()

        /**
         * The message's words worth searching, most distinctive first: quoted values (a module, a
         * key), identifiers (`json_decode`, `Envelope`, `$attempts`), then long words; common words
         * and numbers left out.
         */
        internal fun distinctiveWords(message: String): List<String> {
            val quoted = QUOTED.findAll(message).map { it.value.trim('\'', '"') }.filter { WORD.matches(it) && it.length >= 3 }.toList()
            val words = WORD.findAll(message).map { it.value }.toList()
            val identifiers = words.filter { w -> w.contains('_') || (w.any { it.isUpperCase() } && w.drop(1).any { it.isLowerCase() } && w.length >= 4) }
            val long = words.filter { it.length >= 7 }
            return (quoted + identifiers + long)
                .filter { it.lowercase() !in STOP_WORDS }
                .distinctBy { it.lowercase() }
        }
    }
}
