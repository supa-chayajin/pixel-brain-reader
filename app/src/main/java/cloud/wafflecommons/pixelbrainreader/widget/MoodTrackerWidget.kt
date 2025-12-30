package cloud.wafflecommons.pixelbrainreader.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cloud.wafflecommons.pixelbrainreader.MainActivity
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.currentState
import androidx.glance.unit.ColorProvider

class MoodTrackerWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(100.dp, 100.dp), // SMALL
            DpSize(150.dp, 100.dp), // MEDIUM
            DpSize(250.dp, 200.dp)  // LARGE
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    @Composable
    private fun WidgetContent() {
        val size = LocalSize.current
        val prefs = currentState<Preferences>()
        val lastEmoji = prefs[LAST_EMOJI_KEY] ?: ""

        Scaffold(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface),
            titleBar = { /* Optional if needed */ },
            content = {
                Column(
                    modifier = GlanceModifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        size.width >= 250.dp && size.height >= 200.dp -> LargeLayout(lastEmoji)
                        size.width >= 150.dp -> MediumLayout(lastEmoji)
                        else -> SmallLayout()
                    }
                }
            }
        )
    }

    @Composable
    private fun SmallLayout() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🧠",
                    style = TextStyle(fontSize = 32.sp)
                )
                Text(
                    text = "Check In",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }

    @Composable
    private fun MediumLayout(lastEmoji: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "How are you?",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MoodIcons(lastEmoji)
            }
        }
    }

    @Composable
    private fun LargeLayout(lastEmoji: String) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Daily Tracker",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(16.dp))
            Text(
                text = "How are you feeling?",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp
                )
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MoodIcons(lastEmoji)
            }
            Spacer(modifier = GlanceModifier.height(16.dp))
            Text(
                text = "Recent Activities",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            )
            Row(modifier = GlanceModifier.padding(top = 4.dp)) {
                ActivityChip("Coding")
                Spacer(modifier = GlanceModifier.width(8.dp))
                ActivityChip("Gaming")
                Spacer(modifier = GlanceModifier.width(8.dp))
                ActivityChip("Reading")
            }
        }
    }

    @Composable
    private fun MoodIcons(lastEmoji: String) {
        val moods = listOf("😫", "😞", "😐", "🙂", "🤩")
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            moods.forEachIndexed { index, emoji ->
                val score = index + 1
                val isSelected = emoji == lastEmoji
                
                Box(
                    modifier = GlanceModifier
                        .padding(4.dp)
                        .then(
                            if (isSelected) {
                                GlanceModifier.background(GlanceTheme.colors.primaryContainer)
                            } else {
                                GlanceModifier
                            }
                        )
                        .padding(4.dp)
                        .clickable(
                            actionRunCallback<MoodWidgetActionCallback>(
                                actionParametersOf(MoodWidgetActionCallback.SCORE_KEY to score)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        style = TextStyle(fontSize = 24.sp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ActivityChip(label: String) {
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 10.sp
                )
            )
        }
    }

    companion object {
        val LAST_EMOJI_KEY = stringPreferencesKey("last_emoji")
        val LAST_UPDATE_KEY = stringPreferencesKey("last_update_time")
    }
}
