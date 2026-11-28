package cloud.wafflecommons.pixelbrainreader.guard

import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plan's "search index checked against the routes" (13.4, S6): every route the Settings home
 * and its search open is registered by a NavHost, so a renamed or forgotten route fails the build
 * rather than a tap on the phone (an unknown route crashes the navigation).
 *
 * It reads the navigation files' sources: each `composable(` call's first argument (a literal, an
 * `Owner.NAME` constant, a fully qualified one, or a bare top-level `NAME`), resolved through the
 * `const val`s of the route objects (one constant may name another). Routes compare by their base,
 * the part before `?`.
 */
class SettingsIndexGuardTest {

    private val sourceRoot: File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "cloud/wafflecommons/pixelbrainreader") }
        .first { it.isDirectory }

    /** The files that register destinations. */
    private val navigationFiles = listOf(
        "ui/main/MainScreen.kt",
        "ui/main/V11Destinations.kt",
        "ui/games/GamesNav.kt",
        "ui/feeds/FeedsNav.kt",
        "ui/settings/SettingsNav.kt",
        "ui/settings/SettingsHomeNav.kt"
    )

    /** The files whose constants name the routes. */
    private val constantFiles = navigationFiles + listOf("ui/settings/SettingsPages.kt", "ui/settings/PrivacyLedgerScreen.kt")

    @Test
    fun `every route of the settings index is registered`() {
        val routes = RouteConstants()
        constantFiles.mapNotNull(::codeOf).forEach(routes::read)
        val registered = navigationFiles.mapNotNull(::codeOf)
            .flatMap { code -> COMPOSABLE.findAll(code).map { it.groupValues[1] }.toList() }
            .mapNotNull(routes::resolve)
            .map(::baseOf)
            .toSet()

        val missing = SettingsIndex.ENTRIES
            .mapNotNull { entry -> entry.route?.let { route -> entry.key to baseOf(route) } }
            .filterNot { (_, base) -> base in registered }

        assertTrue(
            "Routes the Settings index opens that no NavHost registers (entry to route): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `every navigation file's destinations are added to the NavHost`() {
        // A route registered in a file whose destinations nobody adds is no route at all.
        val hosts = listOf("ui/main/MainScreen.kt", "ui/main/V11Destinations.kt").mapNotNull(::codeOf).joinToString("\n")
        val declared = navigationFiles.mapNotNull(::codeOf)
            .flatMap { code -> DESTINATIONS.findAll(code).map { it.groupValues[1] }.toList() }
        assertTrue("No destinations function found in $navigationFiles", declared.isNotEmpty())

        val notAdded = declared.filterNot { name -> Regex("""(?<!NavGraphBuilder\.)\b$name\(""").containsMatchIn(hosts) }

        assertTrue("Destinations never added to the NavHost: $notAdded", notAdded.isEmpty())
    }

    @Test
    fun `the reader resolves literals, constants, qualified names and one constant naming another`() {
        val code = stripComments(
            """
            object Screen {
                const val Settings = "settings"
                const val Game = "game?page={page}"
            }
            object Routes {
                const val HOME = Screen.Settings // "settings"
                const val LEDGER = LEDGER_ROUTE
            }
            const val LEDGER_ROUTE = "privacy_ledger"
            fun NavGraphBuilder.routes() {
                composable("daily_layout") { }
                composable(Routes.HOME) { }
                composable(
                    Screen.Game,
                    arguments = listOf()
                ) { }
                composable(some.package.LEDGER_ROUTE) { }
                // composable("in_a_comment") { }
            }
            """.trimIndent()
        )
        val routes = RouteConstants().also { it.read(code) }
        val found = COMPOSABLE.findAll(code).map { it.groupValues[1] }.mapNotNull(routes::resolve).map(::baseOf).toList()

        assertEquals(listOf("daily_layout", "settings", "game", "privacy_ledger"), found)
        assertEquals("privacy_ledger", routes.resolve("Routes.LEDGER"))
    }

    private fun codeOf(path: String): String? =
        File(sourceRoot, path).takeIf { it.isFile }?.readText()?.let(::stripComments)

    private fun baseOf(route: String): String = route.substringBefore('?')

    /** The `const val`s of each `object Name { … }`, and the top-level ones, as written (a literal or a name). */
    private class RouteConstants {
        private val owned = mutableMapOf<String, String>()
        private val topLevel = mutableMapOf<String, String>()

        fun read(code: String) {
            val objects = OBJECT.findAll(code).map { match ->
                val open = match.range.last
                match.groupValues[1] to (open until endOfBlock(code, open + 1))
            }.toList()
            CONSTANT.findAll(code).forEach { match ->
                val (name, value) = match.destructured
                // The innermost object holding the constant (the one opened last).
                val owner = objects.filter { (_, body) -> match.range.first in body }.maxByOrNull { (_, body) -> body.first }
                if (owner != null) {
                    owned["${owner.first}.$name"] = value
                } else {
                    topLevel[name] = value
                }
            }
        }

        /** [argument]'s string: a literal as is, else `Owner.NAME` (its last two segments), else a top-level `NAME`. */
        fun resolve(argument: String, depth: Int = 0): String? {
            if (argument.startsWith("\"")) return argument.removeSurrounding("\"")
            if (depth > MAX_DEPTH) return null
            val segments = argument.split('.')
            val value = segments.takeIf { it.size >= 2 }?.let { owned["${it[it.size - 2]}.${it.last()}"] }
                ?: topLevel[segments.last()]
                ?: return null
            return resolve(value, depth + 1)
        }
    }

    private companion object {
        const val MAX_DEPTH = 4

        /** A destination's first argument: a string literal or a (dotted) name. */
        val COMPOSABLE = Regex("""\bcomposable\s*\(\s*(?:route\s*=\s*)?("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.]*)""")

        /** A `const val NAME = …` and its value: a string literal or a (dotted) name. */
        val CONSTANT = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.]*)""")

        /** A named object's opening brace (a companion without a name is no owner). */
        val OBJECT = Regex("""\bobject\s+(\w+)[^{=\n]*\{""")

        /** A file's destinations: `fun NavGraphBuilder.name(`. */
        val DESTINATIONS = Regex("""\bfun\s+NavGraphBuilder\.(\w+)\s*\(""")

        /** [code] without its comments (block comments nest in Kotlin), its strings kept whole. */
        fun stripComments(code: String): String {
            val out = StringBuilder(code.length)
            var i = 0
            while (i < code.length) {
                when {
                    code.startsWith("//", i) -> {
                        while (i < code.length && code[i] != '\n') i++
                    }
                    code.startsWith(BLOCK_OPEN, i) -> i = endOfBlockComment(code, i)
                    code[i] == '"' -> {
                        val end = endOfString(code, i)
                        out.append(code, i, end)
                        i = end
                    }
                    code[i] == '\'' -> {
                        val end = endOfChar(code, i)
                        out.append(code, i, end)
                        i = end
                    }
                    else -> {
                        out.append(code[i])
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** Just past the block comment opening at [start], the comments nested in it included. */
        fun endOfBlockComment(code: String, start: Int): Int {
            var depth = 0
            var i = start
            while (i < code.length) {
                when {
                    code.startsWith(BLOCK_OPEN, i) -> {
                        depth++
                        i += 2
                    }
                    code.startsWith(BLOCK_CLOSE, i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            return code.length
        }

        /** Just past the string literal opening at [start] (plain or raw), its templates skipped whole. */
        fun endOfString(code: String, start: Int): Int {
            val raw = code.startsWith(RAW_QUOTE, start)
            var i = start + (if (raw) 3 else 1)
            while (i < code.length) {
                when {
                    raw && code.startsWith(RAW_QUOTE, i) -> return i + 3
                    !raw && code[i] == '\\' -> i += 2
                    !raw && code[i] == '"' -> return i + 1
                    code.startsWith(TEMPLATE_OPEN, i) -> i = endOfBlock(code, i + 2)
                    else -> i++
                }
            }
            return code.length
        }

        /** Just past the character literal opening at [start]; a lone quote is one character. */
        fun endOfChar(code: String, start: Int): Int {
            var i = start + 1
            if (i < code.length && code[i] == '\\') i++
            i++
            return if (i < code.length && code[i] == '\'') i + 1 else start + 1
        }

        /** Just past the brace that closes the block whose content starts at [start] (strings skipped). */
        fun endOfBlock(code: String, start: Int): Int {
            var depth = 1
            var i = start
            while (i < code.length) {
                when (code[i]) {
                    '"' -> i = endOfString(code, i)
                    '\'' -> i = endOfChar(code, i)
                    '{' -> {
                        depth++
                        i++
                    }
                    '}' -> {
                        depth--
                        i++
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            return code.length
        }

        const val BLOCK_OPEN = "/" + "*"
        const val BLOCK_CLOSE = "*" + "/"
        const val RAW_QUOTE = "\"\"\""
        const val TEMPLATE_OPEN = "\${"
    }
}
