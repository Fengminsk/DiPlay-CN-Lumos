package com.andrerinas.openheadunit.utils

import android.content.Context
import android.util.Log

/**
 * Replacement for MiAuto's AppLog, whose file-capture backend pulls in the whole LogExporter /
 * DiagnosticJournal / LogFilesHelper stack. The ICCOA engine only ever calls [d], [i], [w], [e]
 * and [init], so this keeps that surface and routes everything to logcat.
 *
 * DiPlay's own diagnostic report already collects the session log; wiring the engine's lines into
 * it is part of the settings integration, not of the engine port.
 */
object AppLog {

    private const val TAG = "MiAuto"

    @Volatile
    var enabled: Boolean = true

    fun init(settings: Settings?, context: Context? = null) {
        // No file backend here; retained so ported call sites stay unchanged.
        enabled = true
    }

    fun v(msg: String) = emit(Log.VERBOSE, msg)
    fun v(msg: String, vararg params: Any) = emit(Log.VERBOSE, format(msg, params))
    fun d(msg: String) = emit(Log.DEBUG, msg)    fun d(msg: String, vararg params: Any) = emit(Log.DEBUG, format(msg, params))
    fun i(msg: String) = emit(Log.INFO, msg)
    fun i(msg: String, vararg params: Any) = emit(Log.INFO, format(msg, params))
    fun w(msg: String) = emit(Log.WARN, msg)
    fun w(msg: String, vararg params: Any) = emit(Log.WARN, format(msg, params))
    fun e(msg: String?) = emit(Log.ERROR, msg.orEmpty())
    fun e(msg: String, tr: Throwable) = emit(Log.ERROR, msg, tr)
    fun e(tr: Throwable) = emit(Log.ERROR, tr.message.orEmpty(), tr)

    fun println(priority: Int, tag: String, msg: String) {
        if (enabled) Log.println(priority, tag, msg)
    }

    private fun emit(priority: Int, msg: String, tr: Throwable? = null) {
        if (!enabled) return
        if (tr == null) Log.println(priority, TAG, msg) else Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
    }

    /** Keeps `%s`-style call sites working without pulling MiAuto's formatter. */
    private fun format(msg: String, params: Array<out Any>): String =
        runCatching { msg.format(*params) }.getOrDefault(msg)

    val LOG_VERBOSE get() = Log.isLoggable(TAG, Log.VERBOSE)
    val LOG_DEBUG get() = Log.isLoggable(TAG, Log.DEBUG)
}
