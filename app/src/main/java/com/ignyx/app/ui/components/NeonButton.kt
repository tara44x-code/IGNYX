package com.ignyx.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignyx.app.ui.theme.Neon

enum class NeonStyle { Cyan, Magenta, Ghost, GhostMagenta }

/**
 * Glow drawn with a real blur shadow behind a rounded shape.
 * [strength] 0..1 drives both blur radius and opacity, so it can "breathe".
 */
fun Modifier.neonGlow(glowColor: Color, corner: Dp, strength: Float, filled: Boolean = true): Modifier =
    drawBehind {
        drawIntoCanvas { canvas ->
            val radius = (10f + 26f * strength).dp.toPx()
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = if (filled) glowColor.toArgb() else glowColor.copy(alpha = 0.9f).toArgb()
                style = if (filled) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5.dp.toPx()
                setShadowLayer(radius, 0f, 0f, glowColor.copy(alpha = 0.45f + 0.45f * strength).toArgb())
            }
            val r = corner.toPx()
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }

/** Diagonal light streak that crosses the button every few seconds. */
fun Modifier.lightSweep(progress: Float): Modifier = drawWithContent {
    drawContent()
    if (progress in -0.4f..1.4f) {
        val x = progress * size.width
        val band = size.width * 0.35f
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.5f),
                1f to Color.Transparent,
                start = Offset(x - band, 0f),
                end = Offset(x + band, size.height),
            )
        )
    }
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NeonStyle = NeonStyle.Cyan,
    enabled: Boolean = true,
    animated: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val corner = 18.dp
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "press",
    )

    val transition = rememberInfiniteTransition(label = "neon")
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val sweep by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3400
                -1f at 0 using LinearEasing
                -0.5f at 1900 using FastOutSlowInEasing
                1.5f at 3400
            }
        ),
        label = "sweep",
    )

    val accent = when (style) {
        NeonStyle.Cyan, NeonStyle.Ghost -> Neon.Cyan
        NeonStyle.Magenta, NeonStyle.GhostMagenta -> Neon.Magenta
    }
    val filled = style == NeonStyle.Cyan || style == NeonStyle.Magenta
    val content = when (style) {
        NeonStyle.Cyan -> Neon.OnCyan
        NeonStyle.Magenta -> Neon.OnMagenta
        else -> accent
    }
    val glow = when {
        !enabled -> 0f
        pressed -> 1f
        animated -> breathe
        else -> 0.4f
    }

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .neonGlow(accent, corner, glow, filled)
            .clip(shape)
            .then(
                if (filled) Modifier.background(accent)
                else Modifier.background(accent.copy(alpha = 0.06f)).border(1.5.dp, accent, shape)
            )
            .then(if (filled && animated && enabled) Modifier.lightSweep(sweep) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, color = content, style = MaterialTheme.typography.labelLarge)
    }
}
