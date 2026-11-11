package cloud.wafflecommons.pixelbrainreader.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A data file's conflict in one line (S1, owner 2026-11-11: keep mine or keep theirs, with what
 * differs instead of raw JSON), pure. Objects are compared key by key, arrays as multisets of
 * entries: "3 entries only on this phone, 1 only on GitHub, 2 changed".
 */
object JsonConflictSummary {

    data class Counts(val onlyMine: Int = 0, val onlyTheirs: Int = 0, val changed: Int = 0) {
        operator fun plus(other: Counts) = Counts(onlyMine + other.onlyMine, onlyTheirs + other.onlyTheirs, changed + other.changed)
    }

    fun of(mine: String?, theirs: String?): String {
        if (mine == null) return "Deleted on this phone, kept on GitHub."
        if (theirs == null) return "Deleted on GitHub, kept on this phone."
        val a = parse(mine) ?: return "This phone's copy isn't valid JSON."
        val b = parse(theirs) ?: return "GitHub's copy isn't valid JSON."
        val counts = compare(a, b)
        val parts = listOfNotNull(
            counts.onlyMine.takeIf { it > 0 }?.let { "${entries(it)} only on this phone" },
            counts.onlyTheirs.takeIf { it > 0 }?.let { "${entries(it)} only on GitHub" },
            counts.changed.takeIf { it > 0 }?.let { "${entries(it)} changed" }
        )
        return if (parts.isEmpty()) "The same data, written differently." else parts.joinToString(", ") + "."
    }

    fun compare(a: JsonElement, b: JsonElement): Counts = when {
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).fold(Counts()) { counts, key ->
            val mine = a[key]
            val theirs = b[key]
            when {
                theirs == null -> counts.copy(onlyMine = counts.onlyMine + 1)
                mine == null -> counts.copy(onlyTheirs = counts.onlyTheirs + 1)
                mine == theirs -> counts
                (mine is JsonObject && theirs is JsonObject) || (mine is JsonArray && theirs is JsonArray) -> counts + compare(mine, theirs)
                else -> counts.copy(changed = counts.changed + 1)
            }
        }
        a is JsonArray && b is JsonArray -> {
            val left = a.groupingBy { it }.eachCount()
            val right = b.groupingBy { it }.eachCount()
            Counts(
                onlyMine = left.entries.sumOf { (entry, n) -> (n - (right[entry] ?: 0)).coerceAtLeast(0) },
                onlyTheirs = right.entries.sumOf { (entry, n) -> (n - (left[entry] ?: 0)).coerceAtLeast(0) }
            )
        }
        a == b -> Counts()
        else -> Counts(changed = 1)
    }

    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"

    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text).takeIf(::isJson)
    } catch (e: IllegalArgumentException) {
        // kotlinx's SerializationException is an IllegalArgumentException.
        null
    }

    /** kotlinx reads a bare word (`<<<<<<<`) as a literal; JSON allows only true, false, null and numbers unquoted. */
    private fun isJson(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::isJson)
        is JsonArray -> element.all(::isJson)
        is JsonPrimitive -> element.isString || element is JsonNull ||
            element.content == "true" || element.content == "false" || element.content.toDoubleOrNull() != null
    }
}
