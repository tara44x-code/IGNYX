package com.ignyx.app.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.ignyx.app.R
import com.ignyx.core.BootTheme
import java.io.ByteArrayOutputStream

/** User choices on the theme screen. */
data class ThemeOptions(
    val accent: Int = 0xFF22F1FF.toInt(),
    val width: Int = 1920,
    val height: Int = 1080,
    val photo: Bitmap? = null,
    /** 0..1, how much to darken the photo so the menu stays readable. */
    val dim: Float = 0.45f,
    val showLogo: Boolean = true,
)

/** Draws the boot menu background and frame pieces with Android graphics. */
object ThemeRenderer {

    private const val NIGHT = 0xFF05060F.toInt()
    private const val SECOND = 0xFFFF3DCB.toInt()

    fun background(ctx: Context, o: ThemeOptions): Bitmap {
        val w = o.width
        val h = o.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val photo = o.photo
        if (photo != null) {
            // Centre-crop the photo to the screen's shape.
            val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
            val sw = (w / scale).toInt()
            val sh = (h / scale).toInt()
            val sx = (photo.width - sw) / 2
            val sy = (photo.height - sh) / 2
            c.drawBitmap(photo, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(Color.argb((o.dim * 255).toInt().coerceIn(0, 255), 0, 0, 0))
        } else {
            drawNeonScene(c, w, h, o.accent)
        }
        if (o.showLogo) drawLogo(ctx, c, w, h, o.accent)
        return bmp
    }

    private fun drawNeonScene(c: Canvas, w: Int, h: Int, accent: Int) {
        c.drawColor(NIGHT)
        // Soft glow from the bottom, like light spilling onto a floor.
        val glow = Paint().apply {
            shader = RadialGradient(
                w / 2f, h * 1.1f, h * 0.9f,
                intArrayOf(withAlpha(accent, 70), withAlpha(SECOND, 25), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        // Perspective grid floor.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 38); strokeWidth = h / 900f + 0.6f }
        val horizon = h * 0.62f
        for (i in -14..14) {
            val x = w / 2f + i * w / 14f
            c.drawLine(w / 2f + i * w / 60f, horizon, x * 1f + i * w / 10f, h.toFloat(), grid)
        }
        var y = horizon
        var step = h / 60f
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y, grid); y += step; step *= 1.25f }
        // Fine dots in the sky.
        val dots = Paint().apply { color = withAlpha(accent, 22) }
        val g = h / 27f
        var yy = g
        while (yy < horizon) {
            var xx = g
            while (xx < w) { c.drawCircle(xx, yy, h / 900f + 0.5f, dots); xx += g }
            yy += g
        }
    }

    private fun drawLogo(ctx: Context, c: Canvas, w: Int, h: Int, accent: Int) {
        val size = h * 0.11f
        val text = "IGNYX"
        val typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.chakra_petch_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = h * 0.075f
            letterSpacing = 0.18f
            color = 0xFFEEF1FF.toInt()
        }
        val textW = tp.measureText(text)
        val gap = size * 0.25f
        val total = size + gap + textW
        val left = (w - total) / 2f
        val top = h * 0.08f
        drawMark(c, left, top, size, accent)
        val baseline = top + size / 2f - (tp.ascent() + tp.descent()) / 2f
        val glow = Paint(tp).apply { color = accent; maskFilter = BlurMaskFilter(h / 70f, BlurMaskFilter.Blur.NORMAL) }
        c.drawText(text, left + size + gap, baseline, glow)
        c.drawText(text, left + size + gap, baseline, tp)
    }

    /** The IGNYX mark (USB stick with a bolt) on a 120-unit grid. */
    private fun drawMark(c: Canvas, x: Float, y: Float, size: Float, accent: Int) {
        val u = size / 120f
        fun r(l: Float, t: Float, rw: Float, rh: Float) = RectF(x + l * u, y + t * u, x + (l + rw) * u, y + (t + rh) * u)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4 * u; color = accent; strokeJoin = Paint.Join.ROUND
        }
        val blur = Paint(stroke).apply { strokeWidth = 9 * u; maskFilter = BlurMaskFilter(6 * u, BlurMaskFilter.Blur.NORMAL) }
        for (p in listOf(blur, stroke)) {
            c.drawRoundRect(r(46f, 17f, 28f, 22f), 3 * u, 3 * u, p)
            c.drawRoundRect(r(37f, 39f, 46f, 64f), 12 * u, 12 * u, p)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawRect(r(52f, 23f, 5f, 7f), fill)
        c.drawRect(r(63f, 23f, 5f, 7f), fill)
        val bolt = Path().apply {
            moveTo(x + 65 * u, y + 49 * u); lineTo(x + 50 * u, y + 75 * u); lineTo(x + 60 * u, y + 75 * u)
            lineTo(x + 55 * u, y + 95 * u); lineTo(x + 72 * u, y + 66 * u); lineTo(x + 62 * u, y + 66 * u)
            lineTo(x + 67 * u, y + 49 * u); close()
        }
        c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SECOND; maskFilter = BlurMaskFilter(5 * u, BlurMaskFilter.Blur.NORMAL) })
        c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SECOND })
    }

    /** 9-slice pieces: a dark glassy menu panel with a neon edge, and a neon selection bar. */
    fun pieces(accent: Int): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val panel = 0xD80B0E1E.toInt()
        val n = 12
        for (s in BootTheme.PIECE_SUFFIXES) {
            out["menu_$s.png"] = png(piece(n, s, panel, accent, 2))
            out["select_$s.png"] = png(selectPiece(n, s, accent))
            out["slider_$s.png"] = png(Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(SECOND) })
        }
        return out
    }

    private fun piece(n: Int, s: String, fill: Int, edge: Int, line: Int): Bitmap {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        b.eraseColor(fill)
        val c = Canvas(b)
        val p = Paint().apply { color = edge }
        val soft = Paint().apply { color = withAlpha(edge, 70) }
        if ('n' in s) { c.drawRect(0f, 0f, n.toFloat(), line.toFloat(), p); c.drawRect(0f, line.toFloat(), n.toFloat(), line * 2f, soft) }
        if ('s' in s && s != "c") { c.drawRect(0f, n - line.toFloat(), n.toFloat(), n.toFloat(), p); c.drawRect(0f, n - line * 2f, n.toFloat(), n - line.toFloat(), soft) }
        if ('w' in s) { c.drawRect(0f, 0f, line.toFloat(), n.toFloat(), p); c.drawRect(line.toFloat(), 0f, line * 2f, n.toFloat(), soft) }
        if ('e' in s) { c.drawRect(n - line.toFloat(), 0f, n.toFloat(), n.toFloat(), p); c.drawRect(n - line * 2f, 0f, n - line.toFloat(), n.toFloat(), soft) }
        return b
    }

    private fun selectPiece(n: Int, s: String, accent: Int): Bitmap {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // A slight vertical sheen makes the bar look lit rather than flat.
        val sheen = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, n.toFloat(), lighten(accent), accent, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), sheen)
        if (s.length == 2) b.setPixel(if ('w' in s) 0 else n - 1, if ('n' in s) 0 else n - 1, Color.TRANSPARENT)
        return b
    }

    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun build(ctx: Context, o: ThemeOptions): BootTheme = BootTheme(
        width = o.width,
        height = o.height,
        background = png(background(ctx, o)),
        pieces = pieces(o.accent),
        itemColor = "#EEF1FF",
        selectedItemColor = "#04121A",
        hintColor = "#8E97C2",
    )

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun lighten(c: Int): Int {
        fun ch(v: Int) = (v + (255 - v) * 0.35f).toInt().coerceAtMost(255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }
}
