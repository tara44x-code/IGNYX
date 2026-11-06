package com.ignyx.core

import java.io.IOException
import java.io.InputStream

data class ExFatEntry(val name: String, val sizeBytes: Long, val isDirectory: Boolean)

class ExFatException(message: String) : IOException(message)

/**
 * A small exFAT driver for the Ventoy data partition: list, add and delete files,
 * and create folders. New files and folders are written as one contiguous run of
 * clusters (the "NoFatChain" form), which is also the fastest layout for booting ISOs.
 * Paths use "/" and are relative to the root, e.g. "ventoy/theme/ignyx/theme.txt".
 */
class ExFatVolume private constructor(
    private val dev: BlockDevice,
    private val partStart: Long,
    private val fatOffset: Long,
    private val heapOffset: Long,
    val clusterCount: Long,
    private val spcShift: Int,
    private val rootCluster: Long,
) {
    private val spc = 1 shl spcShift
    val clusterBytes: Int = spc * BlockDevice.SECTOR

    private lateinit var bitmapClusters: List<Long>
    private lateinit var bitmap: ByteArray
    private lateinit var upcase: CharArray
    var label: String = ""
        private set

    val capacityBytes: Long get() = clusterCount * clusterBytes
    val freeBytes: Long get() = freeClusters() * clusterBytes
    val usedBytes: Long get() = capacityBytes - freeBytes

    companion object {
        fun open(dev: BlockDevice, partStart: Long): ExFatVolume {
            val boot = dev.readSectors(partStart, 1)
            val oem = String(boot, 3, 8, Charsets.US_ASCII)
            if (oem != "EXFAT   ") throw ExFatException("القسم مو exFAT")
            if (boot[108].toInt() != 9) throw ExFatException("حجم القطاع غير مدعوم")
            val v = ExFatVolume(
                dev = dev,
                partStart = partStart,
                fatOffset = Bytes.le32(boot, 80),
                heapOffset = Bytes.le32(boot, 88),
                clusterCount = Bytes.le32(boot, 92),
                spcShift = boot[109].toInt(),
                rootCluster = Bytes.le32(boot, 96),
            )
            v.load()
            return v
        }
    }

    // ---------------------------------------------------------------- low level

    private fun clusterLba(c: Long) = partStart + heapOffset + (c - 2) * spc

    private var fatCacheSector = -1L
    private val fatCache = ByteArray(BlockDevice.SECTOR)

    private fun fatEntry(c: Long): Long {
        val byteOff = c * 4
        val sector = partStart + fatOffset + byteOff / BlockDevice.SECTOR
        if (sector != fatCacheSector) {
            dev.read(sector, fatCache, 0, 1)
            fatCacheSector = sector
        }
        return Bytes.le32(fatCache, (byteOff % BlockDevice.SECTOR).toInt())
    }

    private fun fatChain(first: Long): List<Long> {
        val out = ArrayList<Long>()
        var c = first
        while (c in 2..(clusterCount + 1)) {
            out += c
            if (out.size > clusterCount) throw ExFatException("سلسلة FAT تالفة")
            c = fatEntry(c)
        }
        return out
    }

    private fun contiguous(first: Long, bytes: Long): List<Long> {
        val n = Bytes.ceilDiv(bytes, clusterBytes.toLong())
        return (0 until n).map { first + it }
    }

    private fun chainOf(first: Long, noFatChain: Boolean, length: Long): List<Long> =
        if (first < 2) emptyList() else if (noFatChain) contiguous(first, length) else fatChain(first)

    private fun readClusters(clusters: List<Long>, limit: Long = -1): ByteArray {
        val all = clusters.size.toLong() * clusterBytes
        val size = (if (limit < 0) all else minOf(all, Bytes.ceilDiv(limit, 512) * 512)).toInt()
        val out = ByteArray(size)
        var off = 0
        for (c in clusters) {
            if (off >= size) break
            val n = minOf(clusterBytes, size - off)
            dev.read(clusterLba(c), out, off, n / BlockDevice.SECTOR)
            off += n
        }
        return out
    }

    private fun load() {
        val root = loadRoot()
        var bitmapFirst = 0L
        var bitmapLen = 0L
        var upFirst = 0L
        var upLen = 0L
        var i = 0
        val b = root.buf
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                ExFat.TYPE_END -> break
                ExFat.TYPE_BITMAP -> if ((b[i + 1].toInt() and 1) == 0) {
                    bitmapFirst = Bytes.le32(b, i + 20); bitmapLen = Bytes.le64(b, i + 24)
                }
                ExFat.TYPE_UPCASE -> { upFirst = Bytes.le32(b, i + 20); upLen = Bytes.le64(b, i + 24) }
                ExFat.TYPE_LABEL -> {
                    val n = (b[i + 1].toInt() and 0xFF).coerceAtMost(11)
                    label = String(CharArray(n) { k -> Bytes.le16(b, i + 2 + k * 2).toChar() })
                }
            }
            i += ExFat.ENTRY_SIZE
        }
        if (bitmapFirst == 0L) throw ExFatException("ما لكيت جدول المساحة (bitmap)")
        bitmapClusters = fatChain(bitmapFirst)
        bitmap = readClusters(bitmapClusters).copyOf(Bytes.ceilDiv(bitmapLen, 512).toInt() * 512)
        upcase = if (upFirst != 0L) ExFat.expandUpcase(readClusters(fatChain(upFirst), upLen).copyOf(upLen.toInt()))
        else ExFat.expandUpcase(ExFat.defaultUpcaseTable())
    }

    private fun upper(s: String) = String(CharArray(s.length) { upcase[s[it].code] })

    // ---------------------------------------------------------------- directories

    /** A directory's contents, read into memory, plus where each part lives on disk. */
    private inner class Dir(val clusters: List<Long>) {
        val buf: ByteArray = readClusters(clusters)

        fun writeRange(from: Int, to: Int) {
            val first = from / BlockDevice.SECTOR
            val last = (to - 1) / BlockDevice.SECTOR
            for (s in first..last) {
                val byteOff = s * BlockDevice.SECTOR
                val cluster = clusters[byteOff / clusterBytes]
                val inCluster = (byteOff % clusterBytes) / BlockDevice.SECTOR
                dev.write(clusterLba(cluster) + inCluster, buf, byteOff, 1)
            }
        }

        fun sets(): List<EntrySet> {
            val out = ArrayList<EntrySet>()
            var i = 0
            while (i < buf.size) {
                val type = buf[i].toInt() and 0xFF
                if (type == ExFat.TYPE_END) break
                if (type == ExFat.TYPE_FILE) {
                    val secondary = buf[i + 1].toInt() and 0xFF
                    val attrs = Bytes.le16(buf, i + 4)
                    val s = i + ExFat.ENTRY_SIZE
                    if (secondary >= 2 && s + secondary * ExFat.ENTRY_SIZE <= buf.size &&
                        (buf[s].toInt() and 0xFF) == ExFat.TYPE_STREAM
                    ) {
                        val flags = buf[s + 1].toInt() and 0xFF
                        val nameLen = buf[s + 3].toInt() and 0xFF
                        val first = Bytes.le32(buf, s + 20)
                        val length = Bytes.le64(buf, s + 24)
                        val sb = StringBuilder()
                        for (k in 1 until secondary) {
                            val e = s + k * ExFat.ENTRY_SIZE
                            if ((buf[e].toInt() and 0xFF) != ExFat.TYPE_NAME) continue
                            for (ch in 0 until ExFat.NAME_CHARS_PER_ENTRY) {
                                if (sb.length >= nameLen) break
                                sb.append(Bytes.le16(buf, e + 2 + ch * 2).toChar())
                            }
                        }
                        out += EntrySet(i, secondary + 1, sb.toString(), attrs, flags, first, length)
                    }
                    i += (secondary + 1) * ExFat.ENTRY_SIZE
                    continue
                }
                i += ExFat.ENTRY_SIZE
            }
            return out
        }

        fun find(name: String): EntrySet? {
            val key = upper(name)
            return sets().firstOrNull { upper(it.name) == key }
        }

        fun freeSlots(count: Int): Int? {
            var run = 0
            var start = 0
            var i = 0
            while (i < buf.size) {
                if ((buf[i].toInt() and ExFat.IN_USE) == 0) {
                    if (run == 0) start = i
                    run++
                    if (run == count) return start
                } else run = 0
                i += ExFat.ENTRY_SIZE
            }
            return null
        }
    }

    private class EntrySet(
        val index: Int, val count: Int, val name: String, val attrs: Int,
        val flags: Int, val firstCluster: Long, val length: Long,
    ) {
        val isDirectory get() = (attrs and ExFat.ATTR_DIRECTORY) != 0
        val noFatChain get() = (flags and ExFat.FLAG_NO_FAT_CHAIN) != 0
    }

    private fun loadRoot() = Dir(fatChain(rootCluster))

    private fun parts(path: String): List<String> = path.split('/').filter { it.isNotEmpty() && it != "." }

    /** Opens the folder at [path], or returns null if any part is missing. */
    private fun openDir(path: String): Dir? {
        var dir = loadRoot()
        for (p in parts(path)) {
            val set = dir.find(p) ?: return null
            if (!set.isDirectory) return null
            dir = Dir(chainOf(set.firstCluster, set.noFatChain, set.length))
        }
        return dir
    }

    private fun splitParent(path: String): Pair<String, String> {
        val p = parts(path)
        if (p.isEmpty()) throw ExFatException("المسار فارغ")
        return p.dropLast(1).joinToString("/") to p.last()
    }

    fun list(path: String = ""): List<ExFatEntry> {
        val dir = openDir(path) ?: throw ExFatException("المجلد غير موجود: $path")
        return dir.sets().map { ExFatEntry(it.name, it.length, it.isDirectory) }
    }

    fun exists(path: String): Boolean {
        if (parts(path).isEmpty()) return true
        val (parent, name) = splitParent(path)
        return openDir(parent)?.find(name) != null
    }

    fun isDirectory(path: String): Boolean {
        if (parts(path).isEmpty()) return true
        val (parent, name) = splitParent(path)
        return openDir(parent)?.find(name)?.isDirectory == true
    }

    /** Total size of all files under [path], recursively. */
    fun sizeOf(path: String): Long {
        if (!isDirectory(path)) {
            val (parent, name) = splitParent(path)
            return openDir(parent)?.find(name)?.length ?: 0
        }
        return list(path).sumOf { if (it.isDirectory) sizeOf(join(path, it.name)) else it.sizeBytes }
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

    /** Creates the folder at [path] and any missing parents. */
    fun mkdirs(path: String) {
        var cur = ""
        for (p in parts(path)) {
            val dir = openDir(cur) ?: throw ExFatException("المجلد غير موجود: $cur")
            val existing = dir.find(p)
            if (existing == null) {
                checkName(p)
                val first = findRun(1)
                if (first < 0) throw ExFatException("ماكو مساحة كافية على الفلاشة")
                dev.write(clusterLba(first), ByteArray(clusterBytes), 0, spc)
                setUsed(first, 1, true)
                writeBitmapBits(first, 1)
                writeEntrySet(dir, p, ExFat.ATTR_DIRECTORY, first, clusterBytes.toLong())
            } else if (!existing.isDirectory) {
                throw ExFatException("يوجد ملف بنفس اسم المجلد: $p")
            }
            cur = join(cur, p)
        }
        dev.flush()
    }

    // ---------------------------------------------------------------- bitmap

    private fun isUsed(c: Long): Boolean {
        val bit = c - 2
        return ((bitmap[(bit / 8).toInt()].toInt() shr (bit % 8).toInt()) and 1) == 1
    }

    private fun setUsed(first: Long, count: Long, used: Boolean) {
        for (k in 0 until count) {
            val bit = first - 2 + k
            val idx = (bit / 8).toInt()
            val mask = 1 shl (bit % 8).toInt()
            bitmap[idx] = (if (used) bitmap[idx].toInt() or mask else bitmap[idx].toInt() and mask.inv()).toByte()
        }
    }

    private fun writeBitmapBits(firstCluster: Long, count: Long) {
        if (count <= 0) return
        val fromByte = ((firstCluster - 2) / 8).toInt()
        val toByte = ((firstCluster - 2 + count - 1) / 8).toInt() + 1
        val firstSector = fromByte / BlockDevice.SECTOR
        val lastSector = (toByte - 1) / BlockDevice.SECTOR
        for (s in firstSector..lastSector) {
            val byteOff = s * BlockDevice.SECTOR
            val cluster = bitmapClusters[byteOff / clusterBytes]
            val inCluster = (byteOff % clusterBytes) / BlockDevice.SECTOR
            dev.write(clusterLba(cluster) + inCluster, bitmap, byteOff, 1)
        }
    }

    private fun freeClusters(): Long {
        var used = 0L
        val fullBytes = (clusterCount / 8).toInt()
        for (i in 0 until fullBytes) used += Integer.bitCount(bitmap[i].toInt() and 0xFF)
        for (c in (fullBytes * 8L) until clusterCount) if (isUsed(c + 2)) used++
        return clusterCount - used
    }

    /** First cluster of a free contiguous run of [count] clusters, or -1. */
    private fun findRun(count: Long): Long {
        var runStart = -1L
        var runLen = 0L
        var c = 2L
        val end = clusterCount + 2
        while (c < end) {
            val bit = c - 2
            if (bit % 8 == 0L && bit + 8 <= clusterCount && (bitmap[(bit / 8).toInt()].toInt() and 0xFF) == 0xFF) {
                runLen = 0; runStart = -1; c += 8; continue
            }
            if (isUsed(c)) { runLen = 0; runStart = -1 } else {
                if (runLen == 0L) runStart = c
                runLen++
                if (runLen == count) return runStart
            }
            c++
        }
        return -1
    }

    /** The largest file that fits in one contiguous free run. */
    fun largestFreeRunBytes(): Long {
        var best = 0L
        var run = 0L
        for (c in 2L until clusterCount + 2) {
            if (isUsed(c)) run = 0 else { run++; if (run > best) best = run }
        }
        return best * clusterBytes
    }

    // ---------------------------------------------------------------- writing

    private fun checkName(name: String) {
        if (name.isEmpty() || name.length > ExFat.MAX_NAME || name == "." || name == "..") {
            throw ExFatException("اسم الملف غير صالح")
        }
        if (name.any { it.code < 0x20 || it in "\"*/:<>?\\|" }) throw ExFatException("اسم الملف بيه رموز غير مسموحة")
    }

    private fun writeEntrySet(dir: Dir, name: String, attrs: Int, first: Long, size: Long) {
        val nameEntries = Bytes.ceilDiv(name.length.toLong(), ExFat.NAME_CHARS_PER_ENTRY.toLong()).toInt()
        val total = 2 + nameEntries
        val slot = dir.freeSlots(total) ?: throw ExFatException("المجلد ممتلئ")
        val set = ByteArray(total * ExFat.ENTRY_SIZE)
        val now = ExFat.timestampNow()
        set[0] = ExFat.TYPE_FILE.toByte()
        set[1] = (total - 1).toByte()
        Bytes.putLe16(set, 4, attrs)
        Bytes.putLe32(set, 8, now); Bytes.putLe32(set, 12, now); Bytes.putLe32(set, 16, now)
        val s = ExFat.ENTRY_SIZE
        set[s] = ExFat.TYPE_STREAM.toByte()
        set[s + 1] = (ExFat.FLAG_ALLOCATION_POSSIBLE or (if (first >= 2) ExFat.FLAG_NO_FAT_CHAIN else 0)).toByte()
        set[s + 3] = name.length.toByte()
        Bytes.putLe16(set, s + 4, ExFat.nameHash(upper(name)))
        Bytes.putLe64(set, s + 8, size)
        Bytes.putLe32(set, s + 20, first)
        Bytes.putLe64(set, s + 24, size)
        for (k in 0 until nameEntries) {
            val e = (2 + k) * ExFat.ENTRY_SIZE
            set[e] = ExFat.TYPE_NAME.toByte()
            for (ch in 0 until ExFat.NAME_CHARS_PER_ENTRY) {
                val idx = k * ExFat.NAME_CHARS_PER_ENTRY + ch
                if (idx < name.length) Bytes.putLe16(set, e + 2 + ch * 2, name[idx].code)
            }
        }
        Bytes.putLe16(set, 2, ExFat.entrySetChecksum(set))
        set.copyInto(dir.buf, slot)
        dir.writeRange(slot, slot + set.size)
    }

    /**
     * Writes a file. Missing parent folders are created; a file with the same name is replaced.
     * [input] must supply exactly [size] bytes.
     */
    fun createFile(path: String, size: Long, input: InputStream, onProgress: (Float) -> Unit = {}) {
        val (parentPath, name) = splitParent(path)
        checkName(name)
        if (size < 0) throw ExFatException("حجم الملف غير معروف")
        if (parentPath.isNotEmpty()) mkdirs(parentPath)
        openDir(parentPath)!!.find(name)?.let {
            if (it.isDirectory) throw ExFatException("يوجد مجلد بنفس الاسم: $name")
            delete(path)
        }

        val clusters = Bytes.ceilDiv(size, clusterBytes.toLong())
        if (clusters > freeClusters()) throw ExFatException("ماكو مساحة كافية على الفلاشة")
        val first = if (clusters == 0L) 0L else findRun(clusters)
        if (first < 0) throw ExFatException("المساحة الفارغة مقطّعة. احذف ملفات حتى تتوفر مساحة متصلة.")

        // Check there is room in the folder before writing gigabytes of data.
        val dir = openDir(parentPath)!!
        val nameEntries = Bytes.ceilDiv(name.length.toLong(), ExFat.NAME_CHARS_PER_ENTRY.toLong()).toInt()
        if (dir.freeSlots(2 + nameEntries) == null) throw ExFatException("المجلد ممتلئ")

        // 1. Data.
        if (clusters > 0) {
            val chunk = ByteArray(1 shl 20)
            var done = 0L
            var lba = clusterLba(first)
            while (done < size) {
                val want = minOf(chunk.size.toLong(), size - done).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(chunk, got, want - got)
                    if (n < 0) throw ExFatException("الملف انقطع قبل ما يكمل")
                    got += n
                }
                val sectors = Bytes.ceilDiv(got.toLong(), 512).toInt()
                if (got % 512 != 0) chunk.fill(0, got, sectors * 512)
                dev.write(lba, chunk, 0, sectors)
                lba += sectors
                done += got
                onProgress(done.toFloat() / size)
            }
        }

        // 2. Allocation bitmap, 3. directory entry set.
        setUsed(first, clusters, true)
        writeBitmapBits(first, clusters)
        writeEntrySet(dir, name, ExFat.ATTR_ARCHIVE, first, size)
        dev.flush()
        onProgress(1f)
    }

    /** Deletes a file, or a folder and everything in it. Returns false if not found. */
    fun delete(path: String): Boolean {
        val (parentPath, name) = splitParent(path)
        val dir = openDir(parentPath) ?: return false
        val set = dir.find(name) ?: return false
        if (set.isDirectory) {
            for (child in list(path)) delete(join(path, child.name))
        }
        val clusters = chainOf(set.firstCluster, set.noFatChain, set.length)
        if (set.noFatChain && clusters.isNotEmpty()) {
            setUsed(clusters.first(), clusters.size.toLong(), false)
            writeBitmapBits(clusters.first(), clusters.size.toLong())
        } else {
            for (c in clusters) { setUsed(c, 1, false); writeBitmapBits(c, 1) }
        }
        // Re-read the parent: deleting children may not touch it, but be safe.
        val fresh = openDir(parentPath)!!
        val target = fresh.find(name)!!
        for (k in 0 until target.count) {
            val e = target.index + k * ExFat.ENTRY_SIZE
            fresh.buf[e] = (fresh.buf[e].toInt() and ExFat.IN_USE.inv()).toByte()
        }
        fresh.writeRange(target.index, target.index + target.count * ExFat.ENTRY_SIZE)
        dev.flush()
        return true
    }

    /** Reads a whole small file. */
    fun readFile(path: String): ByteArray {
        val (parentPath, name) = splitParent(path)
        val set = openDir(parentPath)?.find(name) ?: throw ExFatException("الملف غير موجود: $path")
        if (set.isDirectory) throw ExFatException("هذا مجلد: $path")
        if (set.length == 0L) return ByteArray(0)
        if (set.length > Int.MAX_VALUE) throw ExFatException("الملف كبير")
        return readClusters(chainOf(set.firstCluster, set.noFatChain, set.length), set.length).copyOf(set.length.toInt())
    }

    /** Streams a file in chunks, e.g. to verify it after copying. */
    fun readFileChunks(path: String, onChunk: (ByteArray, Int) -> Unit) {
        val (parentPath, name) = splitParent(path)
        val set = openDir(parentPath)?.find(name) ?: throw ExFatException("الملف غير موجود: $path")
        val clusters = chainOf(set.firstCluster, set.noFatChain, set.length)
        var left = set.length
        val buf = ByteArray(1 shl 20)
        var i = 0
        while (left > 0 && i < clusters.size) {
            // Group consecutive clusters into one read of up to 1 MiB.
            val start = clusters[i]
            var n = 1
            while (i + n < clusters.size && clusters[i + n] == start + n && (n + 1) * clusterBytes <= buf.size) n++
            val bytes = minOf(n.toLong() * clusterBytes, left).toInt()
            dev.read(clusterLba(start), buf, 0, Bytes.ceilDiv(bytes.toLong(), 512).toInt())
            onChunk(buf, bytes)
            left -= bytes
            i += n
        }
    }
}
