package com.ignyx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ignyx.app.UiState
import com.ignyx.app.compareVersions
import com.ignyx.app.formatBytes
import com.ignyx.app.ltr
import com.ignyx.app.ui.components.ActionRow
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonSwitch
import com.ignyx.app.ui.components.Panel
import com.ignyx.app.ui.components.SectionTitle
import com.ignyx.app.ui.theme.Neon
import com.ignyx.app.usb.DriveState
import com.ignyx.core.DriveReport

class ToolsActions(
    val prepare: () -> Unit,
    val update: () -> Unit,
    val restore: () -> Unit,
    val setVerify: (Boolean) -> Unit,
)

@Composable
fun ToolsScreen(state: UiState, a: ToolsActions) {
    val drive = state.drive
    val report: DriveReport? = when (drive) {
        is DriveState.Ready -> drive.info.report
        is DriveState.NotPrepared -> drive.report
        else -> null
    }
    val name = when (drive) {
        is DriveState.Ready -> drive.info.name
        is DriveState.NotPrepared -> drive.name
        else -> null
    }
    val connected = report != null
    val isVentoy = report?.isVentoy == true
    val busy = state.copy != null
    val driveVersion = report?.ventoyVersion
    val canUpdate = isVentoy && state.appVentoyVersion.isNotEmpty()
    val newer = driveVersion != null && compareVersions(state.appVentoyVersion, driveVersion) > 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("الأدوات", style = MaterialTheme.typography.headlineMedium, color = Neon.Text)

        SectionTitle("معلومات الفلاشة")
        Panel(accent = if (connected) Neon.Cyan else null) {
            if (report == null || name == null) {
                Text("ماكو فلاشة متصلة.", color = Neon.Muted, style = MaterialTheme.typography.bodyLarge)
            } else {
                InfoLine("الاسم", ltr(name))
                InfoLine("الحجم الكلي", formatBytes(report.totalSectors * 512))
                InfoLine("نوع التقسيم", report.partitionStyle + if (report.partitionStyle == "MBR") " (يقلع على BIOS و UEFI)" else "")
                InfoLine("نظام الملفات", report.fileSystem ?: "—")
                report.clusterBytes?.let { InfoLine("حجم الكتلة", formatBytes(it.toLong())) }
                InfoLine("مجهّزة للإقلاع", if (isVentoy) "نعم" else "لا")
                InfoLine("Ventoy على الفلاشة", ltr(driveVersion ?: "—"))
                InfoLine("Ventoy بالتطبيق", ltr(state.appVentoyVersion.ifEmpty { "—" }))
            }
        }

        SectionTitle("الفلاشة")
        ActionRow(
            icon = IgnyxIcon.Refresh, title = "تحديث Ventoy",
            subtitle = when {
                !isVentoy -> "يحتاج فلاشة مجهّزة"
                newer -> "متوفرة نسخة أحدث: ${ltr(state.appVentoyVersion)}. ملفاتك تبقى."
                else -> "إعادة كتابة ملفات الإقلاع بدون ما تنمسح ملفاتك"
            },
            tint = if (newer) Neon.Magenta else Neon.Cyan,
            enabled = canUpdate && !busy, onClick = a.update,
        )
        ActionRow(
            icon = IgnyxIcon.Usb, title = if (isVentoy) "إعادة تجهيز من الصفر" else "تجهيز الفلاشة للإقلاع",
            subtitle = "يمسح كل شي. تختار اسم الفلاشة ومساحة تبقى فارغة.",
            tint = Neon.Magenta, enabled = connected && !busy, onClick = a.prepare,
        )
        ActionRow(
            icon = IgnyxIcon.Folder, title = "إرجاع لفلاشة عادية",
            subtitle = "FAT32 أو exFAT، لنقل الملفات على أي جهاز",
            tint = Neon.Magenta, enabled = connected && !busy, onClick = a.restore,
        )

        SectionTitle("الإعدادات")
        ActionRow(
            icon = IgnyxIcon.Shield, title = "التحقق بعد النسخ",
            subtitle = "يقرا الملف من الفلاشة ويقارنه بالأصل (SHA-256). أبطأ بس أضمن.",
            onClick = { a.setVerify(!state.verifyAfterCopy) },
            trailing = { NeonSwitch(state.verifyAfterCopy, a.setVerify) },
        )

        SectionTitle("حول")
        Panel {
            Text(
                "IGNYX مفتوح المصدر بترخيص GPLv3، ومبني على صيغة الإقلاع مال Ventoy (github.com/ventoy/Ventoy). " +
                    "ملفات الإقلاع مأخوذة بدون تعديل من إصدار Ventoy الرسمي.",
                color = Neon.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(ltr("github.com/tara44x-code/IGNYX"), color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Neon.Text, style = MaterialTheme.typography.bodyMedium)
    }
}
