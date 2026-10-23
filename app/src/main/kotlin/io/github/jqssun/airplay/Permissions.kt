package io.github.jqssun.airplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
const val BUILD_VERSION_CINNAMON_BUN = 36

fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < BUILD_VERSION_CINNAMON_BUN ||
    ContextCompat.checkSelfPermission(this, PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
