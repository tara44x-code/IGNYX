package com.ignyx.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** A disk addressed in 512-byte sectors. */
interface BlockDevice : Closeable {
    /** Total number of 512-byte sectors. */
    val sectorCount: Long

    /** Reads [sectors] sectors starting at [lba] into [buf] at [off]. */
    fun read(lba: Long, buf: ByteArray, off: Int, sectors: Int)

    /** Writes [sectors] sectors starting at [lba] from [buf] at [off]. */
    fun write(lba: Long, buf: ByteArray, off: Int, sectors: Int)

    fun flush() {}

    override fun close() {}

    companion object {
        const val SECTOR = 512
    }
}

fun BlockDevice.readSectors(lba: Long, sectors: Int): ByteArray =
    ByteArray(sectors * BlockDevice.SECTOR).also { read(lba, it, 0, sectors) }

fun BlockDevice.writeSectors(lba: Long, buf: ByteArray) {
    require(buf.size % BlockDevice.SECTOR == 0) { "buffer must be a whole number of sectors" }
    write(lba, buf, 0, buf.size / BlockDevice.SECTOR)
}

/** Writes zeros over [count] sectors starting at [lba], in large chunks. */
fun BlockDevice.zero(lba: Long, count: Long, chunkSectors: Int = 2048, onChunk: (Long) -> Unit = {}) {
    val zeros = ByteArray(chunkSectors * BlockDevice.SECTOR)
    var done = 0L
    while (done < count) {
        val n = minOf(chunkSectors.toLong(), count - done).toInt()
        write(lba + done, zeros, 0, n)
        done += n
        onChunk(done)
    }
}

/** A disk image file, used by tests and tools. */
class FileBlockDevice(file: File, override val sectorCount: Long) : BlockDevice {
    private val raf = RandomAccessFile(file, "rw").apply {
        if (length() < sectorCount * BlockDevice.SECTOR) setLength(sectorCount * BlockDevice.SECTOR)
    }

    override fun read(lba: Long, buf: ByteArray, off: Int, sectors: Int) {
        check(lba >= 0 && lba + sectors <= sectorCount) { "read out of range: $lba+$sectors" }
        raf.seek(lba * BlockDevice.SECTOR)
        raf.readFully(buf, off, sectors * BlockDevice.SECTOR)
    }

    override fun write(lba: Long, buf: ByteArray, off: Int, sectors: Int) {
        check(lba >= 0 && lba + sectors <= sectorCount) { "write out of range: $lba+$sectors" }
        raf.seek(lba * BlockDevice.SECTOR)
        raf.write(buf, off, sectors * BlockDevice.SECTOR)
    }

    override fun flush() = raf.fd.sync()

    override fun close() = raf.close()
}
