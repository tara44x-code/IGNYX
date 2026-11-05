package com.ignyx.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ignyx.app.usb.DriveState
import com.ignyx.app.usb.UsbDriveManager
import com.ignyx.core.VentoyInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Flash }

enum class StepStatus { Waiting, Running, Done, Failed }

data class FlashStep(val step: VentoyInstaller.Step, val title: String, val status: StepStatus)

data class FlashState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val steps: List<FlashStep> = defaultSteps(),
    val finished: Boolean = false,
    val error: String? = null,
) {
    companion object {
        private val titles = mapOf(
            VentoyInstaller.Step.Prepare to "تفريغ الفلاشة",
            VentoyInstaller.Step.Bootloader to "كتابة محمّل الإقلاع",
            VentoyInstaller.Step.EfiPartition to "كتابة قسم EFI",
            VentoyInstaller.Step.Format to "فرمتة exFAT",
            VentoyInstaller.Step.PartitionTable to "كتابة جدول الأقسام",
            VentoyInstaller.Step.Verify to "التحقق النهائي",
        )

        /** Rough share of the total time each step takes, for one smooth progress number. */
        val weights = mapOf(
            VentoyInstaller.Step.Prepare to 0.08f,
            VentoyInstaller.Step.Bootloader to 0.05f,
            VentoyInstaller.Step.EfiPartition to 0.55f,
            VentoyInstaller.Step.Format to 0.27f,
            VentoyInstaller.Step.PartitionTable to 0.02f,
            VentoyInstaller.Step.Verify to 0.03f,
        )

        fun defaultSteps() = VentoyInstaller.Step.entries.map { FlashStep(it, titles.getValue(it), StepStatus.Waiting) }
    }
}

data class CopyState(val fileName: String, val progress: Float)

/** A destructive action waiting for the user to confirm it. */
sealed interface Confirm {
    data class Install(val driveName: String, val diskBytes: Long) : Confirm
    data class Delete(val fileName: String) : Confirm
}

data class UiState(
    val drive: DriveState = DriveState.Disconnected,
    val screen: Screen = Screen.Home,
    val flash: FlashState = FlashState(),
    val copy: CopyState? = null,
    val confirm: Confirm? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val usb = UsbDriveManager(app)

    /** All USB I/O goes through one thread, one operation at a time. */
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val busy get() = _state.value.flash.running || _state.value.copy != null

    fun refresh() {
        if (busy) return
        viewModelScope.launch {
            val drive = withContext(io) {
                runCatching { usb.scan() }.getOrElse { e ->
                    usb.close()
                    DriveState.Failed("فلاشة USB", e.message ?: "خطأ غير معروف")
                }
            }
            _state.update { it.copy(drive = drive) }
        }
    }

    fun requestPermission() = usb.requestPermission()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    // ------------------------------------------------------------- install

    fun askInstall() {
        val (name, bytes) = when (val d = _state.value.drive) {
            is DriveState.NotPrepared -> d.name to d.diskBytes
            is DriveState.Ready -> d.info.name to d.info.diskBytes
            else -> return
        }
        _state.update { it.copy(confirm = Confirm.Install(name, bytes)) }
    }

    fun confirmInstall() {
        if (busy) return
        _state.update { it.copy(confirm = null, screen = Screen.Flash, flash = FlashState(running = true)) }
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    usb.install { step, p -> onInstallProgress(step, p) }
                }
            }
            result.onSuccess {
                _state.update { s ->
                    s.copy(flash = s.flash.copy(running = false, finished = true, progress = 1f,
                        steps = s.flash.steps.map { it.copy(status = StepStatus.Done) }))
                }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(flash = s.flash.copy(running = false, error = e.message ?: "فشل التجهيز",
                        steps = s.flash.steps.map { if (it.status == StepStatus.Running) it.copy(status = StepStatus.Failed) else it }))
                }
            }
            refresh()
        }
    }

    private fun onInstallProgress(step: VentoyInstaller.Step, p: Float) {
        val order = VentoyInstaller.Step.entries
        val before = order.takeWhile { it != step }.sumOf { FlashState.weights.getValue(it).toDouble() }.toFloat()
        val total = before + FlashState.weights.getValue(step) * p
        _state.update { s ->
            s.copy(flash = s.flash.copy(
                progress = total.coerceIn(0f, 1f),
                steps = s.flash.steps.map {
                    val idx = order.indexOf(it.step)
                    val cur = order.indexOf(step)
                    it.copy(status = when {
                        idx < cur -> StepStatus.Done
                        idx == cur -> if (p >= 1f) StepStatus.Done else StepStatus.Running
                        else -> StepStatus.Waiting
                    })
                },
            ))
        }
    }

    fun closeFlash() {
        if (_state.value.flash.running) return // never walk away mid-write
        _state.update { it.copy(screen = Screen.Home, flash = FlashState()) }
    }

    // ------------------------------------------------------------- files

    fun addIso(uri: Uri) {
        if (busy) return
        val resolver = getApplication<Application>().contentResolver
        var name = "image.iso"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            _state.update { it.copy(message = "ما كدرت أعرف حجم الملف") }
            return
        }
        _state.update { it.copy(copy = CopyState(name, 0f)) }
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val input = resolver.openInputStream(uri) ?: error("ما كدرت أفتح الملف")
                    input.use { stream ->
                        var last = 0f
                        usb.copyToDrive(name, size, stream) { p ->
                            if (p - last >= 0.005f || p >= 1f) {
                                last = p
                                _state.update { it.copy(copy = CopyState(name, p)) }
                            }
                        }
                    }
                }
            }
            _state.update {
                it.copy(copy = null, message = result.fold({ "انضاف $name" }, { e -> e.message ?: "فشل النسخ" }))
            }
            refresh()
        }
    }

    fun askDelete(fileName: String) = _state.update { it.copy(confirm = Confirm.Delete(fileName)) }

    fun confirmDelete(fileName: String) {
        if (busy) return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            val result = withContext(io) { runCatching { usb.delete(fileName) } }
            _state.update {
                it.copy(message = result.fold({ ok -> if (ok) "انحذف $fileName" else "الملف مو موجود" }, { e -> e.message ?: "فشل الحذف" }))
            }
            refresh()
        }
    }

    fun ventoyVersion(): String = usb.ventoyVersion()

    override fun onCleared() {
        usb.close()
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb)
    else String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
}
