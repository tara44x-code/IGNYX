package com.ignyx.core

import java.io.InputStream
import kotlin.random.Random

/**
 * The three binary pieces of an official Ventoy release:
 *  - boot.img        (MBR boot code)
 *  - core.img        (GRUB2 core, written to sectors 1..2047)
 *  - ventoy.disk.img (the 32 MiB VTOYEFI partition)
 * The streams must be already decompressed.
 */
class VentoyImages(
    val bootImg: ByteArray,
    val coreImg: () -> InputStream,
    val efiImg: () -> InputStream,
    val version: String = "",
)

object VentoyInstaller {

    enum class Step { Prepare, Bootloader, EfiPartition, Format, PartitionTable, Verify }

    /** Erases [dev] completely and makes it a Ventoy-compatible boot drive. */
    fun install(
        dev: BlockDevice,
        images: VentoyImages,
        label: String = "Ventoy",
        random: Random = Random.Default,
        onProgress: (Step, Float) -> Unit = { _, _ -> },
    ): VentoyLayout.DiskLayout {
        require(images.bootImg.size >= VentoyLayout.BOOT_CODE_SIZE) { "boot.img is too small" }
        val layout = VentoyLayout.plan(dev.sectorCount)

        // Invalidate the old partition table first, so nobody mounts a half-written drive.
        onProgress(Step.Prepare, 0f)
        dev.zero(0, VentoyLayout.DATA_START_SECTOR)
        val tail = dev.sectorCount - (layout.efi.endSector + 1)
        if (tail > 0) dev.zero(layout.efi.endSector + 1, tail) // also clears an old GPT backup header
        onProgress(Step.Prepare, 1f)

        // GRUB core image: sectors 1..2047.
        onProgress(Step.Bootloader, 0f)
        val maxCore = (VentoyLayout.DATA_START_SECTOR - 1).toInt()
        streamToDisk(dev, images.coreImg, 1, maxCore.toLong(), "core.img") { onProgress(Step.Bootloader, it) }

        // VTOYEFI partition.
        onProgress(Step.EfiPartition, 0f)
        streamToDisk(dev, images.efiImg, layout.efi.startSector, layout.efi.sectorCount, "ventoy.disk.img") {
            onProgress(Step.EfiPartition, it)
        }

        // Data partition.
        ExFatFormatter.format(dev, layout.data.startSector, layout.data.sectorCount, label, random.nextInt()) {
            onProgress(Step.Format, it)
        }

        // MBR last.
        onProgress(Step.PartitionTable, 0f)
        val uuid = ByteArray(16).also { random.nextBytes(it) }
        val mbr = VentoyLayout.buildMbr(layout, images.bootImg, random.nextInt(), uuid)
        dev.writeSectors(0, mbr)
        dev.flush()
        onProgress(Step.PartitionTable, 1f)

        // Read it all back.
        onProgress(Step.Verify, 0f)
        val back = dev.readSectors(0, 1)
        check(back.contentEquals(mbr)) { "التحقق فشل: جدول الأقسام ما انكتب صح" }
        val detected = VentoyLayout.detect(back, dev.sectorCount)
        check(detected is VentoyLayout.Detection.Ventoy) { "التحقق فشل: الفلاشة ما تبين كـ Ventoy" }
        ExFatVolume.open(dev, layout.data.startSector)
        onProgress(Step.Verify, 1f)
        return layout
    }

    private fun streamToDisk(
        dev: BlockDevice,
        open: () -> InputStream,
        startLba: Long,
        maxSectors: Long,
        what: String,
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
                if (written + sectors > maxSectors) error("$what أكبر من المساحة المخصصة له")
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
