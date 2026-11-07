package com.ignyx.app.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.ignyx.app.isBootable
import com.ignyx.core.BootTheme
import com.ignyx.core.DriveReport
import com.ignyx.core.DriveTools
import com.ignyx.core.ExFatVolume
import com.ignyx.core.ThemeWriter
import com.ignyx.core.VentoyImages
import com.ignyx.core.VentoyInstaller
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.InputStream

data class FileItem(val name: String, val path: String, val sizeBytes: Long, val isDirectory: Boolean) {
    val bootable: Boolean get() = !isDirectory && isBootable(name)
}

data class DriveInfo(
    val name: String,
    val report: DriveReport,
    val capacityBytes: Long,
    val freeBytes: Long,
    val bootableBytes: Long,
    val bootableCount: Int,
    val hasTheme: Boolean,
) {
    val diskBytes: Long get() = report.totalSectors * 512
    val usedBytes: Long get() = capacityBytes - freeBytes
    val otherBytes: Long get() = (usedBytes - bootableBytes).coerceAtLeast(0)
}

sealed interface DriveState {
    data object Disconnected : DriveState
    data class NeedsPermission(val name: String) : DriveState
    /** A drive that is not prepared yet (or prepared in a mode we do not support). */
    data class NotPrepared(val name: String, val report: DriveReport) : DriveState {
        val diskBytes: Long get() = report.totalSectors * 512
    }
    data class Ready(val info: DriveInfo) : DriveState
    data class Failed(val name: String, val reason: String) : DriveState
}

/**
 * Talks to a USB flash drive plugged in over OTG. Everything except [requestPermission]
 * does blocking I/O and must run off the main thread, one call at a time.
 */
class UsbDriveManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var disk: ScsiBlockDevice? = null
    private var volume: ExFatVolume? = null

    private fun findDrive(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { ScsiBlockDevice.findInterface(it) != null }

    private fun displayName(d: UsbDevice, scsi: ScsiBlockDevice?): String =
        listOfNotNull(scsi?.vendor?.ifBlank { null }, scsi?.product?.ifBlank { null }).joinToString(" ")
            .ifBlank { d.productName ?: "فلاشة USB" }

    fun scan(): DriveState {
        val found = findDrive() ?: run { close(); return DriveState.Disconnected }
        if (!usbManager.hasPermission(found)) return DriveState.NeedsPermission(found.productName ?: "فلاشة USB")

        if (usbDevice?.deviceName != found.deviceName || disk == null) {
            close()
            disk = ScsiBlockDevice.open(usbManager, found)
            usbDevice = found
        }
        val dev = disk!!
        val name = displayName(found, dev)
        val report = DriveTools.inspect(dev)
        val layout = report.layout
        if (!report.isVentoy || layout == null || report.fileSystem == null) {
            volume = null
            return DriveState.NotPrepared(name, report)
        }
        val vol = ExFatVolume.open(dev, layout.data.startSector)
        volume = vol
        var bootBytes = 0L
        var bootCount = 0
        fun walk(path: String, depth: Int) {
            for (e in vol.list(path)) {
                val p = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.isDirectory) {
                    if (depth < 4 && !(path.isEmpty() && e.name.equals("ventoy", true))) walk(p, depth + 1)
                } else if (isBootable(e.name)) {
                    bootBytes += e.sizeBytes; bootCount++
                }
            }
        }
        walk("", 0)
        return DriveState.Ready(
            DriveInfo(
                name = name,
                report = report,
                capacityBytes = vol.capacityBytes,
                freeBytes = vol.freeBytes,
                bootableBytes = bootBytes,
                bootableCount = bootCount,
                hasTheme = vol.exists(BootTheme.FILE),
            )
        )
    }

    fun list(path: String): List<FileItem> {
        val vol = requireVolume()
        return vol.list(path).map {
            FileItem(it.name, if (path.isEmpty()) it.name else "$path/${it.name}", it.sizeBytes, it.isDirectory)
        }.sortedWith(compareBy<FileItem>({ !it.isDirectory }, { !it.bootable }, { it.name.lowercase() }))
    }

    private fun requireVolume() = volume ?: error("الفلاشة غير جاهزة")

    fun requestPermission() {
        val found = findDrive() ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(found, pending)
    }

    // ------------------------------------------------------------- whole-drive operations

    fun install(label: String, reserveBytes: Long, onProgress: (VentoyInstaller.Step, Float) -> Unit) {
        val dev = disk ?: error("الفلاشة غير متصلة")
        volume = null
        VentoyInstaller.install(dev, loadImages(), label = label, reserveSectors = reserveBytes / 512, onProgress = onProgress)
    }

    fun updateVentoy(onProgress: (Float) -> Unit) {
        val dev = disk ?: error("الفلاشة غير متصلة")
        DriveTools.updateVentoy(dev, loadImages(), onProgress)
    }

    fun restorePlain(fs: DriveTools.PlainFs, label: String, onProgress: (Float) -> Unit) {
        val dev = disk ?: error("الفلاشة غير متصلة")
        volume = null
        DriveTools.restorePlain(dev, fs, label, onProgress = onProgress)
    }

    // ------------------------------------------------------------- files

    fun copyToDrive(path: String, sizeBytes: Long, input: InputStream, onProgress: (Float) -> Unit) =
        requireVolume().createFile(path, sizeBytes, input, onProgress)

    fun readBack(path: String, onChunk: (ByteArray, Int) -> Unit) = requireVolume().readFileChunks(path, onChunk)

    fun mkdir(path: String) = requireVolume().mkdirs(path)

    fun delete(path: String): Boolean = requireVolume().delete(path)

    fun freeBytes(): Long = requireVolume().freeBytes

    fun largestFreeRun(): Long = requireVolume().largestFreeRunBytes()

    // ------------------------------------------------------------- theme

    private fun readJson(): JSONObject? {
        val vol = requireVolume()
        if (!vol.exists(BootTheme.CONFIG)) return null
        return runCatching { JSONObject(String(vol.readFile(BootTheme.CONFIG), Charsets.UTF_8)) }.getOrNull()
    }

    fun installTheme(theme: BootTheme, onProgress: (Float) -> Unit) {
        val vol = requireVolume()
        val hadFile = vol.exists(BootTheme.CONFIG)
        val json = readJson() ?: run {
            // Keep an unreadable ventoy.json instead of silently dropping the user's settings.
            if (hadFile) vol.readFile(BootTheme.CONFIG).let {
                vol.createFile("ventoy/ventoy.json.bak", it.size.toLong(), it.inputStream())
            }
            JSONObject()
        }
        json.put(
            "theme",
            JSONObject()
                .put("file", "/${BootTheme.FILE}")
                .put("gfxmode", theme.gfxmode)
                .put("display_mode", "GUI")
                .put("ventoy_color", theme.versionColor),
        )
        ThemeWriter.install(vol, theme, json.toString(2), onProgress)
    }

    fun removeTheme() {
        val vol = requireVolume()
        val json = readJson()
        json?.remove("theme")
        ThemeWriter.remove(vol, if (json == null || json.length() == 0) null else json.toString(2))
    }

    // ------------------------------------------------------------- misc

    fun close() {
        runCatching { disk?.close() }
        disk = null
        usbDevice = null
        volume = null
    }

    private fun loadImages(): VentoyImages {
        val assets = context.assets
        val files = assets.list("ventoy")?.toSet().orEmpty()
        val needed = listOf("boot.img", "core.img.xz", "ventoy.disk.img.xz")
        if (!files.containsAll(needed)) error("ملفات الإقلاع مو موجودة بهاي النسخة من التطبيق")
        return VentoyImages(
            bootImg = assets.open("ventoy/boot.img").use { it.readBytes() },
            coreImg = { XZInputStream(assets.open("ventoy/core.img.xz")) },
            efiImg = { XZInputStream(assets.open("ventoy/ventoy.disk.img.xz")) },
            version = appVentoyVersion(),
        )
    }

    fun appVentoyVersion(): String = runCatching {
        context.assets.open("ventoy/VERSION").use { String(it.readBytes()).trim() }
    }.getOrDefault("")

    companion object {
        const val ACTION_USB_PERMISSION = "com.ignyx.app.USB_PERMISSION"
    }
}
