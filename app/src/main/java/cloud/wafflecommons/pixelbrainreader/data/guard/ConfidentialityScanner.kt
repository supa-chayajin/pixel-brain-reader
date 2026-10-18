package cloud.wafflecommons.pixelbrainreader.data.guard

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Finds what a commit would ADD that must not reach the git remote: protected names (the user's
 * deny-list) and secrets (tokens, keys, passwords). Pure: the commit path hands it staged texts.
 *
 * Only additions count. Each file is scanned before and after the change, and an occurrence is
 * reported only when the change adds one. What HEAD already holds has already been pushed;
 * flagging it again on every edit would hold the vault forever.
 */
object ConfidentialityScanner {

    internal data class Hit(
        val key: String,
        val label: String,
        val kind: FindingKind,
        val start: Int,
        val end: Int,
        val masked: Boolean = false
    )

    /** What holds a commit of [changes]: every addition found, minus what the allow-list lets through. */
    fun review(changes: List<StagedText>, settings: GuardSettings): List<GuardFinding> =
        scan(changes, settings).filterNot { finding -> settings.allowed.any { it.covers(finding) } }

    fun scan(changes: List<StagedText>, settings: GuardSettings): List<GuardFinding> {
        val terms = settings.terms.mapNotNull(TextFold::term).distinct()
        if (terms.isEmpty() && !settings.detectSecrets) return emptyList()
        return changes.flatMap { scanChange(it, terms, settings.detectSecrets) }
    }

    /** The [findings] that still hold after a settings change (a term removed, an exception added…). */
    fun stillHeld(findings: List<GuardFinding>, settings: GuardSettings): List<GuardFinding> {
        val terms = settings.terms.mapNotNull(TextFold::term).toSet()
        return findings.filter { finding ->
            val ruleActive = when (finding.kind) {
                FindingKind.NAME -> finding.ruleKey.removePrefix("term:") in terms
                FindingKind.SECRET -> settings.detectSecrets
            }
            ruleActive && settings.allowed.none { it.covers(finding) }
        }
    }

    /** True when [text] names one of [terms] (raw, as the user typed them). */
    fun mentionsAny(text: String, terms: List<String>): Boolean {
        val folded = terms.mapNotNull(TextFold::term).distinct()
        return folded.isNotEmpty() && termHits(text, folded).isNotEmpty()
    }

    private fun scanChange(change: StagedText, terms: List<String>, detectSecrets: Boolean): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()
        if (change.isNewPath && terms.isNotEmpty()) {
            val before = change.oldPath?.let { termHits(it, terms) }.orEmpty()
            added(before, termHits(change.path, terms), LineIndex(change.path), oldLines = emptySet()).forEach { hit ->
                findings += GuardFinding(change.path, hit.kind, hit.key, hit.label, line = 0, excerpt = change.path)
            }
        }
        val newText = change.newText ?: return findings
        val oldText = change.oldText.orEmpty()
        val after = hits(newText, terms, detectSecrets)
        if (after.isEmpty()) return findings
        val lines = LineIndex(newText)
        val oldLines = oldText.lineSequence().map { it.trim() }.toHashSet()
        added(hits(oldText, terms, detectSecrets), after, lines, oldLines).forEach { hit ->
            findings += GuardFinding(change.path, hit.kind, hit.key, hit.label, lines.lineOf(hit.start), excerpt(newText, hit, lines))
        }
        return findings
    }

    /**
     * The occurrences [after] adds to [before], per key. When a key gains occurrences, the ones on
     * lines that didn't exist before are reported first: they are the new ones.
     */
    private fun added(before: List<Hit>, after: List<Hit>, lines: LineIndex, oldLines: Set<String>): List<Hit> {
        val counts = before.groupingBy { it.key }.eachCount()
        return after.groupBy { it.key }
            .flatMap { (key, hits) ->
                val extra = hits.size - (counts[key] ?: 0)
                if (extra <= 0) return@flatMap emptyList()
                val (fresh, known) = hits.partition { lines.text(it.start).trim() !in oldLines }
                (fresh + known).take(extra)
            }
            .sortedBy { it.start }
    }

    private fun hits(text: String, terms: List<String>, detectSecrets: Boolean): List<Hit> {
        if (text.isEmpty()) return emptyList()
        val found = if (terms.isEmpty()) mutableListOf() else termHits(text, terms).toMutableList()
        if (detectSecrets) found += SecretPatterns.find(text)
        return found
    }

    private fun termHits(text: String, terms: List<String>): List<Hit> {
        val folded = TextFold.fold(text)
        val found = mutableListOf<Hit>()
        for (term in terms) {
            var from = 0
            while (true) {
                val at = folded.indexOf(term, from)
                if (at < 0) break
                val end = at + term.length
                if (TextFold.isBoundary(folded, at - 1) && TextFold.isBoundary(folded, end)) {
                    found += Hit("term:$term", "Protected name", FindingKind.NAME, at, end)
                }
                from = at + 1
            }
        }
        return found
    }

    /** The hit's line, cut to [CONTEXT] characters on each side, with a secret masked. */
    private fun excerpt(text: String, hit: Hit, lines: LineIndex): String {
        val lineStart = lines.startOf(hit.start)
        val lineEnd = maxOf(lines.endOf(hit.start), hit.end)
        val from = maxOf(lineStart, hit.start - CONTEXT)
        val to = minOf(lineEnd, hit.end + CONTEXT)
        val value = text.substring(hit.start, hit.end)
        return buildString {
            if (from > lineStart) append('…')
            append(text, from, hit.start)
            append(if (hit.masked) mask(value) else value)
            append(text, hit.end, to)
            if (to < lineEnd) append('…')
        }.trim()
    }

    /** Enough to recognise a secret, never enough to use it. */
    internal fun mask(secret: String): String =
        secret.take((secret.length / 5).coerceAtMost(4)) + "…(${secret.length} chars)"

    internal fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private const val CONTEXT = 40

    /** Line lookups by character offset. */
    private class LineIndex(private val text: String) {
        private val starts: IntArray = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

        /** 1-based line number of [offset]. */
        fun lineOf(offset: Int): Int {
            val i = starts.binarySearch(offset)
            return if (i >= 0) i + 1 else -i - 1
        }

        fun startOf(offset: Int): Int = starts[lineOf(offset) - 1]

        fun endOf(offset: Int): Int {
            val next = lineOf(offset)
            return if (next < starts.size) starts[next] - 1 else text.length
        }

        fun text(offset: Int): String = text.substring(startOf(offset), endOf(offset))
    }
}

