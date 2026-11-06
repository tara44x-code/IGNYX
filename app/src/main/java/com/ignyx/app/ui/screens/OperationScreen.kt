package com.ignyx.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ignyx.app.Operation
import com.ignyx.app.StepStatus
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.theme.ChakraPetch
import com.ignyx.app.ui.theme.Neon

@Composable
fun OperationScreen(op: Operation, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Neon.Night).padding(20.dp)) {
        Text(op.title, style = MaterialTheme.typography.titleLarge, color = Neon.Text, modifier = Modifier.padding(top = 4.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                ProgressRing(op.progress, spinning = op.running)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                op.steps.forEach { StepRow(it.title, it.status) }
            }
        }

        val note = when {
            op.error != null -> op.error
            op.finished -> op.doneMessage
            else -> "لا تفصل الفلاشة ولا تطلع من التطبيق لحد ما يخلص."
        }
        Text(
            note, color = if (op.error != null) Neon.Magenta else Neon.Muted,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        )
        NeonButton(
            text = when { op.running -> "جاري العمل…"; op.finished -> "تمام"; else -> "رجوع" },
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            enabled = !op.running,
            style = if (op.error != null) NeonStyle.GhostMagenta else NeonStyle.Cyan,
        )
    }
}

@Composable
private fun ProgressRing(progress: Float, spinning: Boolean) {
    val shown by animateFloatAsState(progress, tween(250), label = "p")
    val t = rememberInfiniteTransition(label = "ring")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val spinBack by t.animateFloat(360f, 0f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spinBack")
    val glow by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    val a = if (spinning) 1f else 0f

    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(250.dp).rotate(spinBack * a)) {
            drawCircle(
                Neon.Magenta.copy(alpha = 0.55f), radius = size.minDimension / 2 - 4.dp.toPx(),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 14.dp.toPx()))),
            )
        }
        Canvas(Modifier.size(165.dp).rotate(spin * a)) {
            drawCircle(
                Neon.Cyan.copy(alpha = 0.25f), radius = size.minDimension / 2,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(40.dp.toPx(), 20.dp.toPx()))),
            )
        }
        Canvas(Modifier.size(205.dp)) {
            val w = 14.dp.toPx()
            val inset = w / 2 + 12.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Neon.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            listOf(30.dp to 0.10f, 22.dp to 0.18f).forEach { (gw, al) ->
                drawArc(Neon.Cyan.copy(alpha = al * glow), -90f, 360f * shown, false, topLeft, arcSize, style = Stroke(gw.toPx(), cap = StrokeCap.Round))
            }
            drawArc(Neon.Cyan, -90f, 360f * shown, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(
            "${(shown * 100).toInt()}%",
            style = TextStyle(
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 54.sp, color = Neon.Text,
                shadow = Shadow(Neon.Cyan.copy(alpha = 0.7f), blurRadius = 30f),
            ),
        )
    }
}

@Composable
private fun StepRow(title: String, status: StepStatus) {
    val active = status == StepStatus.Running
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) Neon.Cyan.copy(alpha = 0.07f) else Neon.SurfaceLow)
            .border(1.dp, if (active) Neon.Cyan.copy(alpha = 0.5f) else Neon.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (status) {
            StepStatus.Done -> Box(Modifier.size(28.dp).clip(CircleShape).background(Neon.Cyan), contentAlignment = Alignment.Center) {
                Icon24(IgnyxIcon.Check, Neon.OnCyan, 16.dp, 3f)
            }
            StepStatus.Running -> Box(Modifier.size(28.dp).border(2.dp, Neon.Cyan, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Neon.Cyan))
            }
            StepStatus.Waiting -> Box(Modifier.size(28.dp).border(2.dp, Neon.LineStrong, CircleShape))
            StepStatus.Failed -> Box(Modifier.size(28.dp).clip(CircleShape).background(Neon.Magenta), contentAlignment = Alignment.Center) {
                Text("!", color = Neon.OnMagenta, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            title,
            color = when (status) {
                StepStatus.Waiting -> Neon.Muted; StepStatus.Running -> Neon.Cyan
                StepStatus.Done -> Neon.Text; StepStatus.Failed -> Neon.Magenta
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold),
        )
    }
}
