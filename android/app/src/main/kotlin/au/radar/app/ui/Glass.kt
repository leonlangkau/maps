package au.radar.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass for Android, painted rather than blurred.
 *
 * The obvious implementation — a real backdrop blur — is not available here,
 * for a reason worth writing down because it looks like a shortcut otherwise.
 *
 * MapLibre draws into a SurfaceView, which the system composites on its own
 * layer *underneath* the app window. The Compose hierarchy never holds those
 * pixels, so the backdrop-blur libraries (Haze, Cloudy, Imla), which work by
 * capturing the Compose root into a texture, capture a hole where the map is.
 * They do not merely run slowly over a map; they cannot see it.
 *
 * The one way to make the map blurrable is to run MapLibre in texture mode,
 * which puts it in the view hierarchy at a real cost to map rendering — an
 * extra full-screen copy every frame, on the one surface that redraws
 * continuously while driving. Paying that so a panel edge can be frosted is a
 * bad trade in an app whose whole job is to keep working at 100 km/h.
 *
 * So the glass here is painted: a translucent base, a vertical gradient, a
 * specular rim that is bright at the top-left and catches again at the bottom,
 * and an ambient shadow. Over a dark map it reads as glass, and it costs a
 * handful of draw operations with no readback, no extra render pass, and no
 * per-frame texture copy. On a moving map it holds frame rate on hardware
 * where a real blur would not.
 */
object Glass {

    /** The tint under the gradient. Dark, because the map beneath it is dark. */
    private val Base = Color(0xB315181E)
    private val BaseThin = Color(0x8C15181E)

    /**
     * Real glass is brighter where light enters it and dimmer through its
     * body, so the fill runs light at the top to dark at the bottom.
     */
    private fun fillBrush(strong: Boolean) = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (strong) 0.17f else 0.11f),
            Color.White.copy(alpha = if (strong) 0.06f else 0.04f),
            Color.White.copy(alpha = if (strong) 0.09f else 0.06f),
        ),
    )

    /**
     * The rim is what sells it. A real pane catches light hardest on the edge
     * nearest the source and again, faintly, on the far edge where light exits.
     */
    private val rimBrush = Brush.linearGradient(
        0.0f to Color.White.copy(alpha = 0.42f),
        0.35f to Color.White.copy(alpha = 0.10f),
        0.72f to Color.White.copy(alpha = 0.04f),
        1.0f to Color.White.copy(alpha = 0.20f),
    )

    val PanelShape = RoundedCornerShape(24.dp)
    val CapsuleShape = RoundedCornerShape(percent = 50)
    val CardShape = RoundedCornerShape(20.dp)

    /**
     * The glass treatment itself.
     *
     * @param tint an accent pulled through the glass, for severity colouring.
     * @param strong brighter fill, for surfaces that must stay readable over a
     *   busy part of the map.
     */
    fun Modifier.surface(
        shape: Shape = PanelShape,
        tint: Color = Color.Unspecified,
        strong: Boolean = false,
        elevation: Dp = 16.dp,
    ): Modifier = this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(if (strong) Base else BaseThin, shape)
        .then(
            if (tint != Color.Unspecified) {
                Modifier.background(tint.copy(alpha = 0.22f), shape)
            } else {
                Modifier
            },
        )
        .background(fillBrush(strong), shape)
        .border(BorderStroke(1.dp, rimBrush), shape)
}

/** A glass panel. Content goes inside; the treatment is handled here. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Glass.PanelShape,
    tint: Color = Color.Unspecified,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    with(Glass) {
        Box(modifier.surface(shape = shape, tint = tint, strong = strong), content = content)
    }
}

/**
 * A round glass button.
 *
 * The press response is a spring-damped scale rather than a ripple. A ripple
 * draws an expanding circle over the surface, which on glass reads as a smudge;
 * the scale reads as the pane being pushed, which is what Liquid Glass does on
 * iOS and costs nothing but a transform.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
    tint: Color = Color.Unspecified,
    icon: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "glass-press",
    )

    with(Glass) {
        Box(
            modifier
                .scale(scale)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    onClick = onClick,
                )
                .surface(shape = CircleShape, tint = tint, strong = true)
                .size(size),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}
