package com.ignyx.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tiny stroke icons drawn on a 24-unit grid, so the app needs no icon library. */
enum class IgnyxIcon { Plus, Back, Check, Usb, Refresh, Trash, Warning }

@Composable
fun Icon24(icon: IgnyxIcon, color: Color, size: Dp = 22.dp, strokeWidth: Float = 2.4f) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(strokeWidth * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        }
        when (icon) {
            IgnyxIcon.Plus -> {
                drawLine(color, Offset(12 * u, 5 * u), Offset(12 * u, 19 * u), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(5 * u, 12 * u), Offset(19 * u, 12 * u), stroke.width, StrokeCap.Round)
            }
            // Points right: in RTL layouts "back" goes to the right.
            IgnyxIcon.Back -> drawPath(p(9f, 6f, 15f, 12f, 9f, 18f), color, style = stroke)
            IgnyxIcon.Check -> drawPath(p(5f, 12f, 10f, 17f, 19f, 7f), color, style = stroke)
            IgnyxIcon.Usb -> {
                drawPath(p(9f, 3f, 15f, 3f, 15f, 9f, 9f, 9f, 9f, 3f), color, style = stroke)
                drawPath(p(7f, 9f, 17f, 9f, 17f, 20f, 7f, 20f, 7f, 9f), color, style = stroke)
            }
            IgnyxIcon.Trash -> {
                drawPath(p(4f, 7f, 20f, 7f), color, style = stroke)
                drawPath(p(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f), color, style = stroke)
                drawPath(p(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), color, style = stroke)
            }
            IgnyxIcon.Warning -> {
                drawPath(p(12f, 3f, 22f, 20f, 2f, 20f, 12f, 3f), color, style = stroke)
                drawLine(color, Offset(12 * u, 9 * u), Offset(12 * u, 14 * u), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(12 * u, 17 * u), Offset(12 * u, 17.2f * u), stroke.width, StrokeCap.Round)
            }
            IgnyxIcon.Refresh -> {
                drawArc(color, 40f, 290f, false, Offset(5 * u, 5 * u), androidx.compose.ui.geometry.Size(14 * u, 14 * u), style = stroke)
                drawPath(p(19f, 4f, 19f, 9f, 14f, 9f), color, style = stroke)
            }
        }
    }
}
