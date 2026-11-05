package com.ignyx.core

import kotlin.random.Random

/** exFAT on-disk constants and checksums (Microsoft exFAT specification). */
internal object ExFat {
    const val ENTRY_SIZE = 32
    const val TYPE_END = 0x00
    const val TYPE_BITMAP = 0x81
    const val TYPE_UPCASE = 0x82
    const val TYPE_LABEL = 0x83
    const val TYPE_FILE = 0x85
    const val TYPE_STREAM = 0xC0
    const val TYPE_NAME = 0xC1
    const val IN_USE = 0x80

    const val ATTR_DIRECTORY = 0x10
    const val ATTR_ARCHIVE = 0x20

    const val FLAG_ALLOCATION_POSSIBLE = 0x01
    const val FLAG_NO_FAT_CHAIN = 0x02

    const val FAT_END = 0xFFFF_FFFFL
    const val NAME_CHARS_PER_ENTRY = 15
    const val MAX_NAME = 255

    fun bootChecksum(region: ByteArray): Long {
        var sum = 0L
        for (i in 0 until 11 * BlockDevice.SECTOR) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (((sum shl 31) or (sum ushr 1)) + (region[i].toLong() and 0xFF)) and 0xFFFF_FFFFL
        }
        return sum
    }

    fun tableChecksum(table: ByteArray): Long {
        var sum = 0L
        for (b in table) sum = (((sum shl 31) or (sum ushr 1)) + (b.toLong() and 0xFF)) and 0xFFFF_FFFFL
        return sum
    }

    fun entrySetChecksum(set: ByteArray): Int {
        var sum = 0
        for (i in set.indices) {
            if (i == 2 || i == 3) continue
            sum = (((sum shl 15) or (sum ushr 1)) + (set[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    fun nameHash(upcased: String): Int {
        var hash = 0
        for (ch in upcased) {
            val c = ch.code
            hash = (((hash shl 15) or (hash ushr 1)) + (c and 0xFF)) and 0xFFFF
            hash = (((hash shl 15) or (hash ushr 1)) + ((c shr 8) and 0xFF)) and 0xFFFF
        }
        return hash
    }

    /**
     * A compressed up-case table: identity everywhere except ASCII a-z -> A-Z,
     * plus Latin-1 and Arabic need no case mapping. Valid per spec section 7.2.5.
     */
    fun defaultUpcaseTable(): ByteArray {
        val words = ArrayList<Int>()
        words += 0xFFFF; words += 0x61                    // 0x0000..0x0060 identity
        for (c in 0x61..0x7A) words += c - 0x20           // a..z -> A..Z
        words += 0xFFFF; words += 0x10000 - 0x7B          // the rest identity
        val out = ByteArray(words.size * 2)
        words.forEachIndexed { i, w -> Bytes.putLe16(out, i * 2, w) }
        return out
    }

    /** Expands a (possibly compressed) up-case table into a 64K lookup. */
    fun expandUpcase(table: ByteArray): CharArray {
        val map = CharArray(0x10000) { it.toChar() }
        var idx = 0
        var i = 0
        val words = table.size / 2
        while (i < words && idx < 0x10000) {
            val w = Bytes.le16(table, i * 2)
            if (w == 0xFFFF && i + 1 < words) {
                idx += Bytes.le16(table, (i + 1) * 2)
                i += 2
            } else {
                map[idx] = w.toChar()
                idx++
                i++
            }
        }
        return map
    }

    /** DOS-style timestamp used by exFAT, from the current local time. */
    fun timestampNow(): Long {
        val c = java.util.Calendar.getInstance()
        val year = (c.get(java.util.Calendar.YEAR) - 1980).coerceIn(0, 127)
        val month = c.get(java.util.Calendar.MONTH) + 1
        val day = c.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = c.get(java.util.Calendar.MINUTE)
        val sec2 = c.get(java.util.Calendar.SECOND) / 2
        return (year.toLong() shl 25) or (month.toLong() shl 21) or (day.toLong() shl 16) or
            (hour.toLong() shl 11) or (minute.toLong() shl 5) or sec2.toLong()
    }
}

/** Creates a fresh exFAT file system inside a partition. */
object ExFatFormatter {

    data class Geometry(
        val sectorsPerClusterShift: Int,
        val fatOffset: Long,
        val fatLength: Long,
        val clusterHeapOffset: Long,
        val clusterCount: Long,
    ) {
        val sectorsPerCluster: Int get() = 1 shl sectorsPerClusterShift
        val clusterBytes: Int get() = sectorsPerCluster * BlockDevice.SECTOR
    }

    fun geometry(partSectors: Long): Geometry {
        val bytes = partSectors * BlockDevice.SECTOR
        val shift = when {
            bytes <= 256L * 1024 * 1024 -> 3          // 4 KiB clusters
            bytes <= 32L * 1024 * 1024 * 1024 -> 6    // 32 KiB clusters
            else -> 8                                 // 128 KiB clusters
        }
        val spc = 1L shl shift
        val fatOffset = 128L
        val estimate = (partSectors - fatOffset) / spc
        val fatLength = Bytes.ceilDiv((estimate + 2) * 4, BlockDevice.SECTOR.toLong())
        val heap = Bytes.ceilDiv(fatOffset + fatLength, spc) * spc
        val count = minOf((partSectors - heap) / spc, 0xFFFF_FFF5L)
        require(count > 16) { "Partition too small for exFAT" }
        return Geometry(shift, fatOffset, fatLength, heap, count)
    }

    /**
     * @param partStart first sector of the partition on the disk
     * @param partSectors partition length in sectors
     */
    fun format(
        dev: BlockDevice,
        partStart: Long,
        partSectors: Long,
        label: String = "Ventoy",
        serial: Int = Random.nextInt(),
        onProgress: (Float) -> Unit = {},
    ) {
        val g = geometry(partSectors)
        val clusterBytes = g.clusterBytes.toLong()

        val upcase = ExFat.defaultUpcaseTable()
        val bitmapBytes = Bytes.ceilDiv(g.clusterCount, 8)
        val bitmapClusters = Bytes.ceilDiv(bitmapBytes, clusterBytes)
        val upcaseClusters = Bytes.ceilDiv(upcase.size.toLong(), clusterBytes)
        val bitmapCluster = 2L
        val upcaseCluster = bitmapCluster + bitmapClusters
        val rootCluster = upcaseCluster + upcaseClusters
        val usedClusters = bitmapClusters + upcaseClusters + 1
        fun clusterLba(c: Long) = partStart + g.clusterHeapOffset + (c - 2) * g.sectorsPerCluster

        // Work out how many sectors we will write, for progress.
        val bitmapSectors = bitmapClusters * g.sectorsPerCluster
        val total = (g.clusterHeapOffset + bitmapSectors).toFloat()
        var written = 0L
        fun advance(n: Long) { written += n; onProgress((written / total).coerceAtMost(1f)) }

        // 1. Wipe the boot regions and the FAT.
        dev.zero(partStart, g.fatOffset + g.fatLength) { }
        advance(g.fatOffset + g.fatLength)

        // 2. FAT: media entries plus chains for bitmap, up-case table and root directory.
        val fatBytes = ((rootCluster + 1) * 4).toInt()
        val fat = ByteArray(Bytes.ceilDiv(fatBytes.toLong(), 512).toInt() * 512)
        Bytes.putLe32(fat, 0, 0xFFFF_FFF8L)
        Bytes.putLe32(fat, 4, ExFat.FAT_END)
        fun chain(first: Long, count: Long) {
            for (i in 0 until count) {
                val c = first + i
                Bytes.putLe32(fat, (c * 4).toInt(), if (i == count - 1) ExFat.FAT_END else c + 1)
            }
        }
        chain(bitmapCluster, bitmapClusters)
        chain(upcaseCluster, upcaseClusters)
        chain(rootCluster, 1)
        dev.writeSectors(partStart + g.fatOffset, fat)

        // 3. Allocation bitmap (whole region rewritten so no stale bits survive).
        val bitmapRegion = ByteArray((bitmapClusters * clusterBytes).toInt())
        for (i in 0 until usedClusters) {
            val bit = i.toInt()
            bitmapRegion[bit / 8] = (bitmapRegion[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        val chunk = 2048 * BlockDevice.SECTOR
        var off = 0
        while (off < bitmapRegion.size) {
            val n = minOf(chunk, bitmapRegion.size - off)
            dev.write(clusterLba(bitmapCluster) + off / BlockDevice.SECTOR, bitmapRegion, off, n / BlockDevice.SECTOR)
            off += n
            advance((n / BlockDevice.SECTOR).toLong())
        }

        // 4. Up-case table.
        val upcaseRegion = ByteArray((upcaseClusters * clusterBytes).toInt())
        upcase.copyInto(upcaseRegion)
        dev.writeSectors(clusterLba(upcaseCluster), upcaseRegion)

        // 5. Root directory: label, bitmap and up-case entries.
        val root = ByteArray(clusterBytes.toInt())
        val name = label.take(11)
        root[0] = ExFat.TYPE_LABEL.toByte()
        root[1] = name.length.toByte()
        name.forEachIndexed { i, ch -> Bytes.putLe16(root, 2 + i * 2, ch.code) }
        root[32] = ExFat.TYPE_BITMAP.toByte()
        Bytes.putLe32(root, 32 + 20, bitmapCluster)
        Bytes.putLe64(root, 32 + 24, bitmapBytes)
        root[64] = ExFat.TYPE_UPCASE.toByte()
        Bytes.putLe32(root, 64 + 4, ExFat.tableChecksum(upcase))
        Bytes.putLe32(root, 64 + 20, upcaseCluster)
        Bytes.putLe64(root, 64 + 24, upcase.size.toLong())
        dev.writeSectors(clusterLba(rootCluster), root)

        // 6. Boot regions last, so an interrupted format is never mistaken for a valid one.
        val region = bootRegion(partStart, partSectors, g, rootCluster, serial)
        dev.writeSectors(partStart, region)
        dev.writeSectors(partStart + 12, region)
        dev.flush()
        onProgress(1f)
    }

    private fun bootRegion(partStart: Long, partSectors: Long, g: Geometry, rootCluster: Long, serial: Int): ByteArray {
        val r = ByteArray(12 * BlockDevice.SECTOR)
        r[0] = 0xEB.toByte(); r[1] = 0x76; r[2] = 0x90.toByte()
        "EXFAT   ".forEachIndexed { i, ch -> r[3 + i] = ch.code.toByte() }
        Bytes.putLe64(r, 64, partStart)
        Bytes.putLe64(r, 72, partSectors)
        Bytes.putLe32(r, 80, g.fatOffset)
        Bytes.putLe32(r, 84, g.fatLength)
        Bytes.putLe32(r, 88, g.clusterHeapOffset)
        Bytes.putLe32(r, 92, g.clusterCount)
        Bytes.putLe32(r, 96, rootCluster)
        Bytes.putLe32(r, 100, serial.toLong() and 0xFFFF_FFFFL)
        Bytes.putLe16(r, 104, 0x0100)
        Bytes.putLe16(r, 106, 0)
        r[108] = 9
        r[109] = g.sectorsPerClusterShift.toByte()
        r[110] = 1
        r[111] = 0x80.toByte()
        r[112] = 0xFF.toByte()
        for (i in 120 until 510) r[i] = 0xF4.toByte()
        r[510] = 0x55; r[511] = 0xAA.toByte()
        // Extended boot sectors 1..8 carry a signature in their last 4 bytes.
        for (s in 1..8) {
            val end = (s + 1) * BlockDevice.SECTOR
            r[end - 2] = 0x55; r[end - 1] = 0xAA.toByte()
        }
        val sum = ExFat.bootChecksum(r)
        for (i in 0 until BlockDevice.SECTOR / 4) Bytes.putLe32(r, 11 * BlockDevice.SECTOR + i * 4, sum)
        return r
    }
}
