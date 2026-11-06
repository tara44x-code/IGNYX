package com.ignyx.core

import kotlin.random.Random

/** What IGNYX knows about a connected drive. */
data class DriveReport(
    val totalSectors: Long,
    val partitionStyle: String,          // "MBR", "GPT" or "—"
    val isVentoy: Boolean,
    val fileSystem: String?,             // "exFAT" for a Ventoy data partition
    val clusterBytes: Int?,
    val ventoyVersion: String?,          // installed on the drive
    val layout: VentoyLayout.DiskLayout?,
)

object DriveTools {

    private val versionRegex = Regex("""VENTOY_VERSION\s*=\s*"?([0-9][0-9A-Za-z.\-]*)"?""")

    /** Reads the Ventoy version from the VTOYEFI partition's grub.cfg. */
    fun ventoyVersion(dev: BlockDevice, layout: VentoyLayout.DiskLayout): String? = runCatching {
        val cfg = FatReader(dev, layout.efi.startSector).readFile("grub/grub.cfg") ?: return null
        versionRegex.find(String(cfg, Charsets.UTF_8))?.groupValues?.get(1)
    }.getOrNull()

    fun inspect(dev: BlockDevice): DriveReport {
        val mbr = dev.readSectors(0, 1)
        return when (val d = VentoyLayout.detect(mbr, dev.sectorCount)) {
            is VentoyLayout.Detection.Ventoy -> {
                val vol = runCatching { ExFatVolume.open(dev, d.layout.data.startSector) }.getOrNull()
                DriveReport(
                    totalSectors = dev.sectorCount,
                    partitionStyle = "MBR",
                    isVentoy = true,
                    fileSystem = if (vol != null) "exFAT" else null,
                    clusterBytes = vol?.clusterBytes,
                    ventoyVersion = ventoyVersion(dev, d.layout),
                    layout = d.layout,
                )
            }
            VentoyLayout.Detection.GptDisk -> DriveReport(dev.sectorCount, "GPT", false, null, null, null, null)
            VentoyLayout.Detection.Other -> {
                val hasTable = (mbr[510].toInt() and 0xFF) == 0x55 && (mbr[511].toInt() and 0xFF) == 0xAA
                DriveReport(dev.sectorCount, if (hasTable) "MBR" else "—", false, null, null, null, null)
            }
        }
    }

    /**
     * Updates the Ventoy boot files on an already prepared drive. The data partition,
     * its files, the partition table and the disk identity are all kept.
     */
    fun updateVentoy(dev: BlockDevice, images: VentoyImages, onProgress: (Float) -> Unit = {}) {
        val old = dev.readSectors(0, 1)
        val d = VentoyLayout.detect(old, dev.sectorCount)
        check(d is VentoyLayout.Detection.Ventoy) { "الفلاشة مو مجهّزة بـ Ventoy" }
        val layout = d.layout

        // Boot code only: keep the Ventoy UUID (384..399) and everything from 440 on.
        val mbr = old.copyOf()
        images.bootImg.copyInto(mbr, 0, 0, VentoyLayout.BOOT_CODE_SIZE)
        old.copyInto(mbr, VentoyLayout.UUID_OFFSET, VentoyLayout.UUID_OFFSET, VentoyLayout.UUID_OFFSET + 16)

        onProgress(0.02f)
        dev.zero(1, VentoyLayout.DATA_START_SECTOR - 1)
        writeStream(dev, images.coreImg, 1, VentoyLayout.DATA_START_SECTOR - 1) { onProgress(0.02f + it * 0.08f) }
        writeStream(dev, images.efiImg, layout.efi.startSector, layout.efi.sectorCount) { onProgress(0.1f + it * 0.85f) }
        dev.writeSectors(0, mbr)
        dev.flush()
        check(dev.readSectors(0, 1).contentEquals(mbr)) { "التحقق فشل بعد التحديث" }
        ExFatVolume.open(dev, layout.data.startSector)
        onProgress(1f)
    }

    enum class PlainFs { FAT32, EXFAT }

    /** Turns the drive back into an ordinary USB stick with one partition. */
    fun restorePlain(
        dev: BlockDevice,
        fs: PlainFs,
        label: String,
        random: Random = Random.Default,
        onProgress: (Float) -> Unit = {},
    ) {
        val start = VentoyLayout.DATA_START_SECTOR
        val total = minOf(dev.sectorCount, 0xFFFF_FFFFL)
        var count = total - start
        count -= count % 8
        dev.zero(0, start) { onProgress(0.02f * it / start) }
        val tail = dev.sectorCount - (start + count)
        if (tail > 0) dev.zero(start + count, tail)
        when (fs) {
            PlainFs.FAT32 -> Fat32Formatter.format(dev, start, count, label, random.nextInt()) { onProgress(0.02f + it * 0.96f) }
            PlainFs.EXFAT -> ExFatFormatter.format(dev, start, count, label, random.nextInt()) { onProgress(0.02f + it * 0.96f) }
        }
        val mbr = ByteArray(512)
        Bytes.putLe32(mbr, 440, random.nextInt().toLong() and 0xFFFF_FFFFL)
        val e = 446
        mbr[e + 1] = 0xFE.toByte(); mbr[e + 2] = 0xFF.toByte(); mbr[e + 3] = 0xFF.toByte()
        mbr[e + 4] = (if (fs == PlainFs.FAT32) 0x0C else 0x07).toByte()
        mbr[e + 5] = 0xFE.toByte(); mbr[e + 6] = 0xFF.toByte(); mbr[e + 7] = 0xFF.toByte()
        Bytes.putLe32(mbr, e + 8, start)
        Bytes.putLe32(mbr, e + 12, count)
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        dev.writeSectors(0, mbr)
        dev.flush()
        onProgress(1f)
    }

    internal fun writeStream(
        dev: BlockDevice,
        open: () -> java.io.InputStream,
        startLba: Long,
        maxSectors: Long,
        onProgress: (Float) -> Unit,
    ) {
        val chunk = ByteArray(1 shl 20)
        var written = 0L
        open().use { input ->
            while (true) {
                var got = 0
                while (got < chunk.size) {
                    val n = input.read(chunk, got, chunk.size - got)
                    if (n < 0) break
                    got += n
                }
                if (got == 0) break
                val sectors = Bytes.ceilDiv(got.toLong(), 512)
                check(written + sectors <= maxSectors) { "الصورة أكبر من المساحة المخصصة لها" }
                if (got % 512 != 0) chunk.fill(0, got, (sectors * 512).toInt())
                dev.write(startLba + written, chunk, 0, sectors.toInt())
                written += sectors
                onProgress((written.toFloat() / maxSectors).coerceAtMost(1f))
                if (got < chunk.size) break
            }
        }
        onProgress(1f)
    }
}
