package com.ignyx.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignyx.app.ui.screens.ConfirmDialog
import com.ignyx.app.ui.screens.FlashScreen
import com.ignyx.app.ui.screens.HomeScreen
import com.ignyx.app.ui.theme.IgnyxTheme
import com.ignyx.app.usb.UsbDriveManager

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(UsbDriveManager.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            IgnyxTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.refresh() // a drive was plugged in while the app was open
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::addIso)
    }

    BackHandler(enabled = state.screen == Screen.Flash) { vm.closeFlash() }

    // Keep the screen awake while writing, so the phone does not sleep mid-way.
    val view = LocalView.current
    val writing = state.flash.running || state.copy != null
    DisposableEffect(writing) {
        view.keepScreenOn = writing
        onDispose { view.keepScreenOn = false }
    }

    AnimatedContent(
        targetState = state.screen,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(
                state = state,
                onRefresh = vm::refresh,
                onGrant = vm::requestPermission,
                onAddIso = { pickIso.launch(arrayOf("*/*")) },
                onPrepare = vm::askInstall,
                onDelete = vm::askDelete,
                onDismissMessage = vm::dismissMessage,
            )
            Screen.Flash -> FlashScreen(state.flash, onBack = vm::closeFlash)
        }
    }

    state.confirm?.let { c ->
        ConfirmDialog(
            confirm = c,
            onConfirm = {
                when (c) {
                    is Confirm.Install -> vm.confirmInstall()
                    is Confirm.Delete -> vm.confirmDelete(c.fileName)
                }
            },
            onDismiss = vm::dismissConfirm,
        )
    }
}
