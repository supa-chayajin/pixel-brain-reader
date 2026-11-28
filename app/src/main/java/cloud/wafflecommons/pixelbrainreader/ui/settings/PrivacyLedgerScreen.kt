package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.privacy.HostTotal
import cloud.wafflecommons.pixelbrainreader.data.privacy.LedgerDay
import cloud.wafflecommons.pixelbrainreader.data.privacy.LedgerDays
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings › Privacy & safety › Privacy ledger (13.0, X3; under Privacy & safety since 13.4). */
const val PRIVACY_LEDGER_ROUTE = "privacy_ledger"

/**
 * "What left this phone today" (X3, the owner's D3): today first, then each of the last 30 days,
 * grouped by host with its purposes, calls and bytes. Read from the phone only; nothing to change.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrivacyLedgerScreen(onNavigateBack: () -> Unit) {
    val days by produceState<List<LedgerDay>?>(initialValue = null) { value = PrivacyLedger.days() }
    // 13.4 (S-k): the Settings frame, under Privacy & safety.
    SettingsPageScaffold(page = SettingsPage.PRIVACY_LEDGER, onBack = onNavigateBack) {
        Text(
            "Each host the app talked to, why, how often and about how much. Only hosts and sizes " +
                "are kept, never what was sent, on this phone for 30 days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val list = days
        if (list == null) {
            LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            list.forEachIndexed { index, day -> DaySection(day, today = index == 0) }
            if (list.size == 1) EmptyHint("Earlier days show here once the ledger has counted them.")
        }
        Text(
            "Sizes come from the phone's own count of the app's traffic while each call runs, so they " +
                "include encryption, and calls at the same moment share what was counted. Not listed: " +
                "what Google Play services and the system do for the app, outside it (Google sign-in, " +
                "Gemini Nano's download).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A day (13.4: segmented like the rest of Settings): its heading, then one group whose rows are its
 * totals (or that nothing left), each host, and the unnamed traffic, each row its own segment.
 */
@Composable
private fun DaySection(day: LedgerDay, today: Boolean) {
    val hosts = LedgerDays.byHost(day)
    val summary = when {
        LedgerDays.isEmpty(day) -> null
        !day.measured -> "Sizes unknown: this phone didn't report the app's traffic."
        else -> "About ${LedgerDays.bytes(LedgerDays.sent(day))} sent, ${LedgerDays.bytes(LedgerDays.received(day))} received"
    }
    SettingsPageSection(title = if (today) "Today" else dayTitle(day.date)) {
        if (summary != null) row { SettingsNote(summary) }
        if (LedgerDays.isEmpty(day)) {
            row { SettingsNote(if (today) "Nothing has left the phone today yet." else "Nothing left the phone.") }
        }
        hosts.forEach { host -> row { HostRow(host, measured = day.measured) } }
        if (day.unnamedSent > 0 || day.unnamedReceived > 0) {
            row {
                LedgerRow(
                    name = "Not named",
                    detail = "Traffic while none of the calls above ran, such as a library's own reports",
                    sent = day.unnamedSent,
                    received = day.unnamedReceived,
                    measured = true
                )
            }
        }
    }
}

@Composable
private fun HostRow(host: HostTotal, measured: Boolean) {
    val calls = if (host.calls == 1) "1 call" else "${host.calls} calls"
    LedgerRow(
        name = host.host,
        detail = host.purposes.joinToString(", ") { it.label } + " · " + calls,
        sent = host.sent,
        received = host.received,
        measured = measured
    )
}

/** A host (or the unnamed traffic) as a row of its day's group: its segment shape, the kit's inner margin. */
@Composable
private fun LedgerRow(name: String, detail: String, sent: Long, received: Long, measured: Boolean) {
    Surface(
        shape = LocalSegmentPosition.current.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SettingsRowPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (measured) {
                val up = LedgerDays.bytes(sent)
                val down = LedgerDays.bytes(received)
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "$up sent, $down received" }
                ) {
                    Text("↑ $up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text("↓ $down", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

private fun dayTitle(date: String): String =
    runCatching { LocalDate.parse(date).format(DAY_TITLE) }.getOrDefault(date)
