package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/*
 * Drag to reorder (13.4, S5), written by hand: the build has no reorder library (settled decision
 * #9). Long-press a row's handle, then drag; the row follows the finger above the others, and each
 * time its middle passes a neighbour's middle the list moves it one place ([ReorderState]'s
 * onMove), the page committing its order once, at the drop (onDrop). Screen readers get "Move up"
 * and "Move down" on the row instead ([reorderActions]).
 *
 * A page keeps a working copy while dragging and commits once:
 *
 *     var working by remember(saved) { mutableStateOf(saved) }
 *     val reorder = rememberReorderState(
 *         onMove = { f, t -> working = ReorderMath.moved(working, f, t) },
 *         onDrop = { commit(working) }
 *     )
 *
 * then gives each row `Modifier.reorderable(reorder, key, index)` (before its own clip and
 * background, so the whole row moves), a [ReorderHandle], and `Modifier.reorderActions(...)`. It
 * works in a Column and in a LazyColumn (give the lazy items their keys; in a Column, `key(rowKey)`
 * around each row keeps its state with it, though the drag follows the row either way). The group
 * around the rows must not clip them: a dragged row leaves its place. The dragged row's own
 * placement must not be animated (a lazy item's `animateItem(placementSpec = null)` while it is
 * [ReorderState.draggedKey]): the drag already holds it under the finger.
 */

/**
 * A list's drag (13.4, S5): long-press a row's ≡ handle, then drag; [onMove] each time the row
 * passes a neighbour, [onDrop] once at the end (only when something moved). The dragged row is
 * translated so it stays under the finger, whatever the list does under it (a move, a scroll).
 */