/** Case- and accent-insensitive matching that keeps offsets: one char in, one char out. */
internal object TextFold {

    private const val MIN_TERM_CHARS = 2

    fun fold(text: String): String {
        val cache = HashMap<Char, Char>()
        return String(CharArray(text.length) { i -> foldChar(text[i], cache) })
    }

    /** A protected term as it is matched: folded and single-spaced; null when it is too short to mean anything. */
    fun term(raw: String): String? {
        val folded = fold(raw.trim()).split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        return folded.takeIf { term -> term.count { it.isLetterOrDigit() } >= MIN_TERM_CHARS }
    }

    fun isBoundary(folded: String, index: Int): Boolean =
        index < 0 || index >= folded.length || !folded[index].isLetterOrDigit()

    private fun foldChar(c: Char, cache: MutableMap<Char, Char>): Char = when {
        c == '\t' -> ' '
        c.code < 128 -> c.lowercaseChar()
        c.isWhitespace() || c == ' ' || c == ' ' -> ' '
        c.isSurrogate() -> c
        else -> cache.getOrPut(c) { Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar() }
    }
}

/**
 * Specific secret formats, most specific first. Generic "password = …" assignments are only
 * reported when the value looks generated (letters and digits mixed), never a placeholder, an
 * environment lookup or a code expression — a developer's notes are full of those.
 *
 * Android's regex engine is ICU: no `(?U)`, hyphens escaped inside classes, bounded lookbehinds.
 */
internal object SecretPatterns {

    private class Rule(
        val id: String,
        val label: String,
        pattern: String,
        val valueGroup: Int = 0,
        val masked: Boolean = true,
        val accept: (MatchResult) -> Boolean = { true }
    ) {
        val regex = Regex(pattern)
    }

