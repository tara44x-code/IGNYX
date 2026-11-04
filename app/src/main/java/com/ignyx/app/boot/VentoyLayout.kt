package com.ignyx.app.boot

/**
 * Disk layout compatible with Ventoy (MBR mode):
 *
 *   sector 0            MBR (boot code + partition table)
 *   1 .. 2047           GRUB2 core image (core.img)
 *   partition 1         big data partition (exFAT) where the user drops ISO files
 *   partition 2         32 MiB "VTOYEFI" FAT partition (ventoy.disk.img), at the end
 *
 * Ventoy is GPLv3 software by longpanda: https://github.com/ventoy/Ventoy
 * The boot images themselves are taken unchanged from an official Ventoy release.
 */
object VentoyLayout {
    const val SECTOR_SIZE = 512
    const val DATA_START_SECTOR = 2048L
    const val EFI_PART_SECTORS = 32L * 1024 * 1024 / SECTOR_SIZE // 65536
    private const val MBR_MAX_SECTORS = 0xFFFF_FFFFL

    const val TYPE_EXFAT_NTFS = 0x07
    const val TYPE_EFI = 0xEF

    data class Partition(val startSector: Long, val sectorCount: Long, val type: Int, val bootable: Boolean) {
        val endSector: Long get() = startSector + sectorCount - 1
        val sizeBytes: Long get() = sectorCount * SECTOR_SIZE
    }

    data class DiskLayout(val totalSectors: Long, val data: Partition, val efi: Partition)

    /**
     * @param totalSectors size of the whole USB drive in 512-byte sectors
     * @param reserveSectors optional space left unused at the end of the disk
     */
    fun plan(totalSectors: Long, reserveSectors: Long = 0): DiskLayout {
        require(reserveSectors >= 0) { "reserveSectors must be >= 0" }
        require(totalSectors <= MBR_MAX_SECTORS) { "Drive larger than 2 TiB needs GPT mode (not supported yet)" }
        val minimum = DATA_START_SECTOR + EFI_PART_SECTORS + 2048 + reserveSectors
        require(totalSectors >= minimum) { "Drive too small: $totalSectors sectors, need at least $minimum" }

        var dataSectors = totalSectors - reserveSectors - EFI_PART_SECTORS - DATA_START_SECTOR
        dataSectors -= dataSectors % 8 // keep partition 2 aligned to 4 KiB

        val data = Partition(DATA_START_SECTOR, dataSectors, TYPE_EXFAT_NTFS, bootable = true)
        val efi = Partition(DATA_START_SECTOR + dataSectors, EFI_PART_SECTORS, TYPE_EFI, bootable = false)
        return DiskLayout(totalSectors, data, efi)
    }

    /**
     * Builds sector 0. [bootCode] is the first 440 bytes of Ventoy's boot.img;
     * pass null to get an empty boot area (useful for tests).
     */
    fun buildMbr(layout: DiskLayout, bootCode: ByteArray?, diskSignature: Int): ByteArray {
        val mbr = ByteArray(SECTOR_SIZE)
        bootCode?.copyInto(mbr, 0, 0, minOf(440, bootCode.size))
        putLe32(mbr, 440, diskSignature.toLong() and 0xFFFF_FFFFL)
        writeEntry(mbr, 446, layout.data)
        writeEntry(mbr, 462, layout.efi)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    private fun writeEntry(mbr: ByteArray, offset: Int, p: Partition) {
        mbr[offset] = if (p.bootable) 0x80.toByte() else 0x00
        // CHS fields are set to the "use LBA" marker
        mbr[offset + 1] = 0xFE.toByte(); mbr[offset + 2] = 0xFF.toByte(); mbr[offset + 3] = 0xFF.toByte()
        mbr[offset + 4] = p.type.toByte()
        mbr[offset + 5] = 0xFE.toByte(); mbr[offset + 6] = 0xFF.toByte(); mbr[offset + 7] = 0xFF.toByte()
        putLe32(mbr, offset + 8, p.startSector)
        putLe32(mbr, offset + 12, p.sectorCount)
    }

    private fun putLe32(buf: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) buf[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
}
