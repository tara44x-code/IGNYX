package com.ignyx.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ignyx.app.ui.theme.ChakraPetch
import com.ignyx.app.ui.theme.Neon

/** The IGNYX mark: a neon USB stick with a lightning bolt, drawn on a 120-unit grid. */
@Composable
fun IgnyxMark(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 120f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)

        drawRoundRect(
            color = Neon.SurfaceLow, topLeft = o(4f, 4f), size = Size(112 * u, 112 * u),
            cornerRadius = CornerRadius(28 * u),
        )
        drawRoundRect(
            color = Neon.Cyan.copy(alpha = 0.35f), topLeft = o(4f, 4f), size = Size(112 * u, 112 * u),
            cornerRadius = CornerRadius(28 * u), style = Stroke(1.5f * u),
        )

        val passes = if (glow) listOf(12f to 0.18f, 7f to 0.3f, 4f to 1f) else listOf(4f to 1f)
        for ((w, a) in passes) {
            val stroke = Stroke(w * u, join = StrokeJoin.Round)
            drawRoundRect(Neon.Cyan.copy(alpha = a), o(46f, 17f), Size(28 * u, 22 * u), CornerRadius(3 * u), stroke)
            drawRoundRect(Neon.Cyan.copy(alpha = a), o(37f, 39f), Size(46 * u, 64 * u), CornerRadius(12 * u), stroke)
        }
        drawRoundRect(Neon.Cyan, o(52f, 23f), Size(5 * u, 7 * u), CornerRadius(1 * u))
        drawRoundRect(Neon.Cyan, o(63f, 23f), Size(5 * u, 7 * u), CornerRadius(1 * u))

        val bolt = Path().apply {
            moveTo(65 * u, 49 * u); lineTo(50 * u, 75 * u); lineTo(60 * u, 75 * u)
            lineTo(55 * u, 95 * u); lineTo(72 * u, 66 * u); lineTo(62 * u, 66 * u)
            lineTo(67 * u, 49 * u); close()
        }
        if (glow) drawPath(bolt, Neon.Magenta.copy(alpha = 0.35f), style = Stroke(6 * u, join = StrokeJoin.Round))
        drawPath(bolt, Neon.Magenta)
    }
}

/** The floating version used on the welcome state. */
@Composable
fun FloatingIgnyxMark(size: Dp) {
    val t = rememberInfiniteTransition(label = "float")
    val y by t.animateFloat(
        0f, -10f, infiniteRepeatable(tween(2250, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y",
    )
    IgnyxMark(size, Modifier.graphicsLayer { translationY = y.dp.toPx() })
}

/** "IGNYX" wordmark with neon text glow and an occasional sign-like flicker. */
@Composable
fun IgnyxWordmark(fontSize: TextUnit = 22.sp, flicker: Boolean = true) {
    val t = rememberInfiniteTransition(label = "flicker")
    val alpha by t.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 5000
                1f at 0 using LinearEasing
                1f at 900; 0.55f at 1000; 1f at 1100; 0.6f at 1200; 1f at 1250
                1f at 2650; 0.55f at 2750; 1f at 2850
            }
        ),
        label = "a",
    )
    val text = buildAnnotatedString {
        append("IGN")
        withStyle(
            SpanStyle(color = Neon.Magenta, shadow = Shadow(Neon.Magenta.copy(alpha = 0.8f), blurRadius = 24f))
        ) { append("Y") }
        append("X")
    }
    Text(
        text = text,
        modifier = Modifier.graphicsLayer { this.alpha = if (flicker) alpha else 1f },
        style = TextStyle(
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            letterSpacing = (fontSize.value * 0.18f).sp,
            color = Neon.Text,
            shadow = Shadow(Neon.Cyan.copy(alpha = 0.75f), blurRadius = 24f),
        ),
    )
}