    private val PLACEHOLDER_WORDS = setOf(
        "password", "passwd", "pass", "secret", "token", "changeme", "example", "redacted", "placeholder",
        "none", "null", "undefined", "true", "false", "admin", "root", "postgres", "mysql", "guest", "test",
        "user", "username"
    )
    private val PLACEHOLDER_PREFIXES = listOf(
        "your", "my_", "my-", "example", "dummy", "fake", "sample", "test_", "test-", "insert", "replace", "todo"
    )
    private const val PLACEHOLDER_CHARS = "$%{}<>[]()*…•"
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "example.com", "example.org", "host.docker.internal")
    private val DOTTED_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+""")

    private val rules = listOf(
        Rule("github", "GitHub token", """(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{50,255})"""),
        Rule("gitlab", "GitLab token", """(?<![A-Za-z0-9_])glpat-[A-Za-z0-9_\-]{20,}"""),
        Rule(
            "aws", "AWS access key", """(?<![A-Za-z0-9])(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}(?![A-Za-z0-9])""",
            accept = { !it.value.contains("EXAMPLE") }
        ),
        Rule("google", "Google API key", """(?<![A-Za-z0-9_\-])AIza[0-9A-Za-z_\-]{35}(?![A-Za-z0-9_\-])"""),
        Rule("google_oauth", "Google OAuth secret", """GOCSPX-[A-Za-z0-9_\-]{28}"""),
        Rule("slack", "Slack token", """(?<![A-Za-z0-9])xox[abposr]-[A-Za-z0-9\-]{10,}"""),
        Rule("slack_webhook", "Slack webhook", """https://hooks\.slack\.com/services/[A-Za-z0-9_/]{20,}"""),
        Rule("stripe", "Stripe key", """(?<![A-Za-z0-9])(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,}"""),
        Rule(
            "ai_key", "AI API key", """(?<![A-Za-z0-9])sk-(?:proj-|ant-)?[A-Za-z0-9_\-]{32,}""",
            accept = { m -> m.value.any { it.isDigit() } && m.value.any { it.isUpperCase() } }
        ),
        Rule("huggingface", "Hugging Face token", """(?<![A-Za-z0-9])hf_[A-Za-z0-9]{34,}"""),
        Rule("npm", "npm token", """(?<![A-Za-z0-9])npm_[A-Za-z0-9]{36}(?![A-Za-z0-9])"""),
        Rule("private_key", "Private key", """-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----""", masked = false),
        Rule(
            "jwt", "JSON Web Token",
            """(?<![A-Za-z0-9_\-])eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""
        ),
        Rule("azure", "Azure key", """(?:AccountKey|SharedAccessKey)=([A-Za-z0-9+/]{30,}={0,2})""", valueGroup = 1),
        Rule(
            "url_password", "Password in a URL",
            """[A-Za-z][A-Za-z0-9+.\-]{1,15}://[^\s/:@]{1,64}:([^\s/@]{3,128})@([^\s/:?#]+)""",
            valueGroup = 1,
            accept = { m -> !isPlaceholder(m.groupValues[1]) && !isLocalHost(m.groupValues[2]) }
        ),
        Rule(
            "assignment", "Password or token",
            """(?i)(?<![A-Za-z0-9])(?:[A-Za-z0-9]{1,30}[_\-]){0,3}(?:password|passwd|secret|token|api[_\-]?key|access[_\-]?key|client[_\-]?secret|auth[_\-]?token)(?:[_\-]?key)?["']?[ \t]*[:=][ \t]*["']?([^\s"'`,;]{8,128})""",
            valueGroup = 1,
            accept = { m -> looksGenerated(m.groupValues[1]) }
        )
    )

    /** Every secret in [text]; where two rules match the same characters, the more specific one wins. */
    fun find(text: String): List<ConfidentialityScanner.Hit> {
        val kept = mutableListOf<ConfidentialityScanner.Hit>()
        for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                if (!rule.accept(match)) continue
                val group = match.groups[rule.valueGroup] ?: continue
                val start = group.range.first
                val end = group.range.last + 1
                if (kept.any { start < it.end && it.start < end }) continue
                kept += ConfidentialityScanner.Hit(
                    key = "secret:${rule.id}:${ConfidentialityScanner.fingerprint(group.value)}",
                    label = rule.label,
                    kind = FindingKind.SECRET,
                    start = start,
                    end = end,
                    masked = rule.masked
                )
            }
        }
        return kept
    }

    private fun isPlaceholder(value: String): Boolean {
        val v = value.lowercase()
        return v.any { it in PLACEHOLDER_CHARS } || "xxx" in v || "..." in v ||
            v in PLACEHOLDER_WORDS || PLACEHOLDER_PREFIXES.any { v.startsWith(it) }
    }

    private fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        return h in LOCAL_HOSTS || h.endsWith(".local") || h.endsWith(".example") || h.endsWith(".test")
    }

    /** A value that reads like a generated secret: letters and digits mixed, not code, not a placeholder. */
    private fun looksGenerated(value: String): Boolean {
        if (isPlaceholder(value) || DOTTED_IDENTIFIER.matches(value)) return false
        val digits = value.count { it.isDigit() }
        val letters = value.count { it.isLetter() }
        val classes = listOf(
            value.any { it.isLowerCase() },
            value.any { it.isUpperCase() },
            digits > 0,
            value.any { !it.isLetterOrDigit() }
        ).count { it }
        return (digits >= 2 && letters >= 2) || (classes >= 3 && digits >= 1 && value.length >= 10)
    }
}
