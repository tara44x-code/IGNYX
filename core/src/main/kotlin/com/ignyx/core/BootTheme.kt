package com.ignyx.core

import java.io.ByteArrayInputStream

/**
 * A Ventoy (GRUB2) menu theme. Images must already be encoded as PNG
 * (8-bit, non-interlaced), which both Android and the JVM produce by default.
 *
 * [pieces] holds the 9-slice images for the menu frame ("menu_*.png"),
 * the selected item ("select_*.png") and the scrollbar thumb ("slider_*.png").
 */
class BootTheme(
    val width: Int,
    val height: Int,
    val background: ByteArray,
    val pieces: Map<String, ByteArray>,
    val itemColor: String,
    val selectedItemColor: String,
    val hintColor: String,
    val nightColor: String = "#05060F",
    /** Colour of the small version line the boot loader prints in a corner. */
    val versionColor: String = "#3A4270",
    val menuLeftPct: Int = 18,
    val menuTopPct: Int = 30,
    val menuWidthPct: Int = 64,
    val menuHeightPct: Int = 52,
) {
    companion object {
        const val DIR = "ventoy/theme/ignyx"
        const val FILE = "$DIR/theme.txt"
        const val CONFIG = "ventoy/ventoy.json"
        val PIECE_SUFFIXES = listOf("nw", "n", "ne", "w", "c", "e", "sw", "s", "se")
    }

    val gfxmode: String get() = "${width}x$height"

    fun themeTxt(): String {
        val itemHeight = (height / 24).coerceIn(28, 56)
        return """
            |# IGNYX boot theme (https://github.com/tara44x-code/IGNYX)
            |title-text: ""
            |desktop-image: "background.png"
            |desktop-color: "$nightColor"
            |
            |+ boot_menu {
            |  left = $menuLeftPct%
            |  top = $menuTopPct%
            |  width = $menuWidthPct%
            |  height = $menuHeightPct%
            |  item_color = "$itemColor"
            |  selected_item_color = "$selectedItemColor"
            |  item_height = $itemHeight
            |  item_padding = ${itemHeight / 3}
            |  item_spacing = ${itemHeight / 6}
            |  icon_width = 0
            |  icon_height = 0
            |  item_icon_space = 0
            |  menu_pixmap_style = "menu_*.png"
            |  selected_item_pixmap_style = "select_*.png"
            |  scrollbar = true
            |  scrollbar_width = 6
            |  scrollbar_thumb = "slider_*.png"
            |}
            |
            |+ label {
            |  top = 90%
            |  left = 0
            |  width = 100%
            |  height = 24
            |  align = "center"
            |  color = "$hintColor"
            |  text = "Up / Down to choose    Enter to boot"
            |}
            |""".trimMargin()
    }
}

object ThemeWriter {

    /**
     * Writes the theme files and [ventoyJson] (which must already contain the theme section,
     * merged with any settings the user had) to the Ventoy data partition.
     */
    /**
     * The boot loader's JSON reader does not understand escaped slashes ("\/"), which
     * Android's org.json writes by default. Without this the theme path is not found
     * and the default menu appears instead.
     */
    fun sanitize(json: String): String = json.replace("\\/", "/")

    fun install(vol: ExFatVolume, theme: BootTheme, ventoyJson: String, onProgress: (Float) -> Unit = {}) {
        vol.mkdirs(BootTheme.DIR)
        val files = LinkedHashMap<String, ByteArray>()
        files["${BootTheme.DIR}/background.png"] = theme.background
        theme.pieces.forEach { (name, data) -> files["${BootTheme.DIR}/$name"] = data }
        files[BootTheme.FILE] = theme.themeTxt().toByteArray()
        files[BootTheme.CONFIG] = sanitize(ventoyJson).toByteArray()
        var i = 0
        for ((path, data) in files) {
            vol.createFile(path, data.size.toLong(), ByteArrayInputStream(data))
            i++
            onProgress(i.toFloat() / files.size)
        }
    }

    /** Removes the theme folder and writes [ventoyJson] (null deletes ventoy.json). */
    fun remove(vol: ExFatVolume, ventoyJson: String?) {
        vol.delete(BootTheme.DIR)
        if (ventoyJson == null) vol.delete(BootTheme.CONFIG)
        else sanitize(ventoyJson).toByteArray().let { vol.createFile(BootTheme.CONFIG, it.size.toLong(), ByteArrayInputStream(it)) }
    }

    /** A ventoy.json holding only the IGNYX theme. */
    fun freshJson(theme: BootTheme): String = """
        |{
        |  "theme": {
        |    "file": "/${BootTheme.FILE}",
        |    "gfxmode": "${theme.gfxmode}",
        |    "display_mode": "GUI",
        |    "ventoy_color": "${theme.versionColor}"
        |  }
        |}
        |""".trimMargin()
}
