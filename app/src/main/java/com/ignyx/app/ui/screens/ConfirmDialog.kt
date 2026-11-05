package com.ignyx.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ignyx.app.Confirm
import com.ignyx.app.formatBytes
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.theme.Neon

@Composable
fun ConfirmDialog(confirm: Confirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, action) = when (confirm) {
        is Confirm.Install -> Triple(
            "يمسح كل شي على الفلاشة؟",
            "راح ينمسح كل شي على ${confirm.driveName} (${formatBytes(confirm.diskBytes)}) وما يرجع. " +
                "تأكد إنها الفلاشة الصحيحة، ولا تفصلها لحد ما يخلص التجهيز.",
            "امسح وجهّز",
        )
        is Confirm.Delete -> Triple(
            "تحذف الملف؟",
            "راح ينحذف ${confirm.fileName} من الفلاشة.",
            "احذف",
        )
    }
    val shape = RoundedCornerShape(26.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, Neon.Magenta.copy(alpha = 0.5f), shape),
        shape = shape,
        containerColor = Neon.Surface,
        icon = { Icon24(IgnyxIcon.Warning, Neon.Magenta, 30.dp, 2f) },
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Neon.Text) },
        text = {
            Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal), color = Neon.Muted)
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton(action, onConfirm, Modifier.weight(1f), style = NeonStyle.Magenta)
                NeonButton("إلغاء", onDismiss, Modifier.weight(1f), style = NeonStyle.Ghost, animated = false)
            }
        },
    )
}
