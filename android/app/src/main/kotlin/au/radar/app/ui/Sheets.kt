package au.radar.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.radar.core.AlertEngine
import au.radar.core.PlaceResult
import au.radar.core.RouteOption
import au.radar.core.RouteTracker
import au.radar.core.Threat

/**
 * Every sheet in the app sits on this: a dimming scrim that dismisses on tap,
 * and a glass panel anchored to the bottom within thumb reach.
 *
 * The scrim is a flat colour rather than a blur, for the reason in Glass.kt —
 * the map underneath is a SurfaceView and cannot be sampled by the Compose
 * layer at any price worth paying.
 */
@Composable
private fun GlassSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Swallow taps on the panel itself so they do not reach the scrim.
        Box(Modifier.clickable(enabled = false, onClick = {})) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(28.dp),
                strong = true,
            ) {
                Column(Modifier.padding(20.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

// MARK: - Search

@Composable
fun SearchSheet(
    query: String,
    results: List<PlaceResult>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (PlaceResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    GlassSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(0.7f))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 19.sp),
                cursorBrush = SolidColor(Color(0xFF4AA8FF)),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Search an address", color = Color.White.copy(0.45f), fontSize = 19.sp)
                    }
                    inner()
                },
            )
            if (searching) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(0.7f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (results.isEmpty() && query.length >= 3 && !searching) {
            Text("Nothing found", color = Color.White.copy(0.5f), fontSize = 15.sp)
        }

        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(results) { place ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(place) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(place.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    place.address?.let {
                        Text(it, color = Color.White.copy(0.55f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// MARK: - Route preview

@Composable
fun RoutePreviewSheet(
    destination: PlaceResult?,
    route: RouteOption?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    GlassSheet(onDismiss = onCancel) {
        SheetTitle(destination?.name ?: "Building a route…")
        destination?.address?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color.White.copy(0.55f), fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))

        if (route == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Working out the way there", color = Color.White.copy(0.7f), fontSize = 15.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    RouteTracker.formatDuration(route.durationS),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    RouteTracker.formatDistance(route.distanceM),
                    fontSize = 17.sp,
                    color = Color.White.copy(0.65f),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassActionButton(
                label = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            GlassActionButton(
                label = "Start",
                onClick = onStart,
                modifier = Modifier.weight(1f),
                tint = Color(0xFF2F80ED),
                enabled = route != null,
            )
        }
    }
}

// MARK: - Reporting

/**
 * Deliberately six large buttons and nothing else. Anything that needs reading
 * does not belong on a screen used at 100 km/h.
 */
@Composable
fun ReportSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    data class Option(val kind: String, val label: String)

    val options = listOf(
        Option("police", "Police"),
        Option("mobile_camera", "Mobile camera"),
        Option("crash", "Crash"),
        Option("hazard", "Hazard"),
        Option("object_on_road", "Object on road"),
        Option("stopped_vehicle", "Stopped vehicle"),
    )

    GlassSheet(onDismiss = onDismiss) {
        SheetTitle("What did you just pass?")
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 260.dp),
        ) {
            items(options) { option ->
                GlassPanel(
                    modifier = Modifier
                        .height(88.dp)
                        .fillMaxWidth()
                        .clickable { onPick(option.kind) },
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        option.label,
                        Modifier.align(Alignment.Center).padding(8.dp),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// MARK: - Threat detail

@Composable
fun ThreatSheet(
    threat: Threat,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Only community reports can be voted on. A government feed is not
    // something a driver confirms or denies.
    val isCommunityReport = threat.id.startsWith("community:")

    GlassSheet(onDismiss = onDismiss) {
        SheetTitle(AlertEngine.label(threat))

        Spacer(Modifier.height(8.dp))
        Text(
            when {
                threat.isCamera -> "From published camera data"
                isCommunityReport -> "Reported by a driver"
                else -> "From a live traffic feed"
            },
            color = Color.White.copy(0.6f),
            fontSize = 14.sp,
        )

        AlertEngine.postedLimit(threat)?.let { limit ->
            Spacer(Modifier.height(6.dp))
            Text("Posted limit $limit km/h", color = Color.White.copy(0.6f), fontSize = 14.sp)
        }

        if (isCommunityReport) {
            Spacer(Modifier.height(20.dp))
            Text("Is it still there?", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassActionButton(
                    label = "Still there",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    tint = Color(0xFF27AE60),
                    icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                )
                GlassActionButton(
                    label = "Gone",
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    tint = Color(0xFFEB5757),
                    icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                )
            }
        }
    }
}

// MARK: - Shared

@Composable
private fun GlassActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    GlassPanel(
        modifier = modifier
            .height(54.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = Glass.CapsuleShape,
        tint = tint,
        strong = true,
    ) {
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                color = if (enabled) Color.White else Color.White.copy(0.4f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
