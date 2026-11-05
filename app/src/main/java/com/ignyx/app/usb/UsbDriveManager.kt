package com.ignyx.app.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.ignyx.core.ExFatVolume
import com.ignyx.core.VentoyImages
import com.ignyx.core.VentoyInstaller
import com.ignyx.core.VentoyLayout
import com.ignyx.core.readSectors
import org.tukaani.xz.XZInputStream
import java.io.InputStream

data class IsoEntry(val name: String, val sizeBytes: Long)

data class DriveInfo(
    val name: String,
    val diskBytes: Long,
    val capacityBytes: Long,
    val freeBytes: Long,
    val isos: List<IsoEntry>,
) {
    val usedBytes: Long get() = capacityBytes - freeBytes
}

sealed interface DriveState {
    data object Disconnected : DriveState
    data class NeedsPermission(val name: String) : DriveState
    /** A drive that is not prepared yet (or prepared in a mode we do not support). */
    data class NotPrepared(val name: String, val diskBytes: Long, val gpt: Boolean) : DriveState
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

    private fun displayName(d: UsbDevice, scsi: ScsiBlockDevice? = null): String =
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
        val diskBytes = dev.sectorCount * 512

        return when (val d = VentoyLayout.detect(dev.readSectors(0, 1), dev.sectorCount)) {
            is VentoyLayout.Detection.Ventoy -> {
                val vol = runCatching { ExFatVolume.open(dev, d.layout.data.startSector) }.getOrNull()
                    ?: return DriveState.NotPrepared(name, diskBytes, gpt = false)
                volume = vol
                DriveState.Ready(info(name, diskBytes, vol))
            }
            VentoyLayout.Detection.GptDisk -> DriveState.NotPrepared(name, diskBytes, gpt = true)
            VentoyLayout.Detection.Other -> DriveState.NotPrepared(name, diskBytes, gpt = false)
        }
    }

    private fun info(name: String, diskBytes: Long, vol: ExFatVolume) = DriveInfo(
        name = name,
        diskBytes = diskBytes,
        capacityBytes = vol.capacityBytes,
        freeBytes = vol.freeBytes,
        isos = vol.list()
            .filter { !it.isDirectory && BOOT_EXT.any { ext -> it.name.lowercase().endsWith(ext) } }
            .map { IsoEntry(it.name, it.sizeBytes) }
            .sortedBy { it.name.lowercase() },
    )

    fun requestPermission() {
        val found = findDrive() ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(found, pending)
    }

    /** Erases the drive and installs the Ventoy boot files. */
    fun install(onProgress: (VentoyInstaller.Step, Float) -> Unit) {
        val dev = disk ?: error("الفلاشة غير متصلة")
        volume = null
        VentoyInstaller.install(dev, loadImages(), onProgress = onProgress)
    }

    fun copyToDrive(fileName: String, sizeBytes: Long, input: InputStream, onProgress: (Float) -> Unit) {
        val vol = volume ?: error("الفلاشة غير جاهزة")
        vol.createFile(fileName, sizeBytes, input, onProgress)
    }

    fun delete(fileName: String): Boolean = volume?.delete(fileName) ?: false

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
        if (!files.containsAll(needed)) error("ملفات Ventoy مو موجودة بهاي النسخة من التطبيق")
        return VentoyImages(
            bootImg = assets.open("ventoy/boot.img").use { it.readBytes() },
            coreImg = { XZInputStream(assets.open("ventoy/core.img.xz")) },
            efiImg = { XZInputStream(assets.open("ventoy/ventoy.disk.img.xz")) },
            version = if ("VERSION" in files) assets.open("ventoy/VERSION").use { String(it.readBytes()).trim() } else "",
        )
    }

    fun ventoyVersion(): String = runCatching {
        context.assets.open("ventoy/VERSION").use { String(it.readBytes()).trim() }
    }.getOrDefault("")

    companion object {
        const val ACTION_USB_PERMISSION = "com.ignyx.app.USB_PERMISSION"
        private val BOOT_EXT = listOf(".iso", ".img", ".wim", ".vhd", ".vhdx", ".efi", ".vtoy")
    }
}
