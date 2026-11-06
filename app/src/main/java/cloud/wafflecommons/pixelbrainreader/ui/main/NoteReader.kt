package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.editor.NoteHeading
import cloud.wafflecommons.pixelbrainreader.data.editor.NoteSegment
import kotlin.math.roundToInt

/** Below this width per column, a table gets its own sideways scroll instead of squeezed cells. */
private const val TABLE_COLUMN_DP = 140

/**
 * The note's body for reading long notes: one view per segment ([NoteSegment]), emitted straight
 * into the caller's scrolling column. Each segment's top, in that column, lands in [sectionTops]
 * (segment index → px), which is what the outline scrolls to.
 */
@Composable
fun NoteReader(
    segments: List<NoteSegment>,
    onWikiLinkClick: (String) -> Unit,
    sectionTops: SnapshotStateMap<Int, Int>
) {
    segments.forEachIndexed { index, segment ->
        val tracked = Modifier.onGloballyPositioned { sectionTops[index] = it.positionInParent().y.roundToInt() }
        when (segment) {
            is NoteSegment.Markdown -> Box(tracked) { MarkwonContent(content = segment.text, onWikiLinkClick = onWikiLinkClick) }
            is NoteSegment.Table -> WideTable(segment, onWikiLinkClick, tracked.padding(vertical = 4.dp))
        }
    }
}

/** A table as wide as its columns need: at screen width when they fit, scrolling sideways when not. */
@Composable
private fun WideTable(table: NoteSegment.Table, onWikiLinkClick: (String) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wanted = (table.columns * TABLE_COLUMN_DP).dp
        if (wanted <= maxWidth) {
            MarkwonContent(content = table.markdown, onWikiLinkClick = onWikiLinkClick, modifier = Modifier.fillMaxWidth())
        } else {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Box(Modifier.width(wanted)) {
                    MarkwonContent(content = table.markdown, onWikiLinkClick = onWikiLinkClick, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private val WIKI_ALIAS = Regex("""\[\[[^\]|]+\|([^\]]+)]]""")
private val WIKI = Regex("""\[\[([^\]]+)]]""")
private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")

/** A heading as read, not as written: no backticks, emphasis marks or link syntax. */
internal fun plainHeading(text: String): String {
    var t = text.replace("`", "").replace("**", "").replace("__", "")
    t = WIKI_ALIAS.replace(t) { it.groupValues[1] }
    t = WIKI.replace(t) { it.groupValues[1].substringAfterLast('/') }
    t = MD_LINK.replace(t) { it.groupValues[1] }
    return t.trim()
}

/** The note's `#`–`###` headings; picking one jumps to its section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteOutlineSheet(outline: List<Pair<Int, NoteHeading>>, onPick: (segmentIndex: Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Outline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            outline.forEach { (index, heading) ->
                Text(
                    plainHeading(heading.text),
                    style = if (heading.level == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(start = ((heading.level - 1) * 16).dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}
