package com.ignyx.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ignyx.app.CopyState
import com.ignyx.app.UiState
import com.ignyx.app.formatBytes
import com.ignyx.app.ui.components.FloatingIgnyxMark
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.IgnyxMark
import com.ignyx.app.ui.components.IgnyxWordmark
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.components.neonGlow
import com.ignyx.app.ui.theme.ChakraPetch
import com.ignyx.app.ui.theme.Neon
import com.ignyx.app.usb.DriveInfo
import com.ignyx.app.usb.DriveState
import com.ignyx.app.usb.IsoEntry

@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
    onAddIso: () -> Unit,
    onPrepare: () -> Unit,
    onDelete: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Neon.Night)) {
        TopBar(onRefresh)
        Box(Modifier.weight(1f)) {
            when (val d = state.drive) {
                DriveState.Disconnected -> EmptyState(
                    title = "وصّل فلاشة USB",
                    body = "استخدم وصلة OTG بين الفلاشة والموبايل، وIGNYX راح يحس بيها تلقائياً.",
                    action = "بحث مرة ثانية", style = NeonStyle.Ghost, onAction = onRefresh,
                )
                is DriveState.NeedsPermission -> EmptyState(
                    title = "لكيت ${d.name}",
                    body = "اسمح لـ IGNYX يوصل للفلاشة حتى يكدر يقرأها ويجهّزها.",
                    action = "السماح بالوصول", style = NeonStyle.Cyan, onAction = onGrant,
                )
                is DriveState.NotPrepared -> EmptyState(
                    title = "${d.name} · ${formatBytes(d.diskBytes)}",
                    body = if (d.gpt) "الفلاشة مجهّزة بنظام GPT، وIGNYX يدعم MBR بس. التجهيز يمسح كل شي عليها ويخليها قابلة للإقلاع."
                    else "الفلاشة بعدها مو مجهّزة للإقلاع. التجهيز يمسح كل شي عليها مرة وحدة، وبعدها تضيف الأنظمة براحتك.",
                    action = "تجهيز الفلاشة", style = NeonStyle.Magenta, onAction = onPrepare,
                )
                is DriveState.Failed -> EmptyState(
                    title = "صار خطأ ويا ${d.name}",
                    body = d.reason + "\nافصل الفلاشة ورجّع وصّلها، وجرّب مرة ثانية.",
                    action = "إعادة المحاولة", style = NeonStyle.Ghost, onAction = onRefresh,
                )
                is DriveState.Ready -> ReadyContent(d.info, state.copy, onAddIso, onPrepare, onDelete)
            }
        }
        state.message?.let { MessageBar(it, onDismissMessage) }
    }
}

@Composable
private fun TopBar(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IgnyxMark(36.dp, glow = false)
            IgnyxWordmark(22.sp)
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Neon.Surface)
                .border(1.dp, Neon.LineStrong, RoundedCornerShape(14.dp)).clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) { Icon24(IgnyxIcon.Refresh, Neon.Text, 20.dp, 2f) }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String, style: NeonStyle, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingIgnyxMark(150.dp)
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Neon.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal), color = Neon.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        NeonButton(action, onAction, style = style)
    }
}

@Composable
private fun ReadyContent(info: DriveInfo, copy: CopyState?, onAddIso: () -> Unit, onPrepare: () -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            item { DriveCard(info) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("الأنظمة على الفلاشة", style = MaterialTheme.typography.titleMedium, color = Neon.Text)
                    Text(
                        "إعادة تجهيز", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPrepare).padding(8.dp),
                    )
                }
            }
            if (info.isos.isEmpty()) {
                item {
                    Text(
                        "ماكو أنظمة بعد. اضغط \"إضافة ملف ISO\" وحط أول نظام.",
                        color = Neon.Muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(info.isos, key = { it.name }) { IsoRow(it, enabled = copy == null, onDelete = { onDelete(it.name) }) }
        }
        copy?.let { CopyCard(it) }
        NeonButton(
            "إضافة ملف ISO", onAddIso,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            enabled = copy == null,
            leading = { Icon24(IgnyxIcon.Plus, Neon.OnCyan, 22.dp, 2.6f) },
        )
    }
}

@Composable
private fun DriveCard(info: DriveInfo) {
    val shape = RoundedCornerShape(24.dp)
    val pulse = rememberInfiniteTransition(label = "dot")
    val dot by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val used = if (info.capacityBytes > 0) (info.usedBytes.toFloat() / info.capacityBytes).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxWidth().shadow(24.dp, shape, ambientColor = Neon.Cyan, spotColor = Neon.Cyan)
            .clip(shape).background(Neon.Surface).border(1.dp, Neon.Cyan.copy(alpha = 0.35f), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(info.name, style = MaterialTheme.typography.titleMedium, color = Neon.Text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Neon.Cyan.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = dot }.clip(CircleShape).background(Neon.Cyan))
                Text("جاهزة للإقلاع", color = Neon.Cyan, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatBytes(info.usedBytes), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Neon.Text)
            Text("/ ${formatBytes(info.capacityBytes)}", fontFamily = ChakraPetch, fontSize = 15.sp, color = Neon.Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Neon.Track)) {
            Box(
                Modifier.fillMaxWidth(used.coerceAtLeast(0.02f)).height(10.dp)
                    .neonGlow(Neon.Cyan, 5.dp, 0.3f).clip(RoundedCornerShape(50)).background(Neon.Cyan)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${info.isos.size} أنظمة", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
            Text("فارغ ${formatBytes(info.freeBytes)}", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IsoRow(iso: IsoEntry, enabled: Boolean, onDelete: () -> Unit) {
    val accent = if (iso.name.hashCode() % 2 == 0) Neon.Cyan else Neon.Magenta
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(Neon.SurfaceLow)
            .border(1.dp, Neon.Line, RoundedCornerShape(18.dp)).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(iso.name.substringAfterLast('.', "ISO").uppercase().take(4), color = accent, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(iso.name.substringBeforeLast('.'), color = Neon.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(formatBytes(iso.sizeBytes), color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClickLabel = "حذف ${iso.name}", onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon24(IgnyxIcon.Trash, Neon.Muted, 20.dp, 2f) }
    }
}

@Composable
private fun CopyCard(copy: CopyState) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp).clip(RoundedCornerShape(18.dp))
            .background(Neon.Surface).border(1.dp, Neon.Magenta.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("جاري نسخ ${copy.fileName}", color = Neon.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("${(copy.progress * 100).toInt()}%", color = Neon.Magenta, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Neon.Track)) {
            Box(Modifier.fillMaxWidth(copy.progress.coerceAtLeast(0.01f)).height(6.dp).clip(RoundedCornerShape(50)).background(Neon.Magenta))
        }
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0A0D1C)).clickable(onClick = onDismiss).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Neon.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("تمام", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
