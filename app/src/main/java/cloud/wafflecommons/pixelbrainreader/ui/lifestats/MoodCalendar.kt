package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoodCalendar(
    currentMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    moods: Map<LocalDate, String>, // Date -> Emoji
    modifier: Modifier = Modifier
) {
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayOfMonth = currentMonth.atDay(1)
    val startDayOfWeek = firstDayOfMonth.dayOfWeek.value // 1 (Mon) - 7 (Sun)
    
    // Create list of days to display (including empty slots for start padding)
    // Grid matches standard calendar: Mon, Tue...
    val totalSlots = (startDayOfWeek - 1) + daysInMonth
    val calendarItems = (1..totalSlots).map { i ->
        if (i < startDayOfWeek) {
            null // Empty padding slot
        } else {
            val day = i - startDayOfWeek + 1
            currentMonth.atDay(day)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CortexIconButton(onClick = onPrevMonth) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Month")
                }

                // Read locale observably (via LocalConfiguration) so the month name
                // recomposes on a locale change — and without Locale.getDefault(), which
                // the NonObservableLocale lint rightly flags in composables.
                val monthLocale = LocalConfiguration.current.locales[0]!!
                Text(
                    text = "${currentMonth.month.getDisplayName(TextStyle.FULL, monthLocale)} ${currentMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Disable forward nav if current month is >= now
                val now = YearMonth.now()
                val canGoForward = currentMonth.isBefore(now)
                
                CortexIconButton(
                    onClick = onNextMonth,
                    enabled = canGoForward
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Month",
                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Day Labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val weekDays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
                weekDays.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))

            // Cheap draw-on: fade + subtle scale the whole grid in on first composition.
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appeared = true }
            val gridAlpha by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "gridAlpha"
            )

            // Custom Grid for Scroll Compatibility
            val rows = calendarItems.chunked(7)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = gridAlpha
                        val s = 0.96f + 0.04f * gridAlpha
                        scaleX = s
                        scaleY = s
                    }
            ) {
                rows.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        week.forEach { date ->
                             Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (date != null) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = date.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        
                                        val emoji = moods[date]
                                        if (emoji != null) {
                                            Text(
                                                text = emoji,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontSize = 20.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Handle incomplete last week
                        if (week.size < 7) {
                            Spacer(modifier = Modifier.weight((7 - week.size).toFloat()))
                        }
                    }
                }
            }
        }
    }
}
