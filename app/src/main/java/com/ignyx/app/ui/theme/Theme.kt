package com.ignyx.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ignyx.app.R

/** IGNYX palette: deep night ground, two neon accents. */
object Neon {
    val Night = Color(0xFF05060F)
    val Surface = Color(0xFF0E1226)
    val SurfaceLow = Color(0xFF0B0E1E)
    val Line = Color(0xFF1E2440)
    val LineStrong = Color(0xFF2A3156)
    val Track = Color(0xFF1A2040)
    val Cyan = Color(0xFF22F1FF)
    val OnCyan = Color(0xFF04121A)
    val Magenta = Color(0xFFFF3DCB)
    val OnMagenta = Color(0xFF1A0414)
    val Text = Color(0xFFEEF1FF)
    val Muted = Color(0xFF8E97C2)
}

@OptIn(ExperimentalTextApi::class)
private fun cairo(weight: Int) = Font(
    R.font.cairo_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Arabic UI text. */
val Cairo = FontFamily(cairo(400), cairo(600), cairo(800))

/** Brand wordmark, numbers and percentages. */
val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

private val IgnyxTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(800), fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(800), fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(800), fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(600), fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(400), fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(800), fontSize = 18.sp),
    labelMedium = TextStyle(fontFamily = Cairo, fontWeight = FontWeight(600), fontSize = 12.sp),
)

private val IgnyxColors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Neon.OnCyan,
    secondary = Neon.Magenta,
    onSecondary = Neon.OnMagenta,
    background = Neon.Night,
    onBackground = Neon.Text,
    surface = Neon.Surface,
    onSurface = Neon.Text,
    onSurfaceVariant = Neon.Muted,
    outline = Neon.LineStrong,
)

@Composable
fun IgnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IgnyxColors, typography = IgnyxTypography, content = content)
}
