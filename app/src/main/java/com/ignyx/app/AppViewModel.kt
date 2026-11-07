package com.ignyx.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ignyx.app.theme.ThemeOptions
import com.ignyx.app.theme.ThemeRenderer
import com.ignyx.app.usb.DriveState
import com.ignyx.app.usb.FileItem
import com.ignyx.app.usb.UsbDriveManager
import com.ignyx.core.DriveTools
import com.ignyx.core.VentoyInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class Tab { Drive, Theme, Tools }

enum class StepStatus { Waiting, Running, Done, Failed }

data class OpStep(val title: String, val status: StepStatus)

/** A long whole-drive operation shown full screen: prepare, update, restore, theme. */
data class Operation(
    val title: String,
    val steps: List<OpStep>,
    val progress: Float = 0f,
    val running: Boolean = true,
    val finished: Boolean = false,
    val error: String? = null,
    val doneMessage: String = "",
)

data class CopyState(
    val fileName: String,
    val index: Int,
    val count: Int,
    val progress: Float,
    val speed: Double = 0.0,
    val etaSeconds: Long = -1,
    val verifying: Boolean = false,
)

sealed interface Dialog {
    data class Install(val driveName: String, val diskBytes: Long) : Dialog
    data class Update(val from: String?, val to: String) : Dialog
    data class Restore(val driveName: String, val diskBytes: Long) : Dialog
    data class Delete(val item: FileItem) : Dialog
    data object NewFolder : Dialog
    data object RemoveTheme : Dialog
}

data class ThemeUi(
    val options: ThemeOptions = ThemeOptions(),
    val preview: ImageBitmap? = null,
    val rendering: Boolean = false,
)

