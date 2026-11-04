package com.ignyx.app.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VentoyLayoutTest {

    private fun le32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    @Test
    fun efiPartitionSitsAtTheEndAndIsAligned() {
        val total = 120_831_999L // a typical "64 GB" stick
        val l = VentoyLayout.plan(total)
        assertEquals(2048L, l.data.startSector)
        assertEquals(0L, l.data.sectorCount % 8)
        assertEquals(l.data.startSector + l.data.sectorCount, l.efi.startSector)
        assertEquals(65536L, l.efi.sectorCount)
        assert(l.efi.endSector < total)
    }

    @Test
    fun mbrHasPartitionTableAndSignature() {
        val l = VentoyLayout.plan(30_000_000L)
        val mbr = VentoyLayout.buildMbr(l, null, 0x1234ABCD)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0x80, mbr[446].toInt() and 0xFF)
        assertEquals(0x07, mbr[450].toInt() and 0xFF)
        assertEquals(2048L, le32(mbr, 454))
        assertEquals(l.data.sectorCount, le32(mbr, 458))
        assertEquals(0xEF, mbr[466].toInt() and 0xFF)
        assertEquals(l.efi.startSector, le32(mbr, 470))
        assertEquals(0x1234ABCDL, le32(mbr, 440))
    }

    @Test
    fun rejectsTinyDrives() {
        assertThrows(IllegalArgumentException::class.java) { VentoyLayout.plan(50_000L) }
    }
}
