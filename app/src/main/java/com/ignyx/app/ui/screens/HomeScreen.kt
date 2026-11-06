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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ignyx.app.CopyState
import com.ignyx.app.UiState
import com.ignyx.app.compareVersions
import com.ignyx.app.formatBytes
import com.ignyx.app.formatDuration
import com.ignyx.app.formatSpeed
import com.ignyx.app.ltr
import com.ignyx.app.ui.components.FloatingIgnyxMark
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.components.Tag
import com.ignyx.app.ui.components.neonGlow
import com.ignyx.app.ui.theme.ChakraPetch
import com.ignyx.app.ui.theme.Neon
import com.ignyx.app.usb.DriveInfo
import com.ignyx.app.usb.DriveState
import com.ignyx.app.usb.FileItem

class DriveActions(
    val refresh: () -> Unit,
    val grant: () -> Unit,
    val prepare: () -> Unit,
    val addFiles: () -> Unit,
    val openFolder: (String) -> Unit,
    val up: () -> Unit,
    val delete: (FileItem) -> Unit,
    val newFolder: () -> Unit,
    val update: () -> Unit,
)

@Composable
fun DriveScreen(state: UiState, a: DriveActions) {
    Box(Modifier.fillMaxSize()) {
        when (val d = state.drive) {
            DriveState.Disconnected -> EmptyState(
                title = "وصّل فلاشة USB",
                body = "استخدم وصلة OTG بين الفلاشة والموبايل، وIGNYX راح يحس بيها تلقائياً.",
                action = "بحث مرة ثانية", style = NeonStyle.Ghost, onAction = a.refresh,
            )
            is DriveState.NeedsPermission -> EmptyState(
                title = "لكيت ${ltr(d.name)}",
                body = "اسمح لـ IGNYX يوصل للفلاشة حتى يكدر يقرأها ويجهّزها.",
                action = "السماح بالوصول", style = NeonStyle.Cyan, onAction = a.grant,
            )
            is DriveState.NotPrepared -> EmptyState(
                title = "${ltr(d.name)} · ${formatBytes(d.diskBytes)}",
                body = when (d.report.partitionStyle) {
                    "GPT" -> "الفلاشة مقسّمة بنظام GPT. التجهيز يمسح كل شي عليها ويخليها قابلة للإقلاع."
                    else -> "الفلاشة بعدها مو مجهّزة للإقلاع. التجهيز يمسح كل شي عليها مرة وحدة، وبعدها تضيف الأنظمة براحتك."
                },
                action = "تجهيز الفلاشة", style = NeonStyle.Magenta, onAction = a.prepare,
            )
            is DriveState.Failed -> EmptyState(
                title = "صار خطأ ويا ${ltr(d.name)}",
                body = d.reason + "\nافصل الفلاشة ورجّع وصّلها، وجرّب مرة ثانية.",
                action = "إعادة المحاولة", style = NeonStyle.Ghost, onAction = a.refresh,
            )
            is DriveState.Ready -> ReadyContent(state, d.info, a)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String, style: NeonStyle, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingIgnyxMark(140.dp)
        Spacer(Modifier.height(30.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Neon.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal), color = Neon.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        NeonButton(action, onAction, style = style)
    }
}

@Composable
private fun ReadyContent(state: UiState, info: DriveInfo, a: DriveActions) {
    val copying = state.copy != null
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { DriveCard(info, state.appVentoyVersion, a.update) }
            item { FolderBar(state.path, a, enabled = !copying) }
            if (state.files.isEmpty()) {
                item {
                    Text(
                        if (state.path.isEmpty()) "ماكو أنظمة بعد. اضغط \"إضافة\" وحط أول ملف ISO." else "المجلد فارغ.",
                        color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(state.files, key = { it.path }) { f ->
                FileRow(f, enabled = !copying, onOpen = { a.openFolder(f.path) }, onDelete = { a.delete(f) })
            }
        }
        state.copy?.let { CopyCard(it) }
        NeonButton(
            if (state.path.isEmpty()) "إضافة ملفات ISO" else "إضافة إلى ${ltr(state.path.substringAfterLast('/'))}",
            a.addFiles,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp, top = 6.dp),
            enabled = !copying,
            leading = { Icon24(IgnyxIcon.Plus, Neon.OnCyan, 22.dp, 2.6f) },
        )
    }
}

@Composable
private fun DriveCard(info: DriveInfo, appVersion: String, onUpdate: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val pulse = rememberInfiniteTransition(label = "dot")
    val dot by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val cap = info.capacityBytes.coerceAtLeast(1)
    val bootFrac = (info.bootableBytes.toFloat() / cap).coerceIn(0f, 1f)
    val otherFrac = (info.otherBytes.toFloat() / cap).coerceIn(0f, 1f - bootFrac)
    val driveVersion = info.report.ventoyVersion
    val updateAvailable = driveVersion != null && appVersion.isNotEmpty() && compareVersions(appVersion, driveVersion) > 0

    Column(
        Modifier.fillMaxWidth().shadow(24.dp, shape, ambientColor = Neon.Cyan, spotColor = Neon.Cyan)
            .clip(shape).background(Neon.Surface).border(1.dp, Neon.Cyan.copy(alpha = 0.35f), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(ltr(info.name), style = MaterialTheme.typography.titleMedium, color = Neon.Text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Neon.Cyan.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = dot }.clip(CircleShape).background(Neon.Cyan))
                Text("جاهزة للإقلاع", color = Neon.Cyan, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatBytes(info.freeBytes), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Neon.Text)
            Text("فارغ من ${formatBytes(info.capacityBytes)}", fontSize = 14.sp, color = Neon.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        // Usage bar: bootable images, then everything else.
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Neon.Track)) {
            if (bootFrac > 0f) Box(Modifier.fillMaxWidth(bootFrac.coerceAtLeast(0.01f)).height(10.dp).neonGlow(Neon.Cyan, 5.dp, 0.3f).background(Neon.Cyan))
            if (otherFrac > 0.001f) Box(Modifier.fillMaxWidth(otherFrac / (1f - bootFrac).coerceAtLeast(0.01f)).height(10.dp).background(Neon.Magenta))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(Neon.Cyan, "${info.bootableCount} أنظمة · ${formatBytes(info.bootableBytes)}")
            if (info.otherBytes > 50_000_000) Legend(Neon.Magenta, "ملفات أخرى · ${formatBytes(info.otherBytes)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(info.report.partitionStyle)
            Tag(info.report.fileSystem ?: "—")
            Tag("Ventoy ${ltr(driveVersion ?: "؟")}")
            if (updateAvailable) {
                Text(
                    "تحديث ${ltr(appVersion)}", color = Neon.OnMagenta, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Neon.Magenta)
                        .clickable(role = Role.Button, onClick = onUpdate).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FolderBar(path: String, a: DriveActions, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (path.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Neon.Surface)
                    .border(1.dp, Neon.LineStrong, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClickLabel = "رجوع للمجلد السابق", onClick = a.up),
                contentAlignment = Alignment.Center,
            ) { Icon24(IgnyxIcon.Back, Neon.Text, 18.dp) }
        }
        Text(
            if (path.isEmpty()) "الأنظمة على الفلاشة" else ltr(path.replace("/", " › ")),
            style = MaterialTheme.typography.titleMedium, color = Neon.Text, maxLines = 1, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = a.newFolder).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon24(IgnyxIcon.Folder, Neon.Cyan, 18.dp, 2f)
            Text("مجلد", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun FileRow(f: FileItem, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val accent = when {
        f.isDirectory -> Neon.Cyan
        f.bootable -> if (f.name.hashCode() % 2 == 0) Neon.Cyan else Neon.Magenta
        else -> Neon.Muted
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape).background(Neon.SurfaceLow)
            .border(1.dp, Neon.Line, shape)
            .then(if (f.isDirectory) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onOpen) else Modifier)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (f.isDirectory) Icon24(IgnyxIcon.Folder, accent, 22.dp, 2f)
            else Text(f.name.substringAfterLast('.', "?").uppercase().take(4), color = accent, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                ltr(if (f.bootable) f.name.substringBeforeLast('.') else f.name),
                color = if (f.bootable || f.isDirectory) Neon.Text else Neon.Muted,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            )
            Text(
                when { f.isDirectory -> "مجلد"; f.bootable -> formatBytes(f.sizeBytes); else -> "${formatBytes(f.sizeBytes)} · ما يقلع" },
                color = Neon.Muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClickLabel = "حذف ${f.name}", onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon24(IgnyxIcon.Trash, Neon.Muted, 20.dp, 2f) }
    }
}

@Composable
private fun CopyCard(copy: CopyState) {
    val accent = if (copy.verifying) Neon.Cyan else Neon.Magenta
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(Neon.Surface).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (copy.verifying) "تحقق من " else "نسخ ") + ltr(copy.fileName) +
                    if (copy.count > 1) " (${copy.index}/${copy.count})" else "",
                color = Neon.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${(copy.progress * 100).toInt()}%", color = accent, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Neon.Track)) {
            Box(Modifier.fillMaxWidth(copy.progress.coerceAtLeast(0.01f)).height(6.dp).clip(RoundedCornerShape(50)).background(accent))
        }
        if (copy.speed > 0) {
            Text(
                "${formatSpeed(copy.speed)} · باقي ${formatDuration(copy.etaSeconds)}",
                color = Neon.Muted, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
