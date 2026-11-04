package com.ignyx.app.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import java.io.InputStream

data class IsoEntry(val name: String, val sizeBytes: Long)

data class DriveInfo(
    val label: String,
    val capacityBytes: Long,
    val usedBytes: Long,
    val isos: List<IsoEntry>,
)

sealed interface DriveState {
    data object Disconnected : DriveState
    data class NeedsPermission(val productName: String) : DriveState
    data class Ready(val info: DriveInfo) : DriveState
    data class Unsupported(val productName: String, val reason: String) : DriveState
}

/**
 * Talks to USB flash drives plugged in over OTG, using libaums (no root needed).
 * All public functions except [requestPermission] do blocking I/O: call them off the main thread.
 */
class UsbDriveManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbMassStorageDevice? = null
    private var fs: FileSystem? = null

    fun scan(): DriveState {
        val found = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull()
        if (found == null) {
            close()
            return DriveState.Disconnected
        }
        val name = found.usbDevice.productName ?: "USB"
        if (!usbManager.hasPermission(found.usbDevice)) return DriveState.NeedsPermission(name)

        if (device?.usbDevice?.deviceName != found.usbDevice.deviceName) {
            close()
            found.init()
            device = found
        }
        val partition = device?.partitions?.firstOrNull()
            ?: return DriveState.Unsupported(name, "ما لكيت قسم مدعوم. الفلاشة تحتاج تجهيز.")
        val fileSystem = partition.fileSystem
        fs = fileSystem
        return DriveState.Ready(readInfo(fileSystem))
    }

    fun requestPermission() {
        val found = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull() ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(found.usbDevice, pending)
    }

    /** Copies one file into the drive's root folder, reporting progress 0..1. */
    fun copyToRoot(fileName: String, sizeBytes: Long, input: InputStream, onProgress: (Float) -> Unit) {
        val fileSystem = fs ?: error("الفلاشة غير جاهزة")
        if (sizeBytes >= FAT32_MAX_FILE) {
            error("الملف أكبر من 4GB. هذا يحتاج قسم exFAT، وراح ينضاف بالمرحلة الجاية.")
        }
        if (sizeBytes > fileSystem.freeSpace) error("ماكو مساحة كافية على الفلاشة")

        val root = fileSystem.rootDirectory
        root.search(fileName)?.delete()
        val target: UsbFile = root.createFile(fileName)
        target.length = sizeBytes
        UsbFileOutputStream(target).use { out ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (sizeBytes > 0) onProgress((done.toFloat() / sizeBytes).coerceAtMost(1f))
            }
        }
        onProgress(1f)
    }

    fun close() {
        runCatching { device?.close() }
        device = null
        fs = null
    }

    private fun readInfo(fileSystem: FileSystem): DriveInfo {
        val isos = fileSystem.rootDirectory.listFiles()
            .filter { !it.isDirectory && it.name.lowercase().let { n -> BOOT_EXT.any(n::endsWith) } }
            .map { IsoEntry(it.name, it.length) }
            .sortedBy { it.name.lowercase() }
        return DriveInfo(
            label = fileSystem.volumeLabel.trim().ifEmpty { "USB" },
            capacityBytes = fileSystem.capacity,
            usedBytes = fileSystem.occupiedSpace,
            isos = isos,
        )
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.ignyx.app.USB_PERMISSION"
        private const val FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024
        private val BOOT_EXT = listOf(".iso", ".img", ".wim", ".vhd", ".vhdx", ".efi")
    }
}
