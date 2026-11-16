@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth
import cloud.wafflecommons.pixelbrainreader.ui.utils.isWideLayout
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Card
import androidx.compose.ui.text.style.TextOverflow

/**
 * Shared building blocks for the v11 screens (settings-like pages, cards and pickers), so every new
 * surface looks the same and none re-implements a dialog.
 */

/**
 * A page's frame: the Daily screen's centred header (13.2, the owner's call: an emblem, the
 * emphasized title, an optional [subtitle] pill) over a scrolling column that clears the floating nav
 * bar. The header stays put as the page scrolls, like Daily's (13.2.2): the collapsing behavior,
 * made for the large bar, shrank this small one to nothing and left its status-bar strip in the
 * scrolled colour, a block over a list scrolled beside an open page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FormScaffold(
    title: String,
    /** null: no back arrow (a navigation tab's own page). */
    onBack: (() -> Unit)?,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    subtitle: String? = null,
    /** The page's emblem in its header (13.2). */
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = title,
                subtitle = subtitle,
                icon = icon,
                navigationIcon = {
                    if (onBack != null) {
                        CortexIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                // The owner's call (13.2): every page wears the Daily screen's centred header.
                actions = actions
            )
        },
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it, modifier = Modifier.padding(bottom = NavBarClearance)) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // A readable column on a large screen: centred, never wider than ContentMaxWidth.
                .centeredMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

/**
 * [items] as cards, in order: two per row on a wide window (the cards of a row share its height),
 * one per row otherwise. Meant inside a [FormScaffold] column, which spaces what it holds.
 */
@Composable
fun <T> CardGrid(items: List<T>, card: @Composable (item: T, modifier: Modifier) -> Unit) {
    if (!isWideLayout()) {
        items.forEach { card(it, Modifier) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    row.forEach { item -> card(item, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** An elevated card with an icon badge, a title, an optional subtitle and a body. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    selected: Boolean = false,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {}
) {
    // 13.2, Material 3 Expressive: a tonal container (no shadow) with the extra-large corners.
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        // [selected]: the entry whose page is open beside the list (a hub on a wide window).
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    // 13.2, Material 3 Expressive: the icon on an expressive shape in one of the device's
                    // accent tones, chosen by the title (a card keeps its colour, neighbours differ). A
                    // card given its own tint keeps it.
                    if (tint == MaterialTheme.colorScheme.primary) {
                        ExpressiveBadge(icon = icon, key = title, size = 46.dp)
                    } else {
                        ExpressiveBadge(icon = icon, key = title, size = 46.dp, tone = Tone(tint.copy(alpha = 0.16f), tint))
                    }
                    Spacer(Modifier.width(16.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                trailing()
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** [READ_ONLY_NOTICE] with a lock, at the top of a page that changes the vault, on a read-only device. */
@Composable
fun ReadOnlyNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(READ_ONLY_NOTICE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The line a page that changes the vault shows on a read-only device (12.15, S2; settled decision
 * #18: a UI that offers no write). The page stays readable; its controls are off or gone.
 */
const val READ_ONLY_NOTICE = "Read-only device: change this on your phone."

/** A short centered message for empty lists. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)
    )
}

/** A row with a title, an optional subtitle and a remove button, for the settings lists. */
@Composable
fun RemovableRow(title: String, subtitle: String?, onRemove: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // null: nothing to remove here (a read-only device).
        if (onRemove != null) TextButton(onClick = onRemove, shapes = ButtonDefaults.shapes()) { Text("Remove") }
    }
}

/** Picks one folder of the vault from a list. */
@Composable
fun FolderPickerDialog(folders: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            LazyColumn {
                items(folders) { folder ->
                    Text(folder, modifier = Modifier.fillMaxWidth().clickable { onPick(folder) }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}

/** Material 3 time picker in a dialog; returns "HH:mm". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickTimeDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val parts = initial.split(":")
    val state = rememberTimePickerState(
        initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
        initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select time", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                    TextButton(onClick = { onConfirm("%02d:%02d".format(state.hour, state.minute)) }, shapes = ButtonDefaults.shapes()) { Text("OK") }
                }
            }
        }
    }
}

/** A single date. Material's picker works in UTC milliseconds; this returns a LocalDate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDateDialog(initial: LocalDate?, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.toUtcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null, shapes = ButtonDefaults.shapes(),
                onClick = { state.selectedDateMillis?.let { onConfirm(it.utcMillisToLocalDate()) } }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

/** A date range (both ends inclusive). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDateRangeDialog(onConfirm: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null, shapes = ButtonDefaults.shapes(),
                onClick = {
                    val start = state.selectedStartDateMillis?.utcMillisToLocalDate() ?: return@TextButton
                    onConfirm(start, state.selectedEndDateMillis?.utcMillisToLocalDate() ?: start)
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    ) { DateRangePicker(state = state, modifier = Modifier.weight(1f)) }
}

/** A one-field text prompt. */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Save",
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), shapes = ButtonDefaults.shapes(), onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}

fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.utcMillisToLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
