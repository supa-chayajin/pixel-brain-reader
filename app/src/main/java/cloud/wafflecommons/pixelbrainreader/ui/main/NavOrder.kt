package cloud.wafflecommons.pixelbrainreader.ui.main

/**
 * [all] in the user's [order] of routes (Settings → navigation order), for the bottom bar and the
 * side rail alike. A destination missing from [order] (one added in a later version) is appended,
 * so nothing ever disappears from the navigation.
 */
internal fun <T> inNavOrder(order: List<String>, all: List<T>, route: (T) -> String): List<T> =
    order.mapNotNull { r -> all.find { route(it) == r } } + all.filter { d -> order.none { it == route(d) } }
