package cloud.wafflecommons.pixelbrainreader.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.wrapped.Wrapped
import cloud.wafflecommons.pixelbrainreader.data.wrapped.WrappedRepository
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/** Life Stats › Your year so far (13.0, X5). */
const val WRAPPED_ROUTE = "wrapped"

/** The year read once when the page opens; nothing is kept once it closes. */
@HiltViewModel
class WrappedViewModel @Inject constructor(
    private val repository: WrappedRepository,
    private val gamificationPreferences: GamificationPreferences,
    private val appClock: AppClock
) : ViewModel() {

    data class Ready(val cards: List<Wrapped.Card>, val faces: MoodEmojiSet)

    private val _state = MutableStateFlow<Ready?>(null)
    val state: StateFlow<Ready?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cards = repository.cards(appClock.today.value)
            _state.value = Ready(cards, gamificationPreferences.moodEmojiSetFlow.first())
        }
    }
}

/**
 * PixelBrain Wrapped: one full card per fact, swiped sideways, each in its own Expressive shape
 * and color. The cards come in the order Wrapped lists them; one without data isn't there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WrappedScreen(onNavigateBack: () -> Unit, viewModel: WrappedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your year so far") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        val ready = state
        if (ready == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    Text("Reading your year…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@Scaffold
        }
        val pager = rememberPagerState { ready.cards.size }
        Column(Modifier.padding(padding).fillMaxSize().padding(bottom = NavBarClearance)) {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                pageSpacing = 16.dp,
                modifier = Modifier.weight(1f)
            ) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WrappedCard(ready.cards[page], ready.faces, page, Modifier.fillMaxSize().widthIn(max = 560.dp))
                }
            }
            PageDots(count = ready.cards.size, current = pager.currentPage)
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics { contentDescription = "Card ${current + 1} of $count" }
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
            )
        }
    }
}

/** A card's container and content colors, its shape and icon. */
private data class Look(val container: Color, val content: Color, val shape: Shape, val icon: ImageVector)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun lookOf(card: Wrapped.Card, index: Int): Look {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (index % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    return when (card) {
        is Wrapped.Card.Opening -> Look(container, content, MaterialShapes.Sunny.toShape(), Icons.Rounded.AutoAwesome)
        is Wrapped.Card.Notes -> Look(container, content, MaterialShapes.Cookie9Sided.toShape(), Icons.Rounded.Description)
        is Wrapped.Card.Tags -> Look(container, content, MaterialShapes.Clover4Leaf.toShape(), Icons.Rounded.Tag)
        is Wrapped.Card.Mood -> Look(container, content, MaterialShapes.Flower.toShape(), Icons.Rounded.Mood)
        is Wrapped.Card.Habits -> Look(container, content, MaterialShapes.Cookie12Sided.toShape(), Icons.Rounded.TaskAlt)
        is Wrapped.Card.Body -> Look(container, content, MaterialShapes.Pill.toShape(), Icons.Rounded.Bedtime)
        is Wrapped.Card.Games -> Look(container, content, MaterialShapes.Gem.toShape(), Icons.Rounded.SportsEsports)
        is Wrapped.Card.Xp -> Look(container, content, MaterialShapes.Burst.toShape(), Icons.Rounded.WorkspacePremium)
        Wrapped.Card.Closing -> Look(scheme.surfaceContainerHighest, scheme.onSurface, MaterialShapes.Cookie4Sided.toShape(), Icons.Rounded.Lock)
    }
}

@Composable
private fun WrappedCard(card: Wrapped.Card, faces: MoodEmojiSet, index: Int, modifier: Modifier) {
    val look = lookOf(card, index)
    Surface(color = look.container, contentColor = look.content, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = look.shape, color = look.content.copy(alpha = 0.12f), modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(look.icon, contentDescription = null, modifier = Modifier.size(34.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            when (card) {
                is Wrapped.Card.Opening -> Opening(card)
                is Wrapped.Card.Notes -> Notes(card)
                is Wrapped.Card.Tags -> Tags(card)
                is Wrapped.Card.Mood -> Mood(card, faces)
                is Wrapped.Card.Habits -> Habits(card)
                is Wrapped.Card.Body -> Body(card)
                is Wrapped.Card.Games -> Games(card)
                is Wrapped.Card.Xp -> Xp(card)
                Wrapped.Card.Closing -> Closing()
            }
        }
    }
}

@Composable
private fun Kicker(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun Big(text: String) = Text(text, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Ranked(rows: List<Wrapped.Count>, unit: (Int) -> String) {
    Spacer(Modifier.height(4.dp))
    rows.forEachIndexed { index, row ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(unit(row.count), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)

@Composable
private fun Opening(card: Wrapped.Card.Opening) {
    Kicker("Your ${card.year} so far")
    Big("${card.days} days")
    Line("From January 1 to ${card.to.format(DAY)}. Swipe through what this phone saw.")
}

@Composable
private fun Notes(card: Wrapped.Card.Notes) {
    Kicker("Notes")
    Big(grouped(card.created))
    Line(if (card.created == 1) "note written" else "notes written")
    if (card.edited > 0) Line("and ${grouped(card.edited)} more edited")
    if (card.topFolders.isNotEmpty()) Ranked(card.topFolders) { count -> if (count == 1) "1 note" else "${grouped(count)} notes" }
    if (card.workNotes > 0) Line("Plus ${grouped(card.workNotes)} in your work folders, not named outside Work mode.")
}

@Composable
private fun Tags(card: Wrapped.Card.Tags) {
    Kicker("Your tags")
    Line("The tags on the notes you wrote or edited")
    Ranked(card.top.map { it.copy(name = "#${it.name}") }) { count -> if (count == 1) "1 note" else "${grouped(count)} notes" }
}

@Composable
private fun Mood(card: Wrapped.Card.Mood, faces: MoodEmojiSet) {
    Kicker("Mood")
    Big(faces.forAverage(card.average))
    Line("%.1f out of 5 on average, over %d days with a mood".format(Locale.ENGLISH, card.average, card.days))
    if (card.bestMonth != null && card.bestMonthAverage != null) {
        val month = card.bestMonth.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        Line("Your best month: $month, at %.1f".format(Locale.ENGLISH, card.bestMonthAverage))
    }
}

@Composable
private fun Habits(card: Wrapped.Card.Habits) {
    Kicker("Habits")
    Big(grouped(card.checkIns))
    Line(if (card.checkIns == 1) "check-in" else "check-ins, across ${card.habitsKept} habit${if (card.habitsKept == 1) "" else "s"}")
    card.bestStreak?.takeIf { it.count > 1 }?.let { Line("Longest run: ${it.name}, ${it.count} planned days in a row") }
}

@Composable
private fun Body(card: Wrapped.Card.Body) {
    Kicker("Sleep and steps")
    val sleep = card.sleepMinutes?.roundToInt()
    val steps = card.steps?.roundToInt()
    if (sleep != null) {
        Big("${sleep / 60} h ${"%02d".format(Locale.ROOT, sleep % 60)}")
        Line("of sleep a night on average, over ${grouped(card.nights)} nights")
        if (steps != null) Line("${grouped(steps)} steps a day on average, over ${grouped(card.stepDays)} days")
    } else if (steps != null) {
        Big(grouped(steps))
        Line("steps a day on average, over ${grouped(card.stepDays)} days")
    }
    card.bestSteps?.let { (date, best) -> Line("Your best day: ${grouped(best)} steps on ${date.format(DAY)}") }
}

@Composable
private fun Games(card: Wrapped.Card.Games) {
    Kicker("Games")
    Big(hours(card.minutes))
    Line("played, in ${grouped(card.sessions)} session${if (card.sessions == 1) "" else "s"}")
    card.top?.let { Line("Most played: ${it.name}, ${hours(it.count)}") }
}

@Composable
private fun Xp(card: Wrapped.Card.Xp) {
    Kicker("Experience")
    Big("Level ${card.level}")
    Line(listOf(card.className, "${grouped(card.total)} XP in all").filter { it.isNotBlank() }.joinToString(" · "))
}

@Composable
private fun Closing() {
    Kicker("Kept on this phone")
    Line("None of this was written to your vault or sent anywhere: it was read for these cards, then let go.")
}

private fun grouped(value: Int): String = "%,d".format(Locale.ENGLISH, value)

private fun hours(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(Locale.ROOT, minutes % 60)}"
