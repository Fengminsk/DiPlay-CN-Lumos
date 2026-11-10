// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.shilapi.xcertplay.icoca.IcocaEngine
import kotlinx.coroutines.launch

/**
 * Full-screen ICCOA projection surface. The pairing card expands into this page; the SDK renders
 * the phone's mirror into [surfaceView] and receives touches from it.
 */
class IcocaProjectionActivity : ComponentActivity() {

    private lateinit var surfaceView: SurfaceView
    private val bridge by lazy { IcocaEngine.bridge(this) }
    private var boundSurface: android.view.Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private var firstResume = true

    private fun bindSurface(holder: SurfaceHolder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        // surfaceChanged can fire twice for the same size (system bars / fullscreen insets). Each
        // setSurface() restarts the decoder, which then waits for a new keyframe and shows black.
        if (surface === boundSurface && width == boundWidth && height == boundHeight) return
        boundSurface = surface
        boundWidth = width
        boundHeight = height
        bridge.setSurface(surface, width, height)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IcocaProjectionHost.attach(this)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.BLACK)
            // Place the video surface above the window background. Without this the opaque
            // window background is composited over the SurfaceView and the decoder's frames
            // are never visible even though startCast succeeds.
            setZOrderOnTop(true)
        }
        setContentView(surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Wait for surfaceChanged: at surfaceCreated the view has no measured size yet.
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                bindSurface(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                boundSurface = null
                bridge.onSurfaceDestroyed()
            }
        })
        surfaceView.setOnTouchListener { _, event -> bridge.sendTouch(event) }
        val preview = intent.getBooleanExtra("iccoa_preview", false)
        lifecycleScope.launch {
            bridge.state.collect { state ->
                if (!preview && (state.phase == IcocaBridge.Phase.Disconnected || state.phase == IcocaBridge.Phase.Idle)) {
                    if (!isFinishing) finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        IcocaProjectionHost.setForeground(true)
        // The first resume happens before surfaceChanged has bound the surface; resuming there
        // starts the decoder twice and the second start blacks the picture out.
        if (firstResume) {
            firstResume = false
            return
        }
        val surface = surfaceView.holder.surface
        if (surface != null && surface.isValid) {
            bridge.resumeCastingIfStillPaused(surface, surfaceView.width, surfaceView.height)
        }
    }

    override fun onPause() {
        IcocaProjectionHost.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        IcocaProjectionHost.setForeground(false)
        val appContext = applicationContext
        Thread { runCatching { IcocaEngine.stop(appContext) } }.apply { isDaemon = true }.start()
        super.onDestroy()
    }
}
