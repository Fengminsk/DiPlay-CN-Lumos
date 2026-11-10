package com.shilapi.xcertplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live CarPlay session state, readable from anywhere in the process.
 *
 * This is the first piece of moving the connection out of [CarPlayHostActivity]: the screen that
 * shows "preparing CarPlay" must not be the screen that owns the connection, otherwise the popup
 * and the session die together. Today the host still owns the controller and publishes here; the
 * next step moves ownership into the foreground service and leaves the host as a surface only.
 */
internal object CarPlayLiveSession {

    enum class Phase {
        /** Nothing started, or the driver disconnected. */
        Idle,

        /** The host took the request and is building the session. */
        Preparing,

        /** Hotspot, Bluetooth or USB handshake in progress. Text is human-readable. */
        Connecting,

        /** The iPhone is paired but no frame has arrived yet. */
        Connected,

        /** At least one video frame has been rendered: the picture is really there. */
        Streaming,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val message: String = "",
        /** True once a frame has been rendered; reset when a new link starts. */
        val firstFrame: Boolean = false,
    )

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    fun value(): State = mutable.value

    fun preparing(message: String = "Preparing CarPlay") {
        mutable.value = State(Phase.Preparing, message)
    }

    fun connecting(message: String) {
        val current = mutable.value
        // A late stage update must not pull a streaming session back to "connecting".
        if (current.phase == Phase.Streaming) return
        mutable.value = current.copy(phase = Phase.Connecting, message = message)
    }

    fun connected() {
        val current = mutable.value
        if (current.phase == Phase.Streaming) return
        mutable.value = current.copy(phase = Phase.Connected)
    }

    fun streaming() {
        if (mutable.value.phase == Phase.Streaming) return
        mutable.value = mutable.value.copy(phase = Phase.Streaming, firstFrame = true)
    }

    fun idle() {
        mutable.value = State()
    }
}
