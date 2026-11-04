package com.ignyx.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ignyx.app.boot.VentoyLayout
import com.ignyx.app.usb.DriveState
import com.ignyx.app.usb.UsbDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Flash }

enum class StepStatus { Waiting, Running, Done }

data class FlashStep(val title: String, val status: StepStatus)

data class FlashState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val steps: List<FlashStep> = defaultSteps(),
    val layoutSummary: String? = null,
    val finished: Boolean = false,
) {
    companion object {
        fun defaultSteps() = listOf(
            "كتابة جدول الأقسام",
            "كتابة محمّل الإقلاع وقسم EFI",
            "فرمتة exFAT",
            "التحقق النهائي",
        ).map { FlashStep(it, StepStatus.Waiting) }
    }
}

data class CopyState(val fileName: String, val progress: Float)

data class UiState(
    val drive: DriveState = DriveState.Disconnected,
    val screen: Screen = Screen.Home,
    val flash: FlashState = FlashState(),
    val copy: CopyState? = null,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val usb = UsbDriveManager(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var flashJob: Job? = null

    fun refresh() {
        viewModelScope.launch {
            val drive = withContext(Dispatchers.IO) {
                runCatching { usb.scan() }.getOrElse { DriveState.Unsupported("USB", it.message ?: "خطأ غير معروف") }
            }
            _state.update { it.copy(drive = drive) }
        }
    }

    fun requestPermission() = usb.requestPermission()

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun addIso(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = "image.iso"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = c.getLong(1)
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(copy = CopyState(name, 0f)) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = resolver.openInputStream(uri) ?: error("ما كدرت أفتح الملف")
                    input.use { stream ->
                        usb.copyToRoot(name, size, stream) { p ->
                            _state.update { it.copy(copy = CopyState(name, p)) }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    copy = null,
                    message = result.fold({ "انضاف $name" }, { e -> e.message ?: "فشل النسخ" }),
                )
            }
            refresh()
        }
    }

    /**
     * Preview of the preparation flow. It computes the real partition plan for the
     * connected drive but does NOT write anything yet: raw block writing of the
     * Ventoy images is the next milestone (see README).
     */
    fun startPreviewFlash() {
        if (flashJob?.isActive == true) return
        val drive = _state.value.drive
        val summary = (drive as? DriveState.Ready)?.let { ready ->
            runCatching {
                val sectors = ready.info.capacityBytes / VentoyLayout.SECTOR_SIZE +
                    VentoyLayout.DATA_START_SECTOR + VentoyLayout.EFI_PART_SECTORS
                val l = VentoyLayout.plan(sectors)
                "القسم الرئيسي ${formatBytes(l.data.sizeBytes)} · EFI ${formatBytes(l.efi.sizeBytes)}"
            }.getOrNull()
        }
        _state.update { it.copy(screen = Screen.Flash, flash = FlashState(running = true, layoutSummary = summary)) }
        flashJob = viewModelScope.launch {
            val count = FlashState.defaultSteps().size
            for (i in 0 until count) {
                setStep(i, StepStatus.Running)
                repeat(25) { t ->
                    delay(60)
                    _state.update { s -> s.copy(flash = s.flash.copy(progress = (i + (t + 1) / 25f) / count)) }
                }
                setStep(i, StepStatus.Done)
            }
            _state.update { s -> s.copy(flash = s.flash.copy(running = false, finished = true)) }
        }
    }

    fun cancelFlash() {
        flashJob?.cancel()
        _state.update { it.copy(flash = FlashState(), screen = Screen.Home) }
    }

    private fun setStep(index: Int, status: StepStatus) = _state.update { s ->
        s.copy(flash = s.flash.copy(steps = s.flash.steps.mapIndexed { i, st -> if (i == index) st.copy(status = status) else st }))
    }

    override fun onCleared() {
        usb.close()
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb)
    else String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
}
