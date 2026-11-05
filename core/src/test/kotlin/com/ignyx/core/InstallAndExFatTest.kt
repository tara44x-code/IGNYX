package com.ignyx.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Builds a full Ventoy-style disk image in build/ventoy-test/disk.img.
 * CI then checks it with fsck.exfat, mounts it with the Linux kernel driver,
 * and boots it in QEMU (see .github/workflows/build.yml).
 */
class InstallAndExFatTest {

    private val outDir = File("build/ventoy-test").apply { mkdirs() }
    private val assets = File("../app/src/main/assets/ventoy")

    private fun images(): VentoyImages {
        val boot = File(assets, "boot.img")
        return if (boot.exists()) {
            println("Using real Ventoy images from $assets")
            VentoyImages(
                bootImg = boot.readBytes(),
                coreImg = { XZInputStream(FileInputStream(File(assets, "core.img.xz"))) },
                efiImg = { XZInputStream(FileInputStream(File(assets, "ventoy.disk.img.xz"))) },
            )
        } else {
            println("Ventoy images not found, using synthetic ones")
            VentoyImages(
                bootImg = ByteArray(512) { 0x11 },
                coreImg = { ByteArrayInputStream(ByteArray(300_000) { 0x22 }) },
                efiImg = { ByteArrayInputStream(ByteArray(32 * 1024 * 1024) { 0x33 }) },
            )
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun installThenAddListDeleteFiles() {
        val image = File(outDir, "disk.img").apply { delete() }
        val sectors = 600L * 1024 * 1024 / 512
        val rnd = Random(42)
        val steps = mutableSetOf<VentoyInstaller.Step>()

        val layout = FileBlockDevice(image, sectors).use { dev ->
            VentoyInstaller.install(dev, images(), random = rnd) { step, _ -> steps += step }
        }
        assertEquals(VentoyInstaller.Step.entries.toSet(), steps)

        val files = linkedMapOf(
            "ignyx-test.iso" to rnd.nextBytes(3 * 1024 * 1024 + 123),
            "empty.iso" to ByteArray(0),
            "Linux Mint 22 عربي – long file name for testing.iso" to rnd.nextBytes(100_000),
            "small.img" to rnd.nextBytes(512),
        )

        FileBlockDevice(image, sectors).use { dev ->
            val vol = ExFatVolume.open(dev, layout.data.startSector)
            assertEquals("Ventoy", vol.label)
            val freeBefore = vol.freeBytes
            files.forEach { (name, data) -> vol.createFile(name, data.size.toLong(), ByteArrayInputStream(data)) }
            assertTrue(vol.freeBytes < freeBefore)

            assertTrue(vol.delete("EMPTY.ISO")) // names are case-insensitive
            assertFalse(vol.delete("missing.iso"))
            files.remove("empty.iso")

            // Overwrite replaces the old file.
            val replaced = rnd.nextBytes(4096)
            vol.createFile("small.img", replaced.size.toLong(), ByteArrayInputStream(replaced))
            files["small.img"] = replaced

            assertThrows(ExFatException::class.java) {
                vol.createFile("bad?.iso", 1, ByteArrayInputStream(ByteArray(1)))
            }
            assertThrows(ExFatException::class.java) {
                vol.createFile("short.iso", 10, ByteArrayInputStream(ByteArray(5)))
            }
        }

        // Re-open from disk and check everything survived.
        FileBlockDevice(image, sectors).use { dev ->
            val mbr = dev.readSectors(0, 1)
            assertTrue(VentoyLayout.detect(mbr, sectors) is VentoyLayout.Detection.Ventoy)
            val vol = ExFatVolume.open(dev, layout.data.startSector)
            val listed = vol.list().filter { !it.isDirectory }.associate { it.name to it.sizeBytes }
            assertEquals(files.mapValues { it.value.size.toLong() }, listed)
            files.forEach { (name, data) -> assertArrayEquals(name, data, vol.readFile(name)) }
        }

        // Manifest for the CI mount check: "<sha256>  <name>" like sha256sum.
        File(outDir, "expected.sha256").writeText(
            files.entries.joinToString("\n", postfix = "\n") { (n, d) -> "${sha256(d)}  $n" }
        )
        File(outDir, "partition.txt").writeText("${layout.data.startSector} ${layout.data.sectorCount}\n")
    }

    @Test
    fun fragmentedFreeSpaceIsReported() {
        val image = File(outDir, "frag.img").apply { delete() }
        val sectors = 200L * 1024 * 1024 / 512
        FileBlockDevice(image, sectors).use { dev ->
            val layout = VentoyLayout.plan(sectors)
            ExFatFormatter.format(dev, layout.data.startSector, layout.data.sectorCount)
            val vol = ExFatVolume.open(dev, layout.data.startSector)
            val cluster = vol.clusterBytes.toLong()
            // 30 equal files, then one file that takes exactly the rest of the space.
            val per = vol.freeBytes / cluster / 31
            for (k in 0 until 30) {
                vol.createFile("f$k.bin", per * cluster, ByteArrayInputStream(ByteArray((per * cluster).toInt())))
            }
            val rest = vol.freeBytes
            vol.createFile("rest.bin", rest, ByteArrayInputStream(ByteArray(rest.toInt())))
            assertEquals(0L, vol.freeBytes)
            // Free every other file: plenty of space in total, but no gap bigger than one file.
            for (k in 0 until 30 step 2) vol.delete("f$k.bin")
            assertTrue(vol.freeBytes >= 15 * per * cluster)
            val want = (per + 1) * cluster
            val e = assertThrows(ExFatException::class.java) {
                vol.createFile("big.iso", want, ByteArrayInputStream(ByteArray(want.toInt())))
            }
            assertTrue(e.message!!.contains("مقطّعة"))
        }
        image.delete()
    }
}
