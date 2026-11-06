package com.ignyx.core

import kotlin.random.Random

/**
 * Minimal read-only FAT12/16/32 reader. Used to read files from Ventoy's VTOYEFI
 * partition (for example grub/grub.cfg, which holds the Ventoy version).
 * Matches path parts against 8.3 short names, case-insensitively.
 */
class FatReader(private val dev: BlockDevice, private val partStart: Long) {
    private val bpb = dev.readSectors(partStart, 1)
    private val bytesPerSector = Bytes.le16(bpb, 11)
    private val secPerClus = bpb[13].toInt() and 0xFF
    private val reserved = Bytes.le16(bpb, 14)
    private val numFats = bpb[16].toInt() and 0xFF
    private val rootEntries = Bytes.le16(bpb, 17)
    private val totalSectors = Bytes.le16(bpb, 19).toLong().takeIf { it != 0L } ?: Bytes.le32(bpb, 32)
    private val fatSize = Bytes.le16(bpb, 22).toLong().takeIf { it != 0L } ?: Bytes.le32(bpb, 36)
    private val rootDirSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
    private val firstData = reserved + numFats * fatSize + rootDirSectors
    private val clusterCount = (totalSectors - firstData) / secPerClus
    val type: Int = when {
        clusterCount < 4085 -> 12
        clusterCount < 65525 -> 16
        else -> 32
    }
    private val rootCluster = if (type == 32) Bytes.le32(bpb, 44) else 0L

    init {
        require(bytesPerSector == 512) { "FAT sector size $bytesPerSector not supported" }
        require((bpb[510].toInt() and 0xFF) == 0x55 && (bpb[511].toInt() and 0xFF) == 0xAA) { "not a FAT volume" }
    }

    private fun clusterLba(c: Long) = partStart + firstData + (c - 2) * secPerClus

    private fun next(c: Long): Long {
        val fatStart = partStart + reserved
        return when (type) {
            12 -> {
                val off = c + c / 2
                val b = dev.readSectors(fatStart + off / 512, 2)
                val v = Bytes.le16(b, (off % 512).toInt())
                (if (c % 2 == 0L) v and 0xFFF else v shr 4).toLong()
            }
            16 -> {
                val off = c * 2
                Bytes.le16(dev.readSectors(fatStart + off / 512, 1), (off % 512).toInt()).toLong()
            }
            else -> {
                val off = c * 4
                Bytes.le32(dev.readSectors(fatStart + off / 512, 1), (off % 512).toInt()) and 0x0FFF_FFFF
            }
        }
    }

    private fun isEnd(c: Long) = when (type) {
        12 -> c >= 0xFF8; 16 -> c >= 0xFFF8; else -> c >= 0x0FFF_FFF8
    }

    private fun chain(first: Long): List<Long> {
        val out = ArrayList<Long>()
        var c = first
        while (c >= 2 && !isEnd(c) && out.size <= clusterCount) { out += c; c = next(c) }
        return out
    }

    private fun readChain(first: Long): ByteArray {
        val clusters = chain(first)
        val out = ByteArray(clusters.size * secPerClus * 512)
        clusters.forEachIndexed { i, c -> dev.read(clusterLba(c), out, i * secPerClus * 512, secPerClus) }
        return out
    }

    private fun rootDir(): ByteArray =
        if (type == 32) readChain(rootCluster)
        else dev.readSectors(partStart + reserved + numFats * fatSize, rootDirSectors)

    private fun shortName(part: String): String? {
        val up = part.uppercase()
        val dot = up.lastIndexOf('.')
        val base = if (dot >= 0) up.substring(0, dot) else up
        val ext = if (dot >= 0) up.substring(dot + 1) else ""
        if (base.isEmpty() || base.length > 8 || ext.length > 3) return null
        return base.padEnd(8) + ext.padEnd(3)
    }

    private data class Found(val cluster: Long, val size: Long, val isDir: Boolean)

    private fun find(dir: ByteArray, part: String): Found? {
        val want = shortName(part) ?: return null
        var i = 0
        while (i + 32 <= dir.size) {
            val first = dir[i].toInt() and 0xFF
            if (first == 0) break
            val attr = dir[i + 11].toInt() and 0xFF
            if (first != 0xE5 && attr != 0x0F && (attr and 0x08) == 0) {
                val name = String(dir, i, 11, Charsets.ISO_8859_1)
                if (name == want) {
                    val hi = if (type == 32) Bytes.le16(dir, i + 20).toLong() shl 16 else 0L
                    return Found(hi or Bytes.le16(dir, i + 26).toLong(), Bytes.le32(dir, i + 28), (attr and 0x10) != 0)
                }
            }
            i += 32
        }
        return null
    }

