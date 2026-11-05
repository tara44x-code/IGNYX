package com.ignyx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VentoyLayoutTest {

    @Test
    fun efiPartitionSitsAtTheEndAndIsAligned() {
        val total = 120_831_999L // a typical "64 GB" stick
        val l = VentoyLayout.plan(total)
        assertEquals(2048L, l.data.startSector)
        assertEquals(0L, l.data.sectorCount % 8)
        assertEquals(l.data.startSector + l.data.sectorCount, l.efi.startSector)
        assertEquals(65536L, l.efi.sectorCount)
        assertTrue(l.efi.endSector < total)
    }

    @Test
    fun mbrRoundTripsThroughDetection() {
        val l = VentoyLayout.plan(30_000_000L)
        val uuid = ByteArray(16) { it.toByte() }
        val mbr = VentoyLayout.buildMbr(l, ByteArray(512) { 0x33 }, 0x1234ABCD, uuid)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0x80, mbr[446].toInt() and 0xFF)
        assertEquals(0x07, mbr[450].toInt() and 0xFF)
        assertEquals(2048L, Bytes.le32(mbr, 454))
        assertEquals(0xEF, mbr[466].toInt() and 0xFF)
        assertEquals(0x1234ABCDL, Bytes.le32(mbr, 440))
        assertEquals(5, mbr[384 + 5].toInt())
        assertEquals(0x33, mbr[0].toInt())
        val d = VentoyLayout.detect(mbr, 30_000_000L)
        assertTrue(d is VentoyLayout.Detection.Ventoy)
        assertEquals(l, (d as VentoyLayout.Detection.Ventoy).layout)
    }

    @Test
    fun detectsGptAndBlankDisks() {
        val gpt = ByteArray(512).also { it[450] = 0xEE.toByte(); it[510] = 0x55; it[511] = 0xAA.toByte() }
        assertEquals(VentoyLayout.Detection.GptDisk, VentoyLayout.detect(gpt, 1_000_000))
        assertEquals(VentoyLayout.Detection.Other, VentoyLayout.detect(ByteArray(512), 1_000_000))
    }

    @Test
    fun rejectsTinyDrives() {
        assertThrows(IllegalArgumentException::class.java) { VentoyLayout.plan(50_000L) }
    }
}
