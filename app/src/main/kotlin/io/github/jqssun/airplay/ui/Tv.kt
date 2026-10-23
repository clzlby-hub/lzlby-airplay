package io.github.jqssun.airplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

fun Context.isTvDevice(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
    val config = resources.configuration
    return config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 800
}

@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.isTvDevice() }
}

fun getLocalIpAddress(context: Context): String {
    try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ipInt = wm?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            return String.format(
                java.util.Locale.getDefault(),
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            for (nIf in interfaces) {
                if (nIf.isLoopback || !nIf.isUp) continue
                for (addr in nIf.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && host.isNotEmpty()) return host
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return "局域网"
}

fun Modifier.tvItemFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    focusBorderColor: Color = Color(0xFF64B5F6),
    focusBgColor: Color = Color(0x3364B5F6),
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
        .then(
            if (isFocused) {
                Modifier
                    .background(focusBgColor, shape)
                    .border(3.dp, focusBorderColor, shape)
            } else {
                Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            }
        )
}

fun Modifier.dpadFocus(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) Color(0xFF64B5F6) else Color.Transparent, shape)
}

fun Modifier.dpadAdjust(onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft(); true }
            Key.DirectionRight -> { onRight(); true }
            // slider
            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
            Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
            else -> false
        }
    }
}
