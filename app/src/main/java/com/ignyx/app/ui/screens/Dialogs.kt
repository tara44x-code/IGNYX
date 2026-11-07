package com.ignyx.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ignyx.app.Dialog
import com.ignyx.app.formatBytes
import com.ignyx.app.ltr
import com.ignyx.app.ui.components.ChoiceRow
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.theme.Neon
import com.ignyx.core.DriveTools

/** Everything the dialogs can ask the view model to do. */
class DialogActions(
    val dismiss: () -> Unit,
    val install: (label: String, reserveGb: Int) -> Unit,
    val update: () -> Unit,
    val restore: (DriveTools.PlainFs, String) -> Unit,
    val delete: (com.ignyx.app.usb.FileItem) -> Unit,
    val newFolder: (String) -> Unit,
    val removeTheme: () -> Unit,
)

@Composable
fun AppDialog(dialog: Dialog, a: DialogActions) {
    when (dialog) {
        is Dialog.Install -> {
            var label by remember { mutableStateOf("IGNYX") }
            var reserve by remember { mutableIntStateOf(0) }
            NeonDialog(
                title = "تجهيز الفلاشة للإقلاع",
                danger = true,
                body = "راح ينمسح كل شي على ${ltr(dialog.driveName)} (${formatBytes(dialog.diskBytes)}) وما يرجع. " +
                    "تأكد إنها الفلاشة الصحيحة، ولا تفصلها لحد ما يخلص.",
                action = "امسح وجهّز",
                onConfirm = { a.install(label, reserve) },
                onDismiss = a.dismiss,
            ) {
                NeonField(label, { label = it.take(11) }, "اسم الفلاشة")
                Text("مساحة تبقى فارغة بآخر الفلاشة", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(listOf(0 to "بدون", 4 to ltr("4 GB"), 16 to ltr("16 GB")), reserve) { reserve = it }
            }
        }
        is Dialog.Update -> NeonDialog(
            title = "تحديث نظام الإقلاع",
            body = "النسخة على الفلاشة: ${ltr(dialog.from ?: "غير معروفة")}\nالنسخة بالتطبيق: ${ltr(dialog.to)}\n\n" +
                "التحديث يبدّل ملفات الإقلاع بس، والأنظمة والملفات اللي على الفلاشة تبقى مثل ما هي.",
            action = "حدّث",
            onConfirm = a.update,
            onDismiss = a.dismiss,
        )
        is Dialog.Restore -> {
            var fs by remember { mutableStateOf(DriveTools.PlainFs.EXFAT) }
            var label by remember { mutableStateOf("IGNYX") }
            NeonDialog(
                title = "إرجاع لفلاشة عادية",
                danger = true,
                body = "راح ينمسح كل شي على ${ltr(dialog.driveName)} (${formatBytes(dialog.diskBytes)})، " +
                    "وتصير فلاشة عادية لنقل الملفات، مو للإقلاع.",
                action = "امسح وفرمت",
                onConfirm = { a.restore(fs, label) },
                onDismiss = a.dismiss,
            ) {
                ChoiceRow(listOf(DriveTools.PlainFs.EXFAT to "exFAT", DriveTools.PlainFs.FAT32 to "FAT32"), fs) { fs = it }
                Text(
                    if (fs == DriveTools.PlainFs.FAT32) "FAT32 يشتغل على كل الأجهزة (تلفزيونات، سيارات، ريسيفرات)، بس ما يقبل ملف أكبر من 4GB."
                    else "exFAT يقبل ملفات كبيرة ويشتغل على ويندوز وماك وأندرويد وأغلب الأجهزة الحديثة.",
                    color = Neon.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                NeonField(label, { label = it.take(11) }, "اسم الفلاشة")
            }
        }
        is Dialog.Delete -> NeonDialog(
            title = if (dialog.item.isDirectory) "تحذف المجلد؟" else "تحذف الملف؟",
            danger = true,
            body = if (dialog.item.isDirectory) "راح ينحذف مجلد ${ltr(dialog.item.name)} وكل اللي بداخله."
            else "راح ينحذف ${ltr(dialog.item.name)} من الفلاشة.",
            action = "احذف",
            onConfirm = { a.delete(dialog.item) },
            onDismiss = a.dismiss,
        )
        Dialog.NewFolder -> {
            var name by remember { mutableStateOf("") }
            NeonDialog(
                title = "مجلد جديد",
                body = "رتّب أنظمتك بمجلدات، مثلاً Windows وLinux. قائمة الإقلاع تلكى الملفات داخل المجلدات تلقائياً.",
                action = "سوّي",
                onConfirm = { a.newFolder(name) },
                onDismiss = a.dismiss,
            ) { NeonField(name, { name = it.take(60) }, "اسم المجلد") }
        }
        Dialog.RemoveTheme -> NeonDialog(
            title = "إزالة المظهر؟",
            body = "قائمة الإقلاع ترجع للشكل الافتراضي. صورك وأنظمتك ما تتأثر.",
            action = "أزل",
            onConfirm = a.removeTheme,
            onDismiss = a.dismiss,
        )
    }
}

@Composable
private fun NeonDialog(
    title: String,
    body: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(26.dp)
    val accent = if (danger) Neon.Magenta else Neon.Cyan
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, accent.copy(alpha = 0.5f), shape),
        shape = shape,
        containerColor = Neon.Surface,
        icon = if (danger) ({ Icon24(IgnyxIcon.Warning, Neon.Magenta, 30.dp, 2f) }) else null,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Neon.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal), color = Neon.Muted)
                extra?.invoke()
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton(action, onConfirm, Modifier.weight(1f), style = if (danger) NeonStyle.Magenta else NeonStyle.Cyan)
                NeonButton("إلغاء", onDismiss, Modifier.weight(1f), style = NeonStyle.Ghost, animated = false)
            }
        },
    )
}

@Composable
private fun NeonField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Neon.Cyan, unfocusedBorderColor = Neon.LineStrong,
            focusedLabelColor = Neon.Cyan, unfocusedLabelColor = Neon.Muted,
            focusedTextColor = Neon.Text, unfocusedTextColor = Neon.Text, cursorColor = Neon.Cyan,
        ),
    )
}
