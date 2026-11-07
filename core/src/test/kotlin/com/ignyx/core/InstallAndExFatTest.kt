package com.ignyx.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Builds full disk images in build/ventoy-test/ that CI then checks with fsck,
 * mounts with the Linux kernel drivers, and boots in QEMU (see .github/workflows/build.yml).
 */
class InstallAndExFatTest {

    private val outDir = File("build/ventoy-test").apply { mkdirs() }
    private val assets = File("../app/src/main/assets/ventoy")
    private val realImages = File(assets, "boot.img").exists()

    private fun images(): VentoyImages = if (realImages) {
        println("Using real Ventoy images from $assets")
        VentoyImages(
            bootImg = File(assets, "boot.img").readBytes(),
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

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun clustersFor(size: Long, cluster: Int) = Bytes.ceilDiv(size, cluster.toLong()) * cluster

    // ------------------------------------------------------------------ theme images (JVM side)

    private fun png(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun solid(w: Int, h: Int, c: Color): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.color = c; g.fillRect(0, 0, w, h); g.dispose()
        return png(img)
    }

    private fun testTheme(): BootTheme {
        val w = 1024; val h = 768
        val bg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = bg.createGraphics()
        g.color = Color(0x05, 0x06, 0x0F); g.fillRect(0, 0, w, h)
        g.color = Color(0x22, 0xF1, 0xFF, 40)
        for (x in 0 until w step 32) g.drawLine(x, 0, x, h)
        for (y in 0 until h step 32) g.drawLine(0, y, w, y)
        g.color = Color(0x22, 0xF1, 0xFF); g.font = g.font.deriveFont(48f); g.drawString("IGNYX", w / 2 - 80, 140)
        g.dispose()
        val pieces = HashMap<String, ByteArray>()
        for (s in BootTheme.PIECE_SUFFIXES) {
            pieces["menu_$s.png"] = solid(8, 8, if (s == "c") Color(0x0E, 0x12, 0x26, 220) else Color(0x22, 0xF1, 0xFF, 200))
            pieces["select_$s.png"] = solid(8, 8, Color(0x22, 0xF1, 0xFF))
            pieces["slider_$s.png"] = solid(4, 4, Color(0xFF, 0x3D, 0xCB))
        }
        return BootTheme(w, h, png(bg), pieces, "#EEF1FF", "#04121A", "#8E97C2", versionColor = "#05060F", hideVersion = true)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun installThenAddListDeleteFilesThemeAndUpdate() {
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
            val base = vol.usedBytes
            files.forEach { (name, data) -> vol.createFile(name, data.size.toLong(), ByteArrayInputStream(data)) }
            // Used space must be exactly the files' clusters.
            assertEquals(files.values.sumOf { clustersFor(it.size.toLong(), vol.clusterBytes) }, vol.usedBytes - base)

            assertTrue(vol.delete("EMPTY.ISO")) // names are case-insensitive
            assertFalse(vol.delete("missing.iso"))
            files.remove("empty.iso")

            val replaced = rnd.nextBytes(4096)
            vol.createFile("small.img", replaced.size.toLong(), ByteArrayInputStream(replaced))
            files["small.img"] = replaced
            assertEquals(files.values.sumOf { clustersFor(it.size.toLong(), vol.clusterBytes) }, vol.usedBytes - base)

            assertThrows(ExFatException::class.java) { vol.createFile("bad?.iso", 1, ByteArrayInputStream(ByteArray(1))) }
            assertThrows(ExFatException::class.java) { vol.createFile("short.iso", 10, ByteArrayInputStream(ByteArray(5))) }

            // Boot menu theme in a nested folder.
            val theme = testTheme()
            // Written the way Android's org.json does it, with escaped slashes.
            ThemeWriter.install(vol, theme, ThemeWriter.freshJson(theme).replace("/", "\\/"))
            val written = String(vol.readFile(BootTheme.CONFIG))
            assertFalse(written, written.contains("\\/"))
            assertTrue(written.contains("\"/ventoy/theme/ignyx/theme.txt\""))
            assertTrue(vol.isDirectory("ventoy/theme/ignyx"))
            assertTrue(String(vol.readFile(BootTheme.FILE)).contains("selected_item_pixmap_style"))
            println(String(vol.readFile(BootTheme.CONFIG)))
        }

        // Update the Ventoy boot files and make sure nothing on the data partition changed.
        FileBlockDevice(image, sectors).use { dev ->
            val before = dev.readSectors(0, 1)
            DriveTools.updateVentoy(dev, images())
            val after = dev.readSectors(0, 1)
            assertArrayEquals(before.copyOfRange(384, 400), after.copyOfRange(384, 400)) // UUID kept
            assertArrayEquals(before.copyOfRange(440, 512), after.copyOfRange(440, 512)) // table kept

            val report = DriveTools.inspect(dev)
            assertEquals("MBR", report.partitionStyle)
            assertTrue(report.isVentoy)
            assertEquals("exFAT", report.fileSystem)
            if (realImages) {
                val expected = File(assets, "VERSION").readText().trim()
                assertEquals(expected, report.ventoyVersion)
                println("Ventoy version on drive: ${report.ventoyVersion}")
                val cfg = FatReader(dev, layout.efi.startSector).readFile("grub/grub.cfg")!!
                String(cfg).lines().filter { it.contains("theme", true) || it.contains("font", true) || it.contains("gfx", true) }
                    .take(40).forEach { println("grub.cfg: $it") }
            }
        }

        // Re-open from disk and check everything survived.
        FileBlockDevice(image, sectors).use { dev ->
            val vol = ExFatVolume.open(dev, layout.data.startSector)
            val listed = vol.list().filter { !it.isDirectory }.associate { it.name to it.sizeBytes }
            assertEquals(files.mapValues { it.value.size.toLong() }, listed)
            files.forEach { (name, data) -> assertArrayEquals(name, data, vol.readFile(name)) }
            // Streamed read gives the same bytes.
            val md = MessageDigest.getInstance("SHA-256")
            vol.readFileChunks("ignyx-test.iso") { b, n -> md.update(b, 0, n) }
            assertEquals(sha256(files.getValue("ignyx-test.iso")), md.digest().joinToString("") { "%02x".format(it) })
        }

        File(outDir, "expected.sha256").writeText(
            files.entries.joinToString("\n", postfix = "\n") { (n, d) -> "${sha256(d)}  $n" }
        )
    }

    @Test
    fun foldersCanBeCreatedListedAndDeleted() {
        val image = File(outDir, "folders.img").apply { delete() }
        val sectors = 200L * 1024 * 1024 / 512
        FileBlockDevice(image, sectors).use { dev ->
            val layout = VentoyLayout.plan(sectors)
            ExFatFormatter.format(dev, layout.data.startSector, layout.data.sectorCount)
            val vol = ExFatVolume.open(dev, layout.data.startSector)
            val base = vol.usedBytes
            vol.mkdirs("Windows/11")
            vol.createFile("Windows/11/win.iso", 70_000, ByteArrayInputStream(ByteArray(70_000) { 7 }))
            vol.createFile("Linux/ubuntu.iso", 5_000, ByteArrayInputStream(ByteArray(5_000) { 9 }))
            assertEquals(setOf("Windows", "Linux"), vol.list().map { it.name }.toSet())
            assertTrue(vol.list().all { it.isDirectory })
            assertEquals(listOf("win.iso"), vol.list("windows/11").map { it.name })
            assertEquals(75_000L, vol.sizeOf(""))
            assertArrayEquals(ByteArray(5_000) { 9 }, vol.readFile("Linux/ubuntu.iso"))
            assertThrows(ExFatException::class.java) { vol.createFile("Linux", 1, ByteArrayInputStream(ByteArray(1))) }

            assertTrue(vol.delete("Windows"))
            assertFalse(vol.exists("Windows/11/win.iso"))
            assertTrue(vol.delete("Linux"))
            assertEquals(base, vol.usedBytes)
            // A fresh open sees the same state.
            assertEquals(base, ExFatVolume.open(dev, layout.data.startSector).usedBytes)
        }
        image.delete()
    }

    @Test
    fun restorePlainDrives() {
        for (fs in DriveTools.PlainFs.entries) {
            val image = File(outDir, "plain-${fs.name.lowercase()}.img").apply { delete() }
            val sectors = 600L * 1024 * 1024 / 512
            FileBlockDevice(image, sectors).use { dev ->
                DriveTools.restorePlain(dev, fs, "IGNYX", Random(1))
                val report = DriveTools.inspect(dev)
                assertEquals("MBR", report.partitionStyle)
                assertFalse(report.isVentoy)
                if (fs == DriveTools.PlainFs.EXFAT) {
                    val vol = ExFatVolume.open(dev, 2048)
                    vol.createFile("hello.txt", 5, ByteArrayInputStream("hello".toByteArray()))
                } else {
                    assertEquals(32, FatReader(dev, 2048).type)
                }
            }
        }
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
            val per = vol.freeBytes / cluster / 31
            for (k in 0 until 30) {
                vol.createFile("f$k.bin", per * cluster, ByteArrayInputStream(ByteArray((per * cluster).toInt())))
            }
            val rest = vol.freeBytes
            vol.createFile("rest.bin", rest, ByteArrayInputStream(ByteArray(rest.toInt())))
            assertEquals(0L, vol.freeBytes)
            for (k in 0 until 30 step 2) vol.delete("f$k.bin")
            assertTrue(vol.freeBytes >= 15 * per * cluster)
            assertEquals(per * cluster, vol.largestFreeRunBytes())
            val want = (per + 1) * cluster
            val e = assertThrows(ExFatException::class.java) {
                vol.createFile("big.iso", want, ByteArrayInputStream(ByteArray(want.toInt())))
            }
            assertTrue(e.message!!.contains("مقطّعة"))
        }
        image.delete()
    }

    /**
     * Runs only in CI, on a volume made by Linux's mkfs.exfat with files copied by the
     * Linux driver: IGNYX must read them, then add and delete files without damage.
     */
    @Test
    fun worksOnVolumesMadeByOtherTools() {
        val path = System.getenv("EXTERNAL_EXFAT_IMAGE") ?: return
        val image = File(path)
        val sectors = image.length() / 512
        FileBlockDevice(image, sectors).use { dev ->
            val vol = ExFatVolume.open(dev, 2048)
            val names = vol.list().map { it.name }.toSet()
            println("External volume has: $names")
            assertTrue(names.contains("from-linux.bin"))
            assertNotNull(vol.readFile("from-linux.bin"))
            assertTrue(vol.isDirectory("linux-folder"))
            val data = Random(3).nextBytes(2_000_000)
            vol.createFile("from-ignyx.iso", data.size.toLong(), ByteArrayInputStream(data))
            vol.createFile("linux-folder/nested-from-ignyx.txt", 3, ByteArrayInputStream("abc".toByteArray()))
            assertTrue(vol.delete("delete-me.bin"))
            File(outDir, "external.sha256").writeText("${sha256(data)}  from-ignyx.iso\n")
        }
    }
}
