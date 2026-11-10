// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import com.miauto.app.IcocaHost

/**
 * Registers DiPlay's ICCOA projection page with the ported engine. Without this the engine's
 * IcocaHost.ProjectionScreen stays null, so every heartbeat says 页面=后台 even while the
 * projection is on screen; the engine then treats the page as hidden and the mirrored picture
 * stays black. MiAuto had this seam wired to its own activity; DiPlay must wire it here.
 */
internal object IcocaProjectionHost : IcocaHost.ProjectionScreen {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var foreground = false

    fun attach(context: Context) {
        appContext = context.applicationContext
        IcocaHost.setProjectionScreen(this)
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    override fun isForeground(): Boolean = foreground

    override fun open() {
        val context = appContext ?: return
        if (foreground) return
        context.startActivity(
            Intent(context, IcocaProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}
