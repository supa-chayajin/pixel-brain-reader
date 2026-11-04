package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection

/**
 * One line telling what the "More today" fold holds, so a folded block is never forgotten:
 * "3 moments · 2 gratitude · ideas". Only the folded blocks count; empty ones are left out.
 */
internal fun moreTodaySummary(
    fold: Set<DailySection>,
    moments: Int,
    tasks: Int,
    gratitude: Int,
    echoes: Int,
    scraps: Int,
    hasIdeas: Boolean,
    countdowns: Int
): String {
    val parts = buildList {
        if (DailySection.TASKS in fold && tasks > 0) add(plural(tasks, "task"))
        if (DailySection.TIMELINE in fold && moments > 0) add(plural(moments, "moment"))
        if (DailySection.GRATITUDE in fold && gratitude > 0) add("$gratitude gratitude")
        if (DailySection.SECOND_BRAIN in fold && hasIdeas) add("ideas")
        if (DailySection.SCRATCHPAD in fold && scraps > 0) add(plural(scraps, "scrap"))
        if (DailySection.ECHOES in fold && echoes > 0) add(plural(echoes, "echo", "echoes"))
        if (DailySection.COUNTDOWNS in fold && countdowns > 0) add(plural(countdowns, "countdown"))
    }
    return if (parts.isEmpty()) "Gratitude, ideas, mantra and more" else parts.joinToString(" · ")
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

@Composable
internal fun MoreTodayHeader(summary: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("More today", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Fold" else "Unfold"
            )
        }
    }
}