data class UiState(
    val drive: DriveState = DriveState.Disconnected,
    val tab: Tab = Tab.Drive,
    val path: String = "",
    val files: List<FileItem> = emptyList(),
    val op: Operation? = null,
    val copy: CopyState? = null,
    val dialog: Dialog? = null,
    val message: String? = null,
    val verifyAfterCopy: Boolean = true,
    val theme: ThemeUi = ThemeUi(),
    val appVentoyVersion: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val usb = UsbDriveManager(app)
    private val prefs = app.getSharedPreferences("ignyx", Context.MODE_PRIVATE)

    /** All USB I/O goes through one thread, one operation at a time. */
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(
        UiState(verifyAfterCopy = prefs.getBoolean("verify", true), appVentoyVersion = usb.appVentoyVersion())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val busy get() = _state.value.op?.running == true || _state.value.copy != null

    init {
        renderPreview()
    }

    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    // ================================================================= drive

    fun refresh() {
        if (busy) return
        viewModelScope.launch {
            val (drive, files, path) = withContext(io) {
                val d = runCatching { usb.scan() }.getOrElse { e ->
                    usb.close()
                    DriveState.Failed("فلاشة USB", e.message ?: "خطأ غير معروف")
                }
                if (d is DriveState.Ready) {
                    var p = _state.value.path
                    val list = runCatching { usb.list(p) }.getOrElse { p = ""; usb.list("") }
                    Triple(d, list, p)
                } else Triple(d, emptyList(), "")
            }
            _state.update { it.copy(drive = drive, files = files, path = path) }
        }
    }

    fun requestPermission() = usb.requestPermission()

    fun openFolder(path: String) {
        if (busy) return
        viewModelScope.launch {
            val list = withContext(io) { runCatching { usb.list(path) } }
            list.onSuccess { files -> _state.update { it.copy(path = path, files = files) } }
                .onFailure { e -> say(e.message ?: "ما كدرت أفتح المجلد") }
        }
    }

    fun goUp() {
        val p = _state.value.path
        if (p.isNotEmpty()) openFolder(p.substringBeforeLast('/', ""))
    }

    // ================================================================= whole-drive operations

    private fun runOperation(
        title: String,
        steps: List<String>,
        doneMessage: String,
        work: suspend (setStep: (Int, Float) -> Unit) -> Unit,
    ) {
        if (busy) return
        _state.update {
            it.copy(dialog = null, op = Operation(title, steps.map { s -> OpStep(s, StepStatus.Waiting) }, doneMessage = doneMessage))
        }
        viewModelScope.launch {
            val setStep: (Int, Float) -> Unit = { idx, p ->
                _state.update { s ->
                    val op = s.op ?: return@update s
                    val n = op.steps.size
                    s.copy(op = op.copy(
                        progress = ((idx + p.coerceIn(0f, 1f)) / n).coerceIn(0f, 1f),
                        steps = op.steps.mapIndexed { i, st ->
                            st.copy(status = when {
                                i < idx -> StepStatus.Done
                                i == idx -> if (p >= 1f) StepStatus.Done else StepStatus.Running
                                else -> StepStatus.Waiting
                            })
                        },
                    ))
                }
            }
            val result = withContext(io) { runCatching { work(setStep) } }
            _state.update { s ->
                val op = s.op ?: return@update s
                result.fold(
                    { s.copy(op = op.copy(running = false, finished = true, progress = 1f, steps = op.steps.map { it.copy(status = StepStatus.Done) })) },
                    { e ->
                        s.copy(op = op.copy(running = false, error = e.message ?: "صار خطأ",
                            steps = op.steps.map { if (it.status == StepStatus.Running) it.copy(status = StepStatus.Failed) else it }))
                    },
                )
            }
            refresh()
        }
    }

    fun closeOperation() {
        if (_state.value.op?.running == true) return // never walk away mid-write
        _state.update { it.copy(op = null) }
    }

    fun askInstall() {
        val (name, bytes) = when (val d = _state.value.drive) {
            is DriveState.NotPrepared -> d.name to d.diskBytes
            is DriveState.Ready -> d.info.name to d.info.diskBytes
            else -> return
        }
        _state.update { it.copy(dialog = Dialog.Install(name, bytes)) }
    }

    private val installWeights = mapOf(
        VentoyInstaller.Step.Prepare to 0, VentoyInstaller.Step.Bootloader to 1, VentoyInstaller.Step.EfiPartition to 2,
        VentoyInstaller.Step.Format to 3, VentoyInstaller.Step.PartitionTable to 4, VentoyInstaller.Step.Verify to 5,
    )

    fun confirmInstall(label: String, reserveGb: Int) {
        _state.update { it.copy(path = "") }
        runOperation(
            title = "تجهيز الفلاشة",
            steps = listOf("تفريغ الفلاشة", "كتابة محمّل الإقلاع", "كتابة قسم EFI", "فرمتة exFAT", "كتابة جدول الأقسام", "التحقق النهائي"),
            doneMessage = "الفلاشة صارت قابلة للإقلاع. ضيف ملفات ISO وشغّل الحاسبة منها.",
        ) { setStep ->
            usb.install(label.ifBlank { "IGNYX" }, reserveGb * 1_000_000_000L) { step, p -> setStep(installWeights.getValue(step), p) }
        }
    }

    fun askUpdate() {
        val d = _state.value.drive as? DriveState.Ready ?: return
        _state.update { it.copy(dialog = Dialog.Update(d.info.report.ventoyVersion, it.appVentoyVersion)) }
    }

    fun confirmUpdate() = runOperation(
        title = "تحديث نظام الإقلاع",
        steps = listOf("تحديث ملفات الإقلاع", "التحقق"),
        doneMessage = "انتحدث نظام الإقلاع، وكل الأنظمة اللي على الفلاشة باقية مثل ما هي.",
    ) { setStep ->
        usb.updateVentoy { p -> if (p < 1f) setStep(0, p) else setStep(1, 1f) }
    }

    fun askRestore() {
        val (name, bytes) = when (val d = _state.value.drive) {
            is DriveState.NotPrepared -> d.name to d.diskBytes
            is DriveState.Ready -> d.info.name to d.info.diskBytes
            else -> return
        }
        _state.update { it.copy(dialog = Dialog.Restore(name, bytes)) }
    }

    fun confirmRestore(fs: DriveTools.PlainFs, label: String) {
        _state.update { it.copy(path = "") }
        runOperation(
            title = "إرجاع الفلاشة لوضعها الطبيعي",
            steps = listOf("مسح وفرمتة ${if (fs == DriveTools.PlainFs.FAT32) "FAT32" else "exFAT"}"),
            doneMessage = "صارت فلاشة عادية. تكدر تستخدمها لنقل الملفات على أي جهاز.",
        ) { setStep ->
            usb.restorePlain(fs, label.ifBlank { "IGNYX" }) { p -> setStep(0, p) }
        }
    }

    // ================================================================= files

    private data class Source(val name: String, val size: Long, val open: () -> InputStream)

    private fun querySource(uri: Uri): Pair<String, Long> {
        val resolver = getApplication<Application>().contentResolver
        var name = "image.iso"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    /** Expands a picked file into what to copy: itself, or the bootable images inside a zip. */
    private fun sourcesFor(uri: Uri): List<Source> {
        val resolver = getApplication<Application>().contentResolver
        val (name, size) = querySource(uri)
        fun openUri(): InputStream = resolver.openInputStream(uri) ?: error("ما كدرت أفتح الملف")
        if (!name.lowercase().endsWith(".zip")) {
            if (size < 0) error("ما كدرت أعرف حجم الملف $name")
            return listOf(Source(name, size, ::openUri))
        }
        // A zip: find the bootable images inside and how big each one is.
        val found = ArrayList<Pair<String, Long>>()
        ZipInputStream(openUri()).use { zip ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || !isBootable(e.name)) continue
                var len = e.size
                if (len < 0) { len = 0; while (true) { val n = zip.read(buf); if (n < 0) break; len += n } }
                found += e.name to len
            }
        }
        if (found.isEmpty()) error("ملف $name ما بيه أي ملف ISO")
        return found.map { (entryName, len) ->
            Source(entryName.substringAfterLast('/'), len) {
                val zip = ZipInputStream(openUri())
                while (true) {
                    val e = zip.nextEntry ?: error("ما لكيت $entryName داخل الملف")
                    if (e.name == entryName) break
                }
                zip
            }
        }
    }

    fun addFiles(uris: List<Uri>) {
        if (busy || uris.isEmpty()) return
        val folder = _state.value.path
        val verify = _state.value.verifyAfterCopy
        _state.update { it.copy(copy = CopyState("…", 1, uris.size, 0f)) }
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val sources = uris.flatMap { sourcesFor(it) }
                    val total = sources.sumOf { it.size }
                    if (total > usb.freeBytes()) error("ماكو مساحة كافية: تحتاج ${formatBytes(total)} والفارغ ${formatBytes(usb.freeBytes())}")
                    sources.forEachIndexed { i, src -> copyOne(src, folder, i, sources.size, verify) }
                    sources.size
                }
            }
            _state.update {
                it.copy(copy = null, message = result.fold(
                    { n -> if (n == 1) "انضاف الملف${if (verify) " وتم التحقق منه" else ""}" else "انضافت $n ملفات${if (verify) " وتم التحقق منها" else ""}" },
                    { e -> e.message ?: "فشل النسخ" },
                ))
            }
            refresh()
        }
    }

    private fun copyOne(src: Source, folder: String, index: Int, count: Int, verify: Boolean) {
        val path = if (folder.isEmpty()) src.name else "$folder/${src.name}"
        val start = SystemClock.elapsedRealtime()
        var lastUi = 0L
        fun report(p: Float, verifying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi < 200 && p < 1f) return
            lastUi = now
            val secs = (now - start) / 1000.0
            val speed = if (secs > 0.5) src.size * p / secs else 0.0
            val eta = if (speed > 0) ((src.size * (1 - p)) / speed).toLong() else -1
            _state.update { it.copy(copy = CopyState(src.name, index + 1, count, p, speed, eta, verifying)) }
        }

        val md = MessageDigest.getInstance("SHA-256")
        src.open().use { raw ->
            usb.copyToDrive(path, src.size, DigestInputStream(raw, md)) { p -> report(p, false) }
        }
        if (!verify) return
        val expected = md.digest()
        val back = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val vStart = SystemClock.elapsedRealtime()
        usb.readBack(path) { b, n ->
            back.update(b, 0, n)
            done += n
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi >= 200) {
                lastUi = now
                val p = if (src.size > 0) done.toFloat() / src.size else 1f
                val secs = (now - vStart) / 1000.0
                val speed = if (secs > 0.5) done / secs else 0.0
                val eta = if (speed > 0) ((src.size - done) / speed).toLong() else -1
                _state.update { it.copy(copy = CopyState(src.name, index + 1, count, p, speed, eta, verifying = true)) }
            }
        }
        if (!back.digest().contentEquals(expected)) {
            runCatching { usb.delete(path) }
            error("${src.name} انكتب بيه خطأ فانحذف. جرّب فلاشة أو وصلة ثانية.")
        }
    }

    fun askDelete(item: FileItem) = _state.update { it.copy(dialog = Dialog.Delete(item)) }

    fun confirmDelete(item: FileItem) {
        if (busy) return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val result = withContext(io) { runCatching { usb.delete(item.path) } }
            say(result.fold({ ok -> if (ok) "انحذف ${ltr(item.name)}" else "الملف مو موجود" }, { e -> e.message ?: "فشل الحذف" }))
            refresh()
        }
    }

    fun askNewFolder() = _state.update { it.copy(dialog = Dialog.NewFolder) }

    fun createFolder(name: String) {
        if (busy || name.isBlank()) return
        val path = _state.value.path.let { if (it.isEmpty()) name.trim() else "$it/${name.trim()}" }
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val result = withContext(io) { runCatching { usb.mkdir(path) } }
            result.onFailure { e -> say(e.message ?: "ما كدرت أسوي المجلد") }
            refresh()
        }
    }

    fun setVerify(on: Boolean) {
        prefs.edit().putBoolean("verify", on).apply()
        _state.update { it.copy(verifyAfterCopy = on) }
    }

    // ================================================================= theme

    private var renderJob: Job? = null

    private fun updateTheme(change: (ThemeOptions) -> ThemeOptions) {
        _state.update { it.copy(theme = it.theme.copy(options = change(it.theme.options))) }
        renderPreview()
    }

    fun setAccent(color: Int) = updateTheme { it.copy(accent = color) }
    fun setResolution(w: Int, h: Int) = updateTheme { it.copy(width = w, height = h) }
    fun setDim(v: Float) = updateTheme { it.copy(dim = v) }
    fun setShowLogo(v: Boolean) = updateTheme { it.copy(showLogo = v) }
    fun setTitle(v: String) = updateTheme { it.copy(title = v.take(24)) }
    fun setCleanBoot(v: Boolean) = updateTheme { it.copy(cleanBoot = v) }
    fun clearPhoto() = updateTheme { it.copy(photo = null) }

    fun pickPhoto(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decodePhoto(uri) }.getOrNull() }
            if (bmp == null) say("ما كدرت أفتح الصورة") else updateTheme { it.copy(photo = bmp) }
        }
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2560 || bounds.outHeight / (sample * 2) >= 2560) sample *= 2
        val bmp = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("bad image")
        val rotation = runCatching {
            resolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun renderPreview() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(120) // let quick slider moves settle
            _state.update { it.copy(theme = it.theme.copy(rendering = true)) }
            val o = _state.value.theme.options
            val w = 960
            val h = (960f * o.height / o.width).toInt()
            val img = withContext(Dispatchers.Default) {
                ThemeRenderer.background(getApplication(), o.copy(width = w, height = h)).asImageBitmap()
            }
            _state.update { it.copy(theme = it.theme.copy(preview = img, rendering = false)) }
        }
    }

    fun applyTheme() {
        val o = _state.value.theme.options
        runOperation(
            title = "تطبيق مظهر الإقلاع",
            steps = listOf("رسم الخلفية والإطار", "الكتابة على الفلاشة"),
            doneMessage = "المظهر انكتب على الفلاشة. شغّل الحاسبة منها حتى تشوفه.",
        ) { setStep ->
            setStep(0, 0.1f)
            val theme = ThemeRenderer.build(getApplication(), o)
            setStep(0, 1f)
            usb.installTheme(theme) { p -> setStep(1, p) }
        }
    }

    fun askRemoveTheme() = _state.update { it.copy(dialog = Dialog.RemoveTheme) }

    fun confirmRemoveTheme() {
        if (busy) return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val r = withContext(io) { runCatching { usb.removeTheme() } }
            say(r.fold({ "رجع المظهر الافتراضي" }, { e -> e.message ?: "فشل الحذف" }))
            refresh()
        }
    }

    override fun onCleared() {
        usb.close()
    }
}
