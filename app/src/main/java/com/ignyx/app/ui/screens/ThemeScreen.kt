package com.ignyx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.ignyx.app.UiState
import com.ignyx.app.ltr
import com.ignyx.app.ui.components.ActionRow
import com.ignyx.app.ui.components.ChoiceRow
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.NeonButton
import com.ignyx.app.ui.components.NeonStyle
import com.ignyx.app.ui.components.NeonSwitch
import com.ignyx.app.ui.components.Panel
import com.ignyx.app.ui.components.SectionTitle
import com.ignyx.app.ui.theme.Neon
import com.ignyx.app.usb.DriveState

class ThemeActions(
    val pickPhoto: () -> Unit,
    val clearPhoto: () -> Unit,
    val setDim: (Float) -> Unit,
    val setLogo: (Boolean) -> Unit,
    val setAccent: (Int) -> Unit,
    val setResolution: (Int, Int) -> Unit,
    val apply: () -> Unit,
    val remove: () -> Unit,
)

val ACCENTS = listOf(
    0xFF22F1FF.toInt() to "سيان",
    0xFFFF3DCB.toInt() to "ماجنتا",
    0xFF39FF88.toInt() to "أخضر",
    0xFFFFA62B.toInt() to "برتقالي",
    0xFF9D7BFF.toInt() to "بنفسجي",
)

@Composable
fun ThemeScreen(state: UiState, a: ThemeActions) {
    val t = state.theme
    val o = t.options
    val ready = state.drive as? DriveState.Ready
    val accent = Color(o.accent)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("مظهر قائمة الإقلاع", style = MaterialTheme.typography.headlineMedium, color = Neon.Text)
        Text(
            "هذا اللي يطلع على شاشة الحاسبة لما تقلع من الفلاشة.",
            style = MaterialTheme.typography.bodyMedium, color = Neon.Muted,
        )

        Preview(state, accent)

        SectionTitle("الخلفية")
        ActionRow(
            icon = IgnyxIcon.Image,
            title = if (o.photo == null) "اختار صورة من الاستوديو" else "تغيير الصورة",
            subtitle = if (o.photo == null) "أو خليها على خلفية النيون" else "التطبيق يقصها على حجم الشاشة",
            onClick = a.pickPhoto,
            trailing = if (o.photo != null) ({
                Text(
                    "إزالة", color = Neon.Magenta, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = a.clearPhoto).padding(10.dp),
                )
            }) else null,
        )
        if (o.photo != null) {
            Panel {
                Text("تعتيم الصورة حتى تنقرا القائمة", color = Neon.Text, style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = o.dim, onValueChange = a.setDim, valueRange = 0f..0.85f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Neon.Track),
                )
            }
        }
        ActionRow(
            icon = IgnyxIcon.Usb, title = "شعار IGNYX فوق",
            onClick = { a.setLogo(!o.showLogo) },
            trailing = { NeonSwitch(o.showLogo, a.setLogo) },
        )

        SectionTitle("لون النيون")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ACCENTS.forEach { (c, name) ->
                val on = c == o.accent
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .border(if (on) 3.dp else 1.dp, if (on) Neon.Text else Neon.LineStrong, CircleShape)
                        .padding(5.dp).clip(CircleShape).background(Color(c))
                        .clickable(role = Role.RadioButton, onClickLabel = name) { a.setAccent(c) },
                )
            }
        }

        SectionTitle("دقة الشاشة")
        ChoiceRow(
            listOf((1920 to 1080) to ltr("1920×1080"), (1366 to 768) to ltr("1366×768"), (1024 to 768) to ltr("1024×768")),
            o.width to o.height,
        ) { (w, h) -> a.setResolution(w, h) }
        Text(
            "إذا الحاسبة ما تدعم الدقة المختارة، القائمة تطلع بالدقة الافتراضية.",
            color = Neon.Muted, style = MaterialTheme.typography.bodyMedium,
        )

        if (ready == null) {
            Text(
                "وصّل فلاشة مجهّزة حتى تطبّق المظهر عليها.",
                color = Neon.Magenta, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        NeonButton(
            "تطبيق على الفلاشة", a.apply,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = ready != null && state.copy == null,
        )
        if (ready?.info?.hasTheme == true) {
            NeonButton(
                "رجوع لمظهر Ventoy الأصلي", a.remove,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                style = NeonStyle.GhostMagenta, animated = false,
            )
        }
    }
}

/** A scaled mock of the GRUB menu, drawn over the rendered background. */
@Composable
private fun Preview(state: UiState, accent: Color) {
    val o = state.theme.options
    val names = state.files.filter { it.bootable }.map { it.name }.ifEmpty {
        listOf("Windows 11.iso", "Ubuntu 24.04.iso", "Bliss OS 16.iso", "Hiren's BootCD.iso")
    }.take(5)
    val shape = RoundedCornerShape(16.dp)
    // GRUB lays the menu out left-to-right regardless of the phone's language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(o.width.toFloat() / o.height).clip(shape)
                .border(1.dp, accent.copy(alpha = 0.5f), shape).background(Neon.Night),
        ) {
            state.theme.preview?.let {
                Image(it, contentDescription = "معاينة الخلفية", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            val w = maxWidth
            val h = maxHeight
            val itemH = h / 13
            Column(
                Modifier.offset(x = w * 0.18f, y = h * 0.30f).width(w * 0.64f).height(h * 0.52f)
                    .background(Color(0xD80B0E1E)).border(1.dp, accent)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                names.forEachIndexed { i, n ->
                    Box(
                        Modifier.fillMaxWidth().height(itemH).background(if (i == 0) accent else Color.Transparent).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(n, color = if (i == 0) Color(0xFF04121A) else Neon.Text, fontSize = (itemH.value * 0.42f).sp, maxLines = 1)
                    }
                }
            }
            Text(
                "Up / Down to choose    Enter to boot",
                color = Neon.Muted, fontSize = (h.value * 0.035f).sp,
                modifier = Modifier.fillMaxWidth().offset(y = h * 0.89f), textAlign = TextAlign.Center,
            )
        }
    }
}
