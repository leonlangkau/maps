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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.radar.core.AlertSettings
import au.radar.core.KindSettings
import kotlin.math.roundToInt

/**
 * The whole settings surface, over the map like everything else.
 *
 * It is organised by what the driver cares about rather than by data model:
 * the two things people install this for sit at the top, cameras next, road
 * conditions after that, and the noisy stuff they will probably want quiet is
 * grouped under a heading that says so.
 */
/** One kind row, with the heading that precedes it when the group changes. */
private data class KindRowSpec(
    val kind: String,
    val title: String,
    val groupHeading: String?,
)

private val kindRows: List<KindRowSpec> = buildList {
    var previousGroup: String? = null
    for (entry in AlertSettings.editableKinds) {
        add(
            KindRowSpec(
                kind = entry.kind,
                title = entry.title,
                groupHeading = entry.group.takeIf { it != previousGroup },
            ),
        )
        previousGroup = entry.group
    }
}

@Composable
fun SettingsScreen(
    settings: AlertSettings,
    cameraCount: Int,
    connected: Boolean,
    onChange: (AlertSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    fun patch(kind: String, block: (KindSettings) -> KindSettings) {
        val current = settings.forKind(kind)
        onChange(settings.copy(kinds = settings.kinds + (kind to block(current))))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onDismiss),
    ) {
        Box(Modifier.padding(10.dp).clickable(enabled = false, onClick = {})) {
            GlassPanel(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28.dp),
                strong = true,
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Alerts",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            GlassIconButton(
                                onClick = onDismiss,
                                contentDescription = "Close settings",
                                size = 40.dp,
                            ) { Icon(Icons.Filled.Close, contentDescription = null) }
                        }
                    }

                    item { SectionHeader("Overall") }

                    item {
                        ToggleRow(
                            title = "Spoken warnings",
                            subtitle = "Turn off for tones only",
                            checked = !settings.muted,
                            onCheckedChange = { onChange(settings.copy(muted = !it)) },
                        )
                    }
                    item {
                        ToggleRow(
                            title = "Flash the screen",
                            subtitle = "Pulses the edges — keeps working when muted",
                            checked = settings.flashEnabled,
                            onCheckedChange = { onChange(settings.copy(flashEnabled = it)) },
                        )
                    }
                    item {
                        SliderRow(
                            title = "Extra warning on my own road",
                            value = settings.sameRoadLeadMultiplier.toFloat(),
                            range = 1f..2.5f,
                            format = { "${(it * 100).roundToInt()}%" },
                            explanation = "Something on the road you are already on is " +
                                "warned about this much earlier than something merely in front.",
                            onChange = { onChange(settings.copy(sameRoadLeadMultiplier = it.toDouble())) },
                        )
                    }
                    item {
                        SliderRow(
                            title = "Stay quiet below",
                            value = settings.minSpeedKmh.toFloat(),
                            range = 0f..60f,
                            format = { if (it < 1) "Off" else "${it.roundToInt()} km/h" },
                            explanation = "No warnings at all under this speed.",
                            onChange = { onChange(settings.copy(minSpeedKmh = it.toDouble())) },
                        )
                    }

                    // Group headings are decided up front rather than tracked
                    // while composing: a lazy list builds items out of order and
                    // reuses them, so any running variable here would be wrong.
                    items(kindRows) { row ->
                        Column {
                            row.groupHeading?.let { SectionHeader(it) }
                            KindRow(
                                title = row.title,
                                kind = settings.forKind(row.kind),
                                onChange = { updated -> patch(row.kind) { updated } },
                            )
                        }
                    }

                    item { SectionHeader("This phone") }
                    item { InfoRow("Cameras stored", "$cameraCount") }
                    item { InfoRow("Live hazards", if (connected) "Connected" else "Offline") }

                    item {
                        Text(
                            "Camera locations come from state government open data. Live " +
                                "hazards come from the road authorities. This app never detects " +
                                "or interferes with any enforcement signal.",
                            color = Color.White.copy(0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(0.42f),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

/**
 * One threat kind. Three controls, because those are the three questions a
 * driver actually has: do I want it, how loud, and how early.
 */
@Composable
private fun KindRow(
    title: String,
    kind: KindSettings,
    onChange: (KindSettings) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = if (kind.enabled) Color.White else Color.White.copy(0.4f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = kind.enabled,
                onCheckedChange = { onChange(kind.copy(enabled = it)) },
                colors = switchColours(),
            )
        }

        if (kind.enabled) {
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionChip(
                    label = "Voice",
                    icon = { Icon(Icons.Filled.VolumeUp, null, Modifier.size(14.dp)) },
                    selected = kind.voice,
                    onClick = { onChange(kind.copy(voice = !kind.voice)) },
                )
                OptionChip(
                    label = "Flash",
                    icon = { Icon(Icons.Filled.Bolt, null, Modifier.size(14.dp)) },
                    selected = kind.flash,
                    onClick = { onChange(kind.copy(flash = !kind.flash)) },
                )
            }

            SliderRow(
                title = "Warn me",
                value = kind.leadSeconds.toFloat(),
                range = 5f..90f,
                format = { "${it.roundToInt()} s ahead" },
                explanation = null,
                onChange = { onChange(kind.copy(leadSeconds = it.toDouble())) },
            )
            SliderRow(
                title = "Or anywhere within",
                value = kind.radiusM.toFloat(),
                range = 100f..1500f,
                format = { "${(it / 50).roundToInt() * 50} m" },
                explanation = null,
                onChange = { onChange(kind.copy(radiusM = it.toDouble())) },
            )
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.height(30.dp).clickable(onClick = onClick),
        shape = Glass.CapsuleShape,
        tint = if (selected) Color(0xFF2F80ED) else Color.Unspecified,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 12.sp,
                color = if (selected) Color.White else Color.White.copy(0.55f),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = switchColours())
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    explanation: String?,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = Color.White.copy(0.72f), fontSize = 13.sp)
            Text(
                format(value),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4AA8FF),
                activeTrackColor = Color(0xFF2F80ED),
                inactiveTrackColor = Color.White.copy(0.18f),
            ),
        )
        if (explanation != null) {
            Text(explanation, color = Color.White.copy(0.42f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(0.7f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun switchColours() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Color(0xFF2F80ED),
    uncheckedThumbColor = Color.White.copy(0.7f),
    uncheckedTrackColor = Color.White.copy(0.14f),
    uncheckedBorderColor = Color.White.copy(0.22f),
)
