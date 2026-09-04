package edu.playground.djivln.logging

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object AppDiagnosticLogger {
    @Volatile private var recorder: FlightEventRecorder? = null
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    fun initialize(context: Context): FlightEventRecorder {
        val initialized = recorder ?: synchronized(this) {
            recorder ?: FlightEventRecorder(context.applicationContext).also { recorder = it }
        }
        installCrashHandler(initialized)
        return initialized
    }

    fun recorder(context: Context): FlightEventRecorder = initialize(context)

    fun info(source: String, message: String, fields: Map<String, Any?> = emptyMap()) {
        Log.i(source, message)
        recorder?.recordDiagnostic("INFO", source, message, fields = fields)
    }

    fun warn(
        source: String,
        message: String,
        error: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (error == null) Log.w(source, message) else Log.w(source, message, error)
        recorder?.recordDiagnostic("WARN", source, message, error, fields)
    }

    fun error(
        source: String,
        message: String,
        error: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (error == null) Log.e(source, message) else Log.e(source, message, error)
        recorder?.recordDiagnostic("ERROR", source, message, error, fields)
    }

    private fun installCrashHandler(recorder: FlightEventRecorder) {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handlingCrash.compareAndSet(false, true)) {
                recorder.recordUncaughtException(thread, error)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
