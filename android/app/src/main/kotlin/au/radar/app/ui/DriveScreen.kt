package au.radar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.radar.app.DriveViewModel
import au.radar.app.MapScreen
import au.radar.app.NavMode
import au.radar.core.AlertEngine
import au.radar.core.AnnouncementLevel
import au.radar.core.RouteTracker
import au.radar.core.SpeedReading

/**
 * The only screen that matters while driving: the map, whatever is being
 * warned about, and a way to report what you just passed without looking down
 * for more than a moment.
 *
 * Everything floats over a full-bleed map as glass. Nothing is more than one
 * tap deep, and every control is at least 56dp because it will be used by
 * someone whose attention is on the road.
 */
@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    hasLocationPermission: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showReport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MapScreen(
            styleUrl = viewModel.styleUrl(),
            threats = state.threats,
            routeGeometry = state.routeGeometry,
            route = state.route,
            followUser = state.navMode != NavMode.PREVIEWING,
            hasLocationPermission = hasLocationPermission,
            onThreatTapped = { id ->
                viewModel.selectThreat(state.threats.firstOrNull { it.id == id })
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            // Top: either the live turn instruction, or the search pill.
            if (state.navMode == NavMode.NAVIGATING && state.progress != null) {
                ManeuverCard(
                    instruction = state.progress?.currentStep?.instruction.orEmpty(),
                    distance = RouteTracker.formatDistance(
                        state.progress?.distanceToManeuverM ?: 0.0,
                    ),
                )
            } else if (state.navMode == NavMode.IDLE) {
                SearchPill(onClick = viewModel::openSearch)
            }

            AnimatedVisibility(
                visible = state.lastAnnouncement != null,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    state.lastAnnouncement?.let { WarningBanner(it.spokenText, it.level) }
                }
            }

            if (!state.connected) {
                Spacer(Modifier.height(10.dp))
                StatusChip("Offline — cameras only")
            }
            state.toast?.let { message ->
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(2_500)
                    viewModel.dismissToast()
                }
                Spacer(Modifier.height(10.dp))
                StatusChip(message)
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                SpeedReadout(state.speed, state.postedLimit)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassIconButton(
                        onClick = { showSettings = true },
                        contentDescription = "Settings",
                        size = 50.dp,
                    ) { Icon(Icons.Filled.Settings, contentDescription = null) }

                    Spacer(Modifier.height(12.dp))

                    GlassIconButton(
                        onClick = viewModel::toggleMute,
                        contentDescription = if (state.muted) "Unmute warnings" else "Mute warnings",
                        size = 56.dp,
                    ) {
                        Icon(
                            if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = null,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    GlassIconButton(
                        onClick = { showReport = true },
                        contentDescription = "Report something ahead",
                        size = 68.dp,
                        tint = Color(0xFF2F80ED),
                    ) { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(30.dp)) }
                }
            }

            if (state.navMode == NavMode.NAVIGATING) {
                Spacer(Modifier.height(14.dp))
                EtaStrip(
                    remainingS = state.progress?.durationRemainingS ?: 0.0,
                    remainingM = state.progress?.distanceRemainingM ?: 0.0,
                    onEnd = viewModel::endNavigation,
                )
            }
        }

        // Sheets. Only one can be up at a time, by construction.
        if (state.navMode == NavMode.SEARCHING) {
            SearchSheet(
                query = state.searchQuery,
                results = state.searchResults,
                searching = state.searching,
                onQueryChange = viewModel::onSearchQueryChanged,
                onPick = viewModel::pickDestination,
                onDismiss = viewModel::closeSearch,
            )
        }

        if (state.navMode == NavMode.PREVIEWING) {
            RoutePreviewSheet(
                destination = state.destination,
                choices = state.routeChoices,
                selected = state.selectedRoute,
                onSelect = viewModel::selectRoute,
                onStart = viewModel::startNavigation,
                onCancel = viewModel::endNavigation,
            )
        }

        state.selectedThreat?.let { threat ->
            ThreatSheet(
                threat = threat,
                onConfirm = { viewModel.vote(threat, true) },
                onDeny = { viewModel.vote(threat, false) },
                onDismiss = { viewModel.selectThreat(null) },
            )
        }

        if (showReport) {
            ReportSheet(
                onPick = { kind ->
                    showReport = false
                    viewModel.report(kind)
                },
                onDismiss = { showReport = false },
            )
        }

        if (showSettings) {
            SettingsScreen(
                settings = state.settings,
                cameraCount = state.cameras.size,
                connected = state.connected,
                onChange = viewModel::updateSettings,
                onDismiss = { showSettings = false },
            )
        }

        // Above every sheet: a warning worth flashing for is worth seeing over
        // whatever else is open.
        FlashOverlay(flashAt = state.flashAt)
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = Glass.CapsuleShape,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(0.75f))
            Spacer(Modifier.width(12.dp))
            Text(
                "Where to?",
                color = Color.White.copy(0.75f),
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String, level: AnnouncementLevel) {
    val tint = if (level == AnnouncementLevel.SPEAK) Color(0xFFEB5757) else Color(0xFFF2C94C)
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = Glass.CardShape,
        tint = tint,
        strong = true,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun ManeuverCard(instruction: String, distance: String) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = Glass.CardShape,
        strong = true,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                distance,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4AA8FF),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                instruction,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    GlassPanel(shape = Glass.CapsuleShape) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 13.sp,
            color = Color.White.copy(0.85f),
        )
    }
}

/**
 * Speed, and the posted limit where a camera told us one.
 *
 * This one thing is deliberately not glass. Glass is lovely for chrome you
 * glance past, and wrong for the number you check most often: it borrows
 * whatever the map is doing underneath, so the same digits sit on dark asphalt
 * one second and pale parkland the next. A solid disc with a red ring reads
 * identically over anything, at a glance, in sunlight — which is the whole job.
 *
 * The ring is red and circular because that is the shape an Australian driver
 * already reads as "a speed number". The posted limit sits beside it as a
 * plainly different object so the two can never be confused for one another.
 */
@Composable
private fun SpeedReadout(speed: SpeedReading, postedLimit: Int?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .size(88.dp)
                // Untrusted means we are coasting on a stale fix. Dimming says
                // so without hiding the last number we were sure of.
                .alpha(if (speed.trusted) 1f else 0.5f)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFFD42A2A)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF6F7F9)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${speed.displayKmh}",
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10141B),
                        lineHeight = 28.sp,
                    )
                    Text(
                        "km/h",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6B7280),
                    )
                }
            }
        }

        if (postedLimit != null) {
            Spacer(Modifier.width(10.dp))
            GlassPanel(shape = Glass.CapsuleShape, strong = true) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "LIMIT",
                        fontSize = 8.sp,
                        letterSpacing = 1.2.sp,
                        color = Color.White.copy(0.55f),
                    )
                    Text(
                        "$postedLimit",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun EtaStrip(remainingS: Double, remainingM: Double, onEnd: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), shape = Glass.CardShape, strong = true) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    RouteTracker.formatDuration(remainingS),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    RouteTracker.formatDistance(remainingM),
                    fontSize = 13.sp,
                    color = Color.White.copy(0.65f),
                )
            }
            GlassIconButton(
                onClick = onEnd,
                contentDescription = "End navigation",
                size = 48.dp,
                tint = Color(0xFFEB5757),
            ) { Icon(Icons.Filled.Close, contentDescription = null) }
        }
    }
}
