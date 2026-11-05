package com.ignyx.core

/**
 * Disk layout compatible with Ventoy (MBR mode):
 *
 *   sector 0            MBR (boot code from boot.img + Ventoy disk UUID + partition table)
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
    const val TYPE_GPT_PROTECTIVE = 0xEE

    /** Where Ventoy stores its 16-byte disk UUID inside sector 0. */
    const val UUID_OFFSET = 384
    const val BOOT_CODE_SIZE = 440

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
     * Builds sector 0. [bootCode] is Ventoy's boot.img (only its first 440 bytes are used);
     * pass null to get an empty boot area (useful for tests).
     */
    fun buildMbr(layout: DiskLayout, bootCode: ByteArray?, diskSignature: Int, diskUuid: ByteArray? = null): ByteArray {
        val mbr = ByteArray(SECTOR_SIZE)
        bootCode?.copyInto(mbr, 0, 0, minOf(BOOT_CODE_SIZE, bootCode.size))
        diskUuid?.let {
            require(it.size == 16) { "disk UUID must be 16 bytes" }
            it.copyInto(mbr, UUID_OFFSET)
        }
        Bytes.putLe32(mbr, 440, diskSignature.toLong() and 0xFFFF_FFFFL)
        writeEntry(mbr, 446, layout.data)
        writeEntry(mbr, 462, layout.efi)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    sealed interface Detection {
        data class Ventoy(val layout: DiskLayout) : Detection
        data object GptDisk : Detection
        data object Other : Detection
    }

    /** Recognises a Ventoy-style MBR disk (made by IGNYX or by Ventoy on a PC). */
    fun detect(mbr: ByteArray, totalSectors: Long): Detection {
        if (mbr.size < SECTOR_SIZE) return Detection.Other
        if ((mbr[510].toInt() and 0xFF) != 0x55 || (mbr[511].toInt() and 0xFF) != 0xAA) return Detection.Other
        val type1 = mbr[446 + 4].toInt() and 0xFF
        if (type1 == TYPE_GPT_PROTECTIVE) return Detection.GptDisk
        val start1 = Bytes.le32(mbr, 446 + 8)
        val count1 = Bytes.le32(mbr, 446 + 12)
        val type2 = mbr[462 + 4].toInt() and 0xFF
        val start2 = Bytes.le32(mbr, 462 + 8)
        val count2 = Bytes.le32(mbr, 462 + 12)
        val looksVentoy = type1 == TYPE_EXFAT_NTFS && start1 == DATA_START_SECTOR &&
            type2 == TYPE_EFI && count2 == EFI_PART_SECTORS && start2 == start1 + count1 &&
            start2 + count2 <= totalSectors
        if (!looksVentoy) return Detection.Other
        return Detection.Ventoy(
            DiskLayout(
                totalSectors,
                Partition(start1, count1, type1, (mbr[446].toInt() and 0x80) != 0),
                Partition(start2, count2, type2, (mbr[462].toInt() and 0x80) != 0),
            )
        )
    }

    private fun writeEntry(mbr: ByteArray, offset: Int, p: Partition) {
        mbr[offset] = if (p.bootable) 0x80.toByte() else 0x00
        // CHS fields are set to the "use LBA" marker
        mbr[offset + 1] = 0xFE.toByte(); mbr[offset + 2] = 0xFF.toByte(); mbr[offset + 3] = 0xFF.toByte()
        mbr[offset + 4] = p.type.toByte()
        mbr[offset + 5] = 0xFE.toByte(); mbr[offset + 6] = 0xFF.toByte(); mbr[offset + 7] = 0xFF.toByte()
        Bytes.putLe32(mbr, offset + 8, p.startSector)
        Bytes.putLe32(mbr, offset + 12, p.sectorCount)
    }
}
