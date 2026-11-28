@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings › RPG rules (13.4, S-f; route `gamification_settings`): the health goals that decide
 * when a day earns its health XP, and the tag rules that send a mood tag's XP to one of the hero's
 * stats, each stat by its name. The sliders move on round steps (1,000 steps, 30 minutes). This
 * device's own settings: usable on a read-only device. The mood faces are on the Moods page.
 */
@Composable
fun GamificationSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: GamificationSettingsViewModel = hiltViewModel()
) {
    val stepTarget by viewModel.stepTarget.collectAsStateWithLifecycle()
    val sleepMinMinutes by viewModel.sleepMinMinutes.collectAsStateWithLifecycle()
    val tagMappings by viewModel.tagToStatMapping.collectAsStateWithLifecycle()

    var newTagText by rememberSaveable { mutableStateOf("") }
    var selectedAttribute by rememberSaveable { mutableStateOf(Attribute.VIG) }
    val view = LocalView.current

    SettingsPageScaffold(page = SettingsPage.RPG, onBack = onNavigateBack) {
        SettingsGroup {
            row {
                SettingsNote(
                    "Your health goals decide when a day earns its health XP. Each tag rule sends a mood tag's XP to " +
                        "one of your hero's stats."
                )
            }
        }

        SettingsPageSection("Health goals") {
            row {
                SettingsRow(
                    title = "Step goal",
                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                    anchor = SettingsAnchors.GOALS,
                    trailing = { GoalValue(stepsLabel(stepTarget)) },
                    below = {
                        Slider(
                            value = stepTarget.toFloat(),
                            onValueChange = { raw ->
                                val steps = ((raw / STEP_GOAL_STEP).roundToInt() * STEP_GOAL_STEP).coerceIn(STEP_GOAL_MIN, STEP_GOAL_MAX)
                                if (steps != stepTarget) {
                                    view.performHapticTick()
                                    viewModel.updateStepTarget(steps)
                                }
                            },
                            valueRange = STEP_GOAL_MIN.toFloat()..STEP_GOAL_MAX.toFloat(),
                            // 1,000 to 20,000 by 1,000: 18 stops between the two ends.
                            steps = (STEP_GOAL_MAX - STEP_GOAL_MIN) / STEP_GOAL_STEP - 1
                        )
                    }
                )
            }
            row {
                SettingsRow(
                    title = "Sleep goal",
                    icon = Icons.Rounded.Bedtime,
                    trailing = { GoalValue(sleepLabel(sleepMinMinutes)) },
                    below = {
                        Slider(
                            value = sleepMinMinutes.toFloat(),
                            onValueChange = { raw ->
                                val minutes = ((raw / SLEEP_GOAL_STEP).roundToInt() * SLEEP_GOAL_STEP).coerceIn(SLEEP_GOAL_MIN, SLEEP_GOAL_MAX)
                                if (minutes != sleepMinMinutes) {
                                    view.performHapticTick()
                                    viewModel.updateSleepMinMinutes(minutes)
                                }
                            },
                            valueRange = SLEEP_GOAL_MIN.toFloat()..SLEEP_GOAL_MAX.toFloat(),
                            // 3 h to 10 h by 30 min: 13 stops between the two ends.
                            steps = (SLEEP_GOAL_MAX - SLEEP_GOAL_MIN) / SLEEP_GOAL_STEP - 1
                        )
                    }
                )
            }
        }

        SettingsPageSection("Tag rules") {
            if (tagMappings.isEmpty()) {
                row { SettingsNote("No tag rule yet: add one below.", modifier = Modifier.settingsAnchor(SettingsAnchors.TAG_RULES)) }
            }
            tagMappings.entries.forEachIndexed { index, (tag, attribute) ->
                row {
                    SettingsRow(
                        title = "$tag → ${attribute.statName()}",
                        anchor = if (index == 0) SettingsAnchors.TAG_RULES else null,
                        trailing = {
                            TextButton(
                                onClick = {
                                    view.performHapticClick()
                                    viewModel.removeTagMapping(tag)
                                },
                                shapes = ButtonDefaults.shapes()
                            ) { Text("Remove") }
                        }
                    )
                }
            }
            row {
                AddRuleRow(
                    tag = newTagText,
                    onTagChange = { typed -> newTagText = if (typed.isEmpty() || typed.startsWith("#")) typed else "#$typed" },
                    stat = selectedAttribute,
                    onStat = { selectedAttribute = it },
                    onAdd = {
                        if (newTagText.trim().length > 1) {
                            view.performHapticSuccess()
                            viewModel.addTagMapping(newTagText.trim(), selectedAttribute)
                            newTagText = ""
                        }
                    }
                )
            }
        }
    }
}

/** A stat's name, as the hero's sheet shows it (13.4: rules read "#tag → Vigor", never "VIG"). */
internal fun Attribute.statName(): String = when (this) {
    Attribute.VIG -> "Vigor"
    Attribute.MND -> "Focus"
    Attribute.END -> "Endurance"
    Attribute.SOC -> "Social"
    Attribute.CRE -> "Creativity"
    Attribute.INT -> "Intellect"
    Attribute.FTH -> "Spirit"
}

private const val STEP_GOAL_MIN = 1_000
private const val STEP_GOAL_MAX = 20_000
private const val STEP_GOAL_STEP = 1_000
private const val SLEEP_GOAL_MIN = 180
private const val SLEEP_GOAL_MAX = 600
private const val SLEEP_GOAL_STEP = 30

private fun stepsLabel(steps: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(steps) + " steps"

private fun sleepLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest > 0) "$hours h $rest min" else "$hours h"
}

/** A goal's value at the end of its row. */
@Composable
private fun GoalValue(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** The add row of the tag rules: a tag, then its stat by name, then Add. */
@Composable
private fun AddRuleRow(tag: String, onTagChange: (String) -> Unit, stat: Attribute, onStat: (Attribute) -> Unit, onAdd: () -> Unit) {
    Surface(
        shape = LocalSegmentPosition.current.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(SettingsRowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = tag,
                onValueChange = onTagChange,
                label = { Text("Tag (e.g. #sport)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatMenu(selected = stat, onSelect = onStat, modifier = Modifier.weight(1f))
                FilledIconButton(onClick = onAdd, enabled = tag.trim().length > 1, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add the rule")
                }
            }
        }
    }
}

/** The stat a rule sends its tag's XP to, by name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatMenu(selected: Attribute, onSelect: (Attribute) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.statName(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Stat") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Attribute.entries.forEach { attribute ->
                DropdownMenuItem(
                    text = { Text(attribute.statName()) },
                    onClick = {
                        onSelect(attribute)
                        expanded = false
                    }
                )
            }
        }
    }
}
