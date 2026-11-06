package com.ignyx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ignyx.app.ui.theme.Neon

/** A dark rounded panel with an optional neon edge. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Neon.Surface)
            .border(1.dp, accent?.copy(alpha = 0.45f) ?: Neon.Line, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Small rounded label, e.g. "MBR" or "exFAT". */
@Composable
fun Tag(text: String, color: Color = Neon.Cyan) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Neon.Text, modifier = Modifier.padding(top = 8.dp))
}

/** A tappable row with an icon, title, subtitle and optional trailing content. */
@Composable
fun ActionRow(
    icon: IgnyxIcon,
    title: String,
    subtitle: String? = null,
    tint: Color = Neon.Cyan,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Neon.SurfaceLow).border(1.dp, Neon.Line, shape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = if (enabled) 0.12f else 0.05f)),
            contentAlignment = Alignment.Center,
        ) { Icon24(icon, if (enabled) tint else Neon.Muted, 21.dp, 2f) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Neon.Text else Neon.Muted, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
fun NeonSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Neon.OnCyan, checkedTrackColor = Neon.Cyan,
            uncheckedThumbColor = Neon.Muted, uncheckedTrackColor = Neon.Track, uncheckedBorderColor = Neon.LineStrong,
        ),
    )
}

/** Choice chips in a row. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val shape = RoundedCornerShape(14.dp)
            Text(
                label,
                color = if (on) Neon.OnCyan else Neon.Text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(shape)
                    .background(if (on) Neon.Cyan else Neon.SurfaceLow)
                    .border(1.dp, if (on) Neon.Cyan else Neon.LineStrong, shape)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