@Stable
class ReorderState internal constructor(
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit,
    scrollBy: (suspend (Float) -> Float)?
) {
    internal var onMove: (Int, Int) -> Unit = onMove
    internal var onDrop: () -> Unit = onDrop
    internal var scrollBy: (suspend (Float) -> Float)? = scrollBy
    internal var scope: CoroutineScope? = null
    internal var haptic: HapticFeedback? = null

    /** How near the visible edge the dragged row's middle starts a scroll, and how fast, per second. */
    internal var edgePx = 0f
    internal var speedPx = 0f

    /** The floating bar over the bottom of the screen: a scroll starts above it. */
    internal var bottomInsetPx = 0f

    /** The key of the row being dragged; null between drags. */
    var draggedKey: Any? by mutableStateOf(null)
        private set

    /** The row going back to its place after a drop, and how far from it it still is. */
    private var settlingKey: Any? by mutableStateOf(null)
    private var settleOffset by mutableFloatStateOf(0f)

    /** The finger's travel since the drag began, and the dragged row's laid-out top (root coordinates). */
    private var travel by mutableFloatStateOf(0f)
    private var draggedTop by mutableFloatStateOf(0f)

    /** The dragged row's laid-out top when the drag began: with [travel], where the finger holds it. */
    private var startTop = 0f
    private var draggedHeight = 0f
    private var moved = false

    /** The visible part of the list (root coordinates), for the scroll near its edges. */
    private var areaTop = 0f
    private var areaBottom = Float.MAX_VALUE

    /** Each time the dragged row is placed: a scroll that placed it already needs no prediction. */
    private var placements = 0
    private var scrolling: Job? = null
    private var settling: Job? = null

    /** One entry per row on screen, kept by its node: a row slot may show another key over time. */
    internal class RowBounds(var key: Any, var index: Int) {
        var top = 0f
        var height = 0f
        var placed = false
        var coordinates: LayoutCoordinates? = null
    }

    private val rows = ArrayList<RowBounds>()

    /** The dragged row's translation (0 for the others): where the finger holds it, minus where the list lays it. */
    fun offsetOf(key: Any): Float = when (key) {
        draggedKey -> startTop + travel - draggedTop
        settlingKey -> settleOffset
        else -> 0f
    }

    /** The dragged row and the one settling back: both drawn above their neighbours. */
    internal fun isLifted(key: Any): Boolean = key == draggedKey || key == settlingKey

    internal fun register(key: Any, index: Int): RowBounds = RowBounds(key, index).also { rows.add(it) }

    internal fun unregister(row: RowBounds) {
        rows.remove(row)
    }

    /**
     * A row node's key or index changed. The same key at a new index: the list moved it, and its
     * new place comes with the next layout (the move already predicted it). Another key in the
     * same slot (a Column without `key()`): the dragged row takes the place the move predicted for
     * it; any other row, the slot's place, until the layout says.
     */
    internal fun rekey(row: RowBounds, key: Any, index: Int) {
        val sameRow = row.key == key
        row.key = key
        row.index = index
        if (sameRow) return
        if (key == draggedKey) {
            row.top = draggedTop
            row.height = draggedHeight
            return
        }
        row.coordinates?.takeIf { it.isAttached }?.let {
            row.top = it.positionInRoot().y
            row.height = it.size.height.toFloat()
        }
    }

    internal fun place(row: RowBounds, coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        row.coordinates = coordinates
        row.top = coordinates.positionInRoot().y
        row.height = coordinates.size.height.toFloat()
        row.placed = true
        if (row.key == draggedKey) {
            placements++
            draggedTop = row.top
            draggedHeight = row.height
        }
    }

    /** A long-press on [key]'s handle: true when the drag began (the row is laid out, no other drag runs). */
    internal fun start(key: Any, index: Int): Boolean {
        if (draggedKey != null) return false
        val row = rowOf(key) ?: return false
        settling?.cancel()
        settling = null
        settlingKey = null
        settleOffset = 0f
        row.index = index
        startTop = row.top
        draggedTop = row.top
        draggedHeight = row.height
        travel = 0f
        moved = false
        row.coordinates?.takeIf { it.isAttached }?.let { measureArea(it) }
        draggedKey = key
        haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (scrollBy != null) scrolling = scope?.launch { autoScroll() }
        return true
    }

    internal fun dragBy(dy: Float) {
        if (draggedKey == null) return
        travel += dy
        step()
    }

    /** The finger lifted (or the gesture was cancelled): the row settles in its place, the order is committed once. */
    internal fun end() {
        val key = draggedKey ?: return
        val offset = offsetOf(key)
        scrolling?.cancel()
        scrolling = null
        val wasMoved = moved
        moved = false
        settlingKey = key
        settleOffset = offset
        draggedKey = null
        travel = 0f
        settling = scope?.launch {
            animate(initialValue = offset, targetValue = 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                settleOffset = value
            }
            settlingKey = null
        }
        if (settling == null) settlingKey = null
        if (wasMoved) onDrop()
    }

    private fun rowOf(key: Any): RowBounds? = rows.firstOrNull { it.key == key && it.placed }

    /**
     * One step at most: when the dragged row's middle (where the finger holds it) passed a
     * neighbour's middle, the two swap. Their bounds are swapped here too, so the row's offset keeps
     * it under the finger in the very frame the list shows the move (the layout then confirms them).
     */
    private fun step() {
        val key = draggedKey ?: return
        val row = rowOf(key) ?: return
        val from = row.index
        val middle = startTop + travel + row.height / 2f
        val to = ReorderMath.target(from, middle, middles())
        if (to == from) return
        val other = rows.firstOrNull { it !== row && it.placed && it.index == to } ?: return
        if (to > from) {
            val newTop = other.top + other.height - row.height
            other.top = row.top
            row.top = newTop
        } else {
            val otherTop = row.top + row.height - other.height
            row.top = other.top
            other.top = otherTop
        }
        other.index = from
        row.index = to
        draggedTop = row.top
        moved = true
        haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onMove(from, to)
    }

    /** Each row's laid-out middle by index; a row not on screen is NaN (never passed). */
    private fun middles(): List<Float> {
        var count = 0
        rows.forEach { if (it.placed && it.index >= count) count = it.index + 1 }
        val list = MutableList(count) { Float.NaN }
        rows.forEach { if (it.placed && it.index >= 0) list[it.index] = it.top + it.height / 2f }
        return list
    }

    /**
     * The visible part of the list around [row]: the first ancestor shown smaller than it is (the
     * scrolling content, cut by its viewport), else the row's parent (a lazy list is its viewport);
     * never under the floating bar.
     */
    private fun measureArea(row: LayoutCoordinates) {
        val rootHeight = row.findRootCoordinates().size.height.toFloat()
        var parent = row.parentLayoutCoordinates
        var first: Rect? = null
        var cut: Rect? = null
        while (parent != null && parent.isAttached) {
            val visible = parent.boundsInRoot()
            if (first == null) first = visible
            if (visible.height + 1f < parent.size.height.toFloat()) {
                cut = visible
                break
            }
            parent = parent.parentLayoutCoordinates
        }
        val area = cut ?: first
        areaTop = max(area?.top ?: 0f, 0f)
        areaBottom = min(area?.bottom ?: rootHeight, rootHeight - bottomInsetPx)
    }

    /**
     * While the dragged row's middle is near the visible edge of the list, the list scrolls, faster
     * the nearer it is; the row stays under the finger and passes the rows the scroll brings.
     */
    private suspend fun autoScroll() {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = if (last == 0L) 0f else (now - last) / NANOS_PER_SECOND
            last = now
            val key = draggedKey ?: return
            val row = rowOf(key) ?: continue
            row.coordinates?.takeIf { it.isAttached }?.let { measureArea(it) }
            // What the last frame's scroll brought under the finger.
            step()
            val scroll = scrollBy ?: return
            val zone = max(edgePx, row.height / 2f)
            val middle = startTop + travel + row.height / 2f
            val up = (areaTop + zone) - middle
            val down = middle - (areaBottom - zone)
            val depth = when {
                up > 0f && up >= down -> -min(up / zone, 1f)
                down > 0f -> min(down / zone, 1f)
                else -> 0f
            }
            if (depth == 0f || elapsed <= 0f) continue
            val before = placements
            val consumed = scroll(depth * speedPx * elapsed)
            // The content moved by what the scroll consumed; when the list hasn't placed the row yet,
            // its bounds follow now, so the offset holds the row under the finger in this frame.
            if (consumed != 0f && placements == before) {
                rows.forEach { if (it.placed) it.top -= consumed }
                draggedTop -= consumed
            }
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}

/** How near the visible edge a dragged row starts the list scrolling, and how fast it goes. */
private val ScrollEdge = 56.dp
private val ScrollSpeed = 900.dp

/** The handle's touch target. */
private val HandleSize = 48.dp

private const val DISABLED_ALPHA = 0.38f

/** "Move up" and "Move down": what a screen reader offers on a row instead of the drag (S5). */
const val MOVE_UP = "Move up"
const val MOVE_DOWN = "Move down"

/**
 * The drag of a list (13.4, S5). [scrollBy]: a long list scrolls while the row is dragged near its
 * edge (`LazyListState::scrollBy`, `ScrollState::scrollBy`); without it, the list stays put.
 */
@Composable
fun rememberReorderState(
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    /** A long list scrolls while the row is dragged near its edge (LazyListState::scrollBy, ScrollState::scrollBy). */
    scrollBy: (suspend (Float) -> Float)? = null
): ReorderState {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val bottomInset = NavBarClearance
    val state = remember { ReorderState(onMove, onDrop, scrollBy) }
    SideEffect {
        state.onMove = onMove
        state.onDrop = onDrop
        state.scrollBy = scrollBy
        state.scope = scope
        state.haptic = haptic
        with(density) {
            state.edgePx = ScrollEdge.toPx()
            state.speedPx = ScrollSpeed.toPx()
            state.bottomInsetPx = bottomInset.toPx()
        }
    }
    return state
}

/**
 * On each reorderable row: reports its bounds (in root coordinates, before its translation) and
 * translates it while dragged, above its neighbours. Works in a Column and a LazyColumn. Put it
 * before the row's own clip and background, so the whole row moves.
 */
fun Modifier.reorderable(state: ReorderState, key: Any, index: Int): Modifier =
    this
        .zIndex(if (state.isLifted(key)) 1f else 0f)
        .then(ReorderableElement(state, key, index))
        .graphicsLayer { translationY = state.offsetOf(key) }

/**
 * The ≡ handle (Icons.Rounded.DragHandle, 48 dp touch target): a long-press starts the drag of
 * [key]'s row, then it follows the finger. Hidden from screen readers, which get [reorderActions].
 */
@Composable
fun ReorderHandle(state: ReorderState, key: Any, index: Int, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val currentKey by rememberUpdatedState(key)
    val currentIndex by rememberUpdatedState(index)
    // Keyed on the state only: a move may hand this slot another row mid-drag, the gesture goes on.
    val drag = if (enabled) {
        Modifier.pointerInput(state) {
            var active = false
            detectDragGesturesAfterLongPress(
                onDragStart = { active = state.start(currentKey, currentIndex) },
                onDragEnd = {
                    if (active) state.end()
                    active = false
                },
                onDragCancel = {
                    if (active) state.end()
                    active = false
                },
                onDrag = { change, amount ->
                    if (active) {
                        change.consume()
                        state.dragBy(amount.y)
                    }
                }
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(HandleSize)
            .clearAndSetSemantics {}
            .then(drag),
        contentAlignment = Alignment.Center
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA)
        )
    }
}

/**
 * "Move up" and "Move down" for screen readers (S5): custom actions on the row, each a one-step
 * move then [onDrop]. The row reads as one item (its texts merged; its buttons stay their own).
 */
fun Modifier.reorderActions(
    index: Int,
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    enabled: Boolean = true
): Modifier {
    if (!enabled || count < 2 || index !in 0 until count) return this
    val actions = buildList {
        if (index > 0) {
            add(CustomAccessibilityAction(MOVE_UP) { onMove(index, index - 1); onDrop(); true })
        }
        if (index < count - 1) {
            add(CustomAccessibilityAction(MOVE_DOWN) { onMove(index, index + 1); onDrop(); true })
        }
    }
    return this.semantics(mergeDescendants = true) { customActions = actions }
}

/** The order's arithmetic (13.4, S5). Pure. */
object ReorderMath {

    /** [list] with the item at [from] moved to [to]; out of range or [from] == [to]: the same list. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * One step: [from] + 1 when the dragged row's middle passed the next row's middle, [from] - 1
     * for the previous one, else [from]. A neighbour not on screen ([middles] NaN) is never passed.
     */
    fun target(from: Int, draggedMiddle: Float, middles: List<Float>): Int {
        if (from !in middles.indices) return from
        val next = middles.getOrNull(from + 1)
        if (next != null && draggedMiddle > next) return from + 1
        val previous = middles.getOrNull(from - 1)
        if (previous != null && draggedMiddle < previous) return from - 1
        return from
    }
}

private class ReorderableElement(
    private val state: ReorderState,
    private val key: Any,
    private val index: Int
) : ModifierNodeElement<ReorderableNode>() {

    override fun create(): ReorderableNode = ReorderableNode(state, key, index)

    override fun update(node: ReorderableNode) {
        node.update(state, key, index)
    }

    override fun equals(other: Any?): Boolean =
        other is ReorderableElement && other.state === state && other.key == key && other.index == index

    override fun hashCode(): Int = (state.hashCode() * 31 + key.hashCode()) * 31 + index

    override fun InspectorInfo.inspectableProperties() {
        name = "reorderable"
        properties["key"] = key
        properties["index"] = index
    }
}

/** A row's place for its [ReorderState]: registered while attached, reported each time it is placed. */
private class ReorderableNode(
    private var state: ReorderState,
    private var key: Any,
    private var index: Int
) : Modifier.Node(), GlobalPositionAwareModifierNode {

    private var row: ReorderState.RowBounds? = null

    override fun onAttach() {
        row = state.register(key, index)
    }

    override fun onDetach() {
        row?.let(state::unregister)
        row = null
    }

    fun update(state: ReorderState, key: Any, index: Int) {
        if (state !== this.state) {
            row?.let(this.state::unregister)
            this.state = state
            this.key = key
            this.index = index
            row = if (isAttached) state.register(key, index) else null
            return
        }
        this.key = key
        this.index = index
        row?.let { state.rekey(it, key, index) }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        row?.let { state.place(it, coordinates) }
    }
}
