package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults // Added
import androidx.compose.material3.ExperimentalMaterial3Api // Added
import androidx.compose.ui.input.nestedscroll.nestedScroll // Added
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.model.LifeStatsLogic
import cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeStatsScreen(
    viewModel: LifeStatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Character Sheet",
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                state.character?.let { char ->
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Hero Class Name
                    Text(
                        text = char.className,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Text(
                        text = "Level ${char.level}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Radar Chart
                    // Radar Chart - Responsive
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        RadarChart(
                            stats = char.stats,
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .aspectRatio(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Mood Calendar
                    cloud.wafflecommons.pixelbrainreader.ui.lifestats.MoodCalendar(
                        currentMonth = state.currentMonth,
                        onPrevMonth = { viewModel.onPrevMonth() },
                        onNextMonth = { viewModel.onNextMonth() },
                        moods = state.monthlyMoods,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}