    /** Reads a file by path such as "grub/grub.cfg", or null if it does not exist. */
    fun readFile(path: String): ByteArray? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        var dir = rootDir()
        for ((i, p) in parts.withIndex()) {
            val f = find(dir, p) ?: return null
            if (i == parts.lastIndex) {
                if (f.isDir) return null
                return readChain(f.cluster).copyOf(f.size.toInt())
            }
            if (!f.isDir) return null
            dir = readChain(f.cluster)
        }
        return null
    }
}

/** Creates a fresh FAT32 file system inside a partition. */
object Fat32Formatter {

    fun sectorsPerCluster(partSectors: Long): Int {
        val bytes = partSectors * 512
        return when {
            bytes <= 8L shl 30 -> 8      // 4 KiB
            bytes <= 16L shl 30 -> 16    // 8 KiB
            bytes <= 32L shl 30 -> 32    // 16 KiB
            else -> 64                   // 32 KiB
        }
    }

    fun format(
        dev: BlockDevice,
        partStart: Long,
        partSectors: Long,
        label: String = "IGNYX",
        serial: Int = Random.nextInt(),
        onProgress: (Float) -> Unit = {},
    ) {
        val spc = sectorsPerCluster(partSectors)
        val reserved = 32L
        // Solve for the FAT size: each FAT must map every data cluster.
        var fatSize = 1L
        while (true) {
            val clusters = (partSectors - reserved - 2 * fatSize) / spc
            val need = Bytes.ceilDiv((clusters + 2) * 4, 512)
            if (need <= fatSize) break
            fatSize = need
        }
        val clusters = (partSectors - reserved - 2 * fatSize) / spc
        require(clusters >= 65525) { "Partition too small for FAT32" }
        val dataStart = reserved + 2 * fatSize

        // Wipe reserved area, both FATs and the root cluster.
        val wipe = dataStart + spc
        dev.zero(partStart, wipe) { onProgress(it.toFloat() / wipe) }

        val bs = ByteArray(512)
        bs[0] = 0xEB.toByte(); bs[1] = 0x58; bs[2] = 0x90.toByte()
        "MSWIN4.1".forEachIndexed { i, c -> bs[3 + i] = c.code.toByte() }
        Bytes.putLe16(bs, 11, 512)
        bs[13] = spc.toByte()
        Bytes.putLe16(bs, 14, reserved.toInt())
        bs[16] = 2
        bs[21] = 0xF8.toByte()
        Bytes.putLe16(bs, 24, 63)
        Bytes.putLe16(bs, 26, 255)
        Bytes.putLe32(bs, 28, partStart)
        Bytes.putLe32(bs, 32, partSectors)
        Bytes.putLe32(bs, 36, fatSize)
        Bytes.putLe32(bs, 44, 2)          // root cluster
        Bytes.putLe16(bs, 48, 1)          // FSInfo sector
        Bytes.putLe16(bs, 50, 6)          // backup boot sector
        bs[64] = 0x80.toByte()
        bs[66] = 0x29
        Bytes.putLe32(bs, 67, serial.toLong() and 0xFFFF_FFFFL)
        val lab = label.uppercase().take(11).padEnd(11)
        lab.forEachIndexed { i, c -> bs[71 + i] = c.code.toByte() }
        "FAT32   ".forEachIndexed { i, c -> bs[82 + i] = c.code.toByte() }
        bs[510] = 0x55; bs[511] = 0xAA.toByte()

        val fsinfo = ByteArray(512)
        Bytes.putLe32(fsinfo, 0, 0x41615252)
        Bytes.putLe32(fsinfo, 484, 0x61417272)
        Bytes.putLe32(fsinfo, 488, clusters - 1)
        Bytes.putLe32(fsinfo, 492, 3)
        fsinfo[510] = 0x55; fsinfo[511] = 0xAA.toByte()

        val fat = ByteArray(512)
        Bytes.putLe32(fat, 0, 0x0FFF_FFF8)
        Bytes.putLe32(fat, 4, 0x0FFF_FFFF)
        Bytes.putLe32(fat, 8, 0x0FFF_FFFF) // root directory, one cluster

        val root = ByteArray(spc * 512)
        lab.forEachIndexed { i, c -> root[i] = c.code.toByte() }
        root[11] = 0x08 // volume label

        dev.writeSectors(partStart + 1, fsinfo)
        dev.writeSectors(partStart + 7, fsinfo)
        dev.writeSectors(partStart + reserved, fat)
        dev.writeSectors(partStart + reserved + fatSize, fat)
        dev.writeSectors(partStart + dataStart, root)
        dev.writeSectors(partStart + 6, bs)
        dev.writeSectors(partStart, bs)
        dev.flush()
        onProgress(1f)
    }
}
