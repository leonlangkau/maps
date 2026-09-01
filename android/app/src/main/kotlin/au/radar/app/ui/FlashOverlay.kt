package au.radar.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Pulses the screen edges for warnings worth not missing.
 *
 * Two deliberate choices. It flashes the *edges* rather than the whole screen,
 * because a full-screen white-out at night ruins your night vision and hides the
 * map at the exact moment you want to look at it. And it keeps going when the
 * app is muted, since someone driving with the radio up is precisely the person
 * who needs the visual.
 *
 * [flashAt] is a timestamp rather than a boolean so two warnings in quick
 * succession both produce a pulse.
 */
@Composable
fun FlashOverlay(flashAt: Long, colour: Color = Color(0xFFEB5757)) {
    val alpha = remember { Animatable(0f) }
    val context = LocalContext.current

    // Someone who has turned animations off system-wide is telling us they do
    // not want things moving; hold one steady glow instead of pulsing.
    val reduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        if (reduceMotion) {
            alpha.snapTo(0.5f)
            alpha.animateTo(0f, tween(durationMillis = 1_200))
            return@LaunchedEffect
        }
        repeat(3) {
            alpha.animateTo(0.62f, tween(durationMillis = 110))
            alpha.animateTo(0f, tween(durationMillis = 190))
        }
    }

    if (alpha.value > 0.001f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // A vignette: strong at the edges, clear through the middle
                    // where the road and the route line are.
                    Brush.radialGradient(
                        0.45f to Color.Transparent,
                        1.0f to colour.copy(alpha = alpha.value),
                    ),
                ),
        )
    }
}
