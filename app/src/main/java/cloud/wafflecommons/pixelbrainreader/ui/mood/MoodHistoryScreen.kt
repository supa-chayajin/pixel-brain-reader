package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults // Added
import androidx.compose.material3.ExperimentalMaterial3Api // Added
import androidx.compose.ui.input.nestedscroll.nestedScroll // Added
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodHistoryScreen(
    viewModel: MoodViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { event ->
            when(event) {
                is cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Suivi d'humeur",
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Date Selector
            DateSelector(
                selectedDate = uiState.selectedDate,
                onDateSelected = { viewModel.selectDate(it) }
            )
            SummaryHeader(uiState.moodData?.summary?.mainEmoji, uiState.moodData?.summary?.averageScore)

            // Timeline
            val entries = uiState.moodData?.entries
            Crossfade(
                targetState = entries.isNullOrEmpty(),
                label = "moodTimelineState"
            ) { isEmpty ->
                if (isEmpty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucune donnée d'humeur pour ce jour.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = NavBarClearance),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(entries.orEmpty(), key = { it.time }) { entry ->
                            Box(Modifier.animateItem()) {
                                MoodTimelineItem(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        MoodCheckInSheet(onDismiss = { showSheet = false }, viewModel = viewModel)
    }
}

@Composable
fun DateSelector(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    val isToday = selectedDate >= LocalDate.now()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CortexIconButton(onClick = { onDateSelected(selectedDate.minusDays(1)) }) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Jour précédent")
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = if (isToday) "Aujourd'hui" else selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        CortexIconButton(
            onClick = { onDateSelected(selectedDate.plusDays(1)) },
            enabled = !isToday
        ) {
            Icon(
                Icons.Outlined.ChevronRight, 
                contentDescription = "Jour suivant",
                tint = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SummaryHeader(emoji: String?, avg: Double?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(text = emoji ?: "😐", fontSize = 56.sp)
            Column {
                Text(
                    text = "Résumé du jour",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (avg != null) "Score moyen : ${String.format("%.1f", avg)}/5.0" else "Aucune donnée pour l'instant",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodTimelineItem(entry: MoodEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Time Column
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(50.dp)) {
            Text(text = entry.time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }

        // Dot & Line
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        // Content
        Card(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = entry.label, fontSize = 20.sp)
                    Text(text = "Score : ${entry.score}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                
                if (entry.activities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.activities.forEach { activity ->
                            SuggestionChip(onClick = {}, label = { Text(activity) })
                        }
                    }
                }

                if (!entry.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
