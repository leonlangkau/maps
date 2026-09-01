package au.radar.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * The only screen that matters while driving: map, current warning, and a way to
 * report what you just passed without taking your eyes off the road for more
 * than a moment.
 */
@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    hasLocationPermission: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showReportSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MapScreen(
            styleUrl = viewModel.styleUrl(),
            threats = state.threats,
            hasLocationPermission = hasLocationPermission,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            state.lastAnnouncement?.let { announcement ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            announcement.spokenText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (!state.connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Offline — cameras only",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                SpeedReadout(state.speedKmh)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = { viewModel.toggleMute() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (state.muted) "Unmute warnings" else "Mute warnings",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    FloatingActionButton(
                        onClick = { showReportSheet = true },
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Report something ahead")
                    }
                }
            }
        }

        if (showReportSheet) {
            ReportSheet(
                onDismiss = { showReportSheet = false },
                onPick = { kind ->
                    showReportSheet = false
                    viewModel.report(kind)
                },
            )
        }
    }
}

@Composable
private fun SpeedReadout(speedKmh: Double) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${speedKmh.roundToInt()}",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("km/h", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Deliberately six large buttons and nothing else. Anything that needs reading
 * does not belong on a screen used at 100 km/h.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun ReportSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    data class Option(val kind: String, val label: String)

    val options = listOf(
        Option("police", "Police"),
        Option("mobile_camera", "Mobile camera"),
        Option("crash", "Crash"),
        Option("hazard", "Hazard"),
        Option("object_on_road", "Object on road"),
        Option("stopped_vehicle", "Stopped vehicle"),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "What did you just pass?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(options) { option ->
                Card(
                    onClick = { onPick(option.kind) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(6.dp).height(84.dp).fillMaxWidth(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
