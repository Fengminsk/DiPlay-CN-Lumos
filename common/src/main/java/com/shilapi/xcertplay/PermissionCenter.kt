package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Everything DiPlay needs from the operating system, in one list.
 *
 * The settings header shows a badge while anything here is missing, and the driver grants the
 * items one by one; each row disappears as soon as its check passes. Nothing in this list is
 * requested at launch beyond the runtime group, because a permission prompt is only meaningful
 * when the driver is looking at it.
 */
internal object PermissionCenter {

    const val REQUEST_CODE = 0x9E01

    class Item(
        val title: String,
        val summary: String,
        val granted: Boolean,
        val request: () -> Unit,
    )

    fun items(context: Context): List<Item> = buildList {
        add(
            Item(
                title = context.getString(R.string.permission_center_location),
                summary = context.getString(R.string.permission_center_location_summary),
                granted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
                request = { requestRuntime(context, Manifest.permission.ACCESS_FINE_LOCATION) },
            ),
        )
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(
            Item(
                title = context.getString(R.string.permission_center_bluetooth),
                summary = context.getString(R.string.permission_center_bluetooth_summary),
                granted = bluetooth.all { granted(context, it) },
                request = { requestRuntime(context, *bluetooth) },
            ),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Item(
                    title = context.getString(R.string.permission_center_nearby_wifi),
                    summary = context.getString(R.string.permission_center_nearby_wifi_summary),
                    granted = granted(context, Manifest.permission.NEARBY_WIFI_DEVICES),
                    request = { requestRuntime(context, Manifest.permission.NEARBY_WIFI_DEVICES) },
                ),
            )
            add(
                Item(
                    title = context.getString(R.string.permission_center_notifications),
                    summary = context.getString(R.string.permission_center_notifications_summary),
                    granted = granted(context, Manifest.permission.POST_NOTIFICATIONS),
                    request = { requestRuntime(context, Manifest.permission.POST_NOTIFICATIONS) },
                ),
            )
        }
        add(
            Item(
                title = context.getString(R.string.usb_auto_confirm_title),
                summary = context.getString(R.string.permission_center_accessibility_summary),
                granted = UsbAutoConfirmService.isEnabled(context),
                request = { UsbAutoConfirmService.openSettings(context) },
            ),
        )
        add(
            Item(
                title = context.getString(R.string.permission_center_overlay),
                summary = context.getString(R.string.permission_center_overlay_summary),
                granted = Settings.canDrawOverlays(context),
                request = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            ),
        )
    }

    fun missing(context: Context): List<Item> = items(context).filterNot { it.granted }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun requestRuntime(context: Context, vararg permissions: String) {
        if (context is android.app.Activity) {
            ActivityCompat.requestPermissions(context, permissions, REQUEST_CODE)
        }
    }
}
