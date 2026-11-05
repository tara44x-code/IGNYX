package com.ignyx.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.ignyx.core.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw access to a USB flash drive over the USB Mass Storage "Bulk-Only Transport"
 * protocol, using SCSI commands. Works without root through Android's USB host API.
 */
class ScsiBlockDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : BlockDevice {

    override var sectorCount: Long = 0
        private set
    var vendor: String = ""
        private set
    var product: String = ""
        private set

    private var tag = 1
    private val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
    private val csw = ByteArray(13)

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val MAX_SECTORS_PER_COMMAND = 128 // 64 KiB per READ/WRITE(10)
        private const val MAX_BULK = 16 * 1024

        /** Finds the bulk-only mass storage interface of [device], if it has one. */
        fun findInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }

        fun open(manager: UsbManager, device: UsbDevice): ScsiBlockDevice {
            val intf = findInterface(device) ?: throw IOException("الجهاز مو فلاشة USB مدعومة")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) throw IOException("الفلاشة ما بيها منافذ نقل مناسبة")
            val conn = manager.openDevice(device) ?: throw IOException("ما كدرت أفتح الفلاشة")
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("الفلاشة مشغولة بتطبيق ثاني")
            }
            return ScsiBlockDevice(conn, intf, inEp, outEp).also {
                try {
                    it.initialise()
                } catch (e: Exception) {
                    it.close()
                    throw e
                }
            }
        }
    }

    private fun initialise() {
        val inquiry = ByteArray(36)
        command(byteArrayOf(0x12, 0, 0, 0, 36, 0), inquiry, dataIn = true)
        vendor = String(inquiry, 8, 8, Charsets.US_ASCII).trim()
        product = String(inquiry, 16, 16, Charsets.US_ASCII).trim()

        // Drives often report "unit attention" right after being plugged in.
        var ready = false
        for (attempt in 0 until 6) {
            if (runCatching { command(ByteArray(6), null, dataIn = false) }.isSuccess) { ready = true; break }
            requestSense()
            Thread.sleep(300)
        }
        if (!ready) throw IOException("الفلاشة مو جاهزة")

        val cap = ByteArray(8)
        command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), cap, dataIn = true)
        val buf = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.getInt(0).toLong() and 0xFFFF_FFFFL
        val blockSize = buf.getInt(4)
        if (blockSize != BlockDevice.SECTOR) throw IOException("حجم القطاع $blockSize غير مدعوم")
        sectorCount = lastLba + 1
    }

    override fun read(lba: Long, buf: ByteArray, off: Int, sectors: Int) {
        var done = 0
        while (done < sectors) {
            val n = minOf(MAX_SECTORS_PER_COMMAND, sectors - done)
            val data = ByteArray(n * BlockDevice.SECTOR)
            command(rw10(0x28, lba + done, n), data, dataIn = true)
            data.copyInto(buf, off + done * BlockDevice.SECTOR)
            done += n
        }
    }

    override fun write(lba: Long, buf: ByteArray, off: Int, sectors: Int) {
        var done = 0
        while (done < sectors) {
            val n = minOf(MAX_SECTORS_PER_COMMAND, sectors - done)
            val start = off + done * BlockDevice.SECTOR
            val data = buf.copyOfRange(start, start + n * BlockDevice.SECTOR)
            command(rw10(0x2A, lba + done, n), data, dataIn = false)
            done += n
        }
    }

    override fun flush() {
        runCatching { command(byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, dataIn = false) }
    }

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        connection.close()
    }

    private fun rw10(op: Int, lba: Long, sectors: Int): ByteArray {
        require(lba <= 0xFFFF_FFFFL) { "LBA out of range" }
        return byteArrayOf(
            op.toByte(), 0,
            (lba shr 24).toByte(), (lba shr 16).toByte(), (lba shr 8).toByte(), lba.toByte(),
            0, (sectors shr 8).toByte(), sectors.toByte(), 0,
        )
    }

    private fun requestSense() {
        runCatching { command(byteArrayOf(0x03, 0, 0, 0, 18, 0), ByteArray(18), dataIn = true, sense = false) }
    }

    /** One SCSI command: CBW, optional data phase, CSW. */
    @Synchronized
    private fun command(cdb: ByteArray, data: ByteArray?, dataIn: Boolean, sense: Boolean = true) {
        val length = data?.size ?: 0
        val myTag = tag++
        cbw.clear()
        cbw.putInt(0x43425355) // "USBC"
        cbw.putInt(myTag)
        cbw.putInt(length)
        cbw.put(if (dataIn) 0x80.toByte() else 0)
        cbw.put(0) // LUN 0
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        while (cbw.position() < 31) cbw.put(0)
        bulk(outEp, cbw.array(), 0, 31)

        if (data != null && length > 0) {
            var done = 0
            while (done < length) {
                val n = minOf(MAX_BULK, length - done)
                val got = connection.bulkTransfer(if (dataIn) inEp else outEp, data, done, n, TIMEOUT_MS)
                if (got < 0) {
                    clearHalt(if (dataIn) inEp else outEp)
                    break
                }
                done += got
                if (dataIn && got < n) break // short read
            }
        }

        var got = connection.bulkTransfer(inEp, csw, 0, 13, TIMEOUT_MS)
        if (got < 0) {
            clearHalt(inEp)
            got = connection.bulkTransfer(inEp, csw, 0, 13, TIMEOUT_MS)
        }
        if (got != 13) throw IOException("انقطع الاتصال ويا الفلاشة")
        val s = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (s.getInt(0) != 0x53425355 || s.getInt(4) != myTag) throw IOException("رد غير متوقع من الفلاشة")
        val status = csw[12].toInt()
        if (status != 0) {
            if (sense) requestSense()
            throw IOException("الفلاشة رفضت الأمر (0x%02X)".format(cdb[0].toInt() and 0xFF))
        }
    }

    private fun bulk(ep: UsbEndpoint, b: ByteArray, off: Int, len: Int) {
        val n = connection.bulkTransfer(ep, b, off, len, TIMEOUT_MS)
        if (n != len) {
            clearHalt(ep)
            throw IOException("فشل الإرسال للفلاشة")
        }
    }

    private fun clearHalt(ep: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT_MS)
    }
}
