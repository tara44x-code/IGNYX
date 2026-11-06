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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignyx.app.ui.components.Icon24
import com.ignyx.app.ui.components.IgnyxIcon
import com.ignyx.app.ui.components.IgnyxMark
import com.ignyx.app.ui.components.IgnyxWordmark
import com.ignyx.app.ui.components.neonGlow
import com.ignyx.app.ui.screens.AppDialog
import com.ignyx.app.ui.screens.DialogActions
import com.ignyx.app.ui.screens.DriveActions
import com.ignyx.app.ui.screens.DriveScreen
import com.ignyx.app.ui.screens.OperationScreen
import com.ignyx.app.ui.screens.ThemeActions
import com.ignyx.app.ui.screens.ThemeScreen
import com.ignyx.app.ui.screens.ToolsActions
import com.ignyx.app.ui.screens.ToolsScreen
import com.ignyx.app.ui.theme.IgnyxTheme
import com.ignyx.app.ui.theme.Neon
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
        vm.refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickPhoto)
    }

    // Keep the screen awake while writing, so the phone does not sleep mid-way.
    val view = LocalView.current
    val writing = state.op?.running == true || state.copy != null
    DisposableEffect(writing) {
        view.keepScreenOn = writing
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = state.op != null) { vm.closeOperation() }
    BackHandler(enabled = state.op == null && state.tab == Tab.Drive && state.path.isNotEmpty()) { vm.goUp() }
    BackHandler(enabled = state.op == null && state.tab != Tab.Drive) { vm.selectTab(Tab.Drive) }

    Box(Modifier.fillMaxSize().background(Neon.Night).safeDrawingPadding()) {
        val op = state.op
        if (op != null) {
            OperationScreen(op, onClose = vm::closeOperation)
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(onRefresh = vm::refresh)
                AnimatedContent(
                    targetState = state.tab,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "tab",
                ) { tab ->
                    when (tab) {
                        Tab.Drive -> DriveScreen(
                            state,
                            DriveActions(
                                refresh = vm::refresh,
                                grant = vm::requestPermission,
                                prepare = vm::askInstall,
                                addFiles = { pickFiles.launch(arrayOf("*/*")) },
                                openFolder = vm::openFolder,
                                up = vm::goUp,
                                delete = vm::askDelete,
                                newFolder = vm::askNewFolder,
                                update = vm::askUpdate,
                            ),
                        )
                        Tab.Theme -> ThemeScreen(
                            state,
                            ThemeActions(
                                pickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                clearPhoto = vm::clearPhoto,
                                setDim = vm::setDim,
                                setLogo = vm::setShowLogo,
                                setAccent = vm::setAccent,
                                setResolution = vm::setResolution,
                                apply = vm::applyTheme,
                                remove = vm::askRemoveTheme,
                            ),
                        )
                        Tab.Tools -> ToolsScreen(
                            state,
                            ToolsActions(
                                prepare = vm::askInstall,
                                update = vm::askUpdate,
                                restore = vm::askRestore,
                                setVerify = vm::setVerify,
                            ),
                        )
                    }
                }
                state.message?.let { MessageBar(it, vm::dismissMessage) }
                BottomNav(state.tab, vm::selectTab)
            }
        }
    }

    state.dialog?.let { d ->
        AppDialog(
            d,
            DialogActions(
                dismiss = vm::dismissDialog,
                install = vm::confirmInstall,
                update = vm::confirmUpdate,
                restore = vm::confirmRestore,
                delete = vm::confirmDelete,
                newFolder = vm::createFolder,
                removeTheme = vm::confirmRemoveTheme,
            ),
        )
    }
}

@Composable
private fun TopBar(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IgnyxMark(34.dp, glow = false)
            IgnyxWordmark(21.sp)
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Neon.Surface)
                .border(1.dp, Neon.LineStrong, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "تحديث", onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) { Icon24(IgnyxIcon.Refresh, Neon.Text, 20.dp, 2f) }
    }
}

@Composable
private fun BottomNav(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF070914)).border(1.dp, Neon.Line)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        listOf(
            Triple(Tab.Drive, IgnyxIcon.Usb, "الفلاشة"),
            Triple(Tab.Theme, IgnyxIcon.Palette, "المظهر"),
            Triple(Tab.Tools, IgnyxIcon.Tools, "الأدوات"),
        ).forEach { (t, icon, label) ->
            val on = t == tab
            Column(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.then(if (on) Modifier.neonGlow(Neon.Cyan, 12.dp, 0.25f, filled = false) else Modifier)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Neon.Cyan.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Icon24(icon, if (on) Neon.Cyan else Neon.Muted, 22.dp, 2f) }
                Text(
                    label, color = if (on) Neon.Cyan else Neon.Muted,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0A0D1C)).clickable(onClick = onDismiss)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Neon.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("تمام", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
