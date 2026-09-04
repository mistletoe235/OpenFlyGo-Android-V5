package edu.playground.djivln.logging

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Append-only diagnostic recorder. File IO is serialized off the UI thread. */
class FlightEventRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flight-event-recorder").apply { isDaemon = true }
    }
    private val logDirectory = File(appContext.filesDir, "flight_logs")
    private val activeFile = File(logDirectory, "current.jsonl")
    private val writeLock = Any()

    fun record(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (executor.isShutdown) return
        val wallTime = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        runCatching { executor.execute {
            runCatching { append(type, fields, wallTime, elapsed) }
        } }
    }

    fun recordDiagnostic(
        level: String,
        source: String,
        message: String,
        error: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        record(
            type = when (level) {
                "ERROR" -> "app_error"
                "WARN" -> "app_warning"
                else -> "app_info"
            },
            fields = diagnosticFields(level, source, message, error, fields),
        )
    }

    fun recordUncaughtException(thread: Thread, error: Throwable) {
        val fields = diagnosticFields(
            level = "ERROR",
            source = "UncaughtExceptionHandler",
            message = "Uncaught exception on ${thread.name}",
            error = error,
            fields = mapOf(
                "thread_name" to thread.name,
                "thread_id" to thread.id,
            ),
        )
        runCatching {
            append(
                type = "app_crash",
                fields = fields,
                wallTime = System.currentTimeMillis(),
                elapsed = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun exportToDownloads(callback: (Result<String>) -> Unit) {
        executor.execute {
            val result = runCatching {
                logDirectory.mkdirs()
                if (!activeFile.exists()) activeFile.writeText("")
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val displayName = "DJI-VLN-flight-$stamp.jsonl"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportWithMediaStore(displayName)
                } else {
                    val directory = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DJI-VLN")
                    directory.mkdirs()
                    val output = File(directory, displayName)
                    output.outputStream().buffered().use(::writeAllLogs)
                    output.absolutePath
                }
            }
            android.os.Handler(appContext.mainLooper).post { callback(result) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportWithMediaStore(displayName: String): String {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DJI-VLN")
                }
                val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error(appContext.getString(edu.playground.djivln.R.string.download_file_create_failed))
                try {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        output.buffered().use(::writeAllLogs)
                    } ?: error(appContext.getString(edu.playground.djivln.R.string.download_file_write_failed))
                } catch (error: Throwable) {
                    appContext.contentResolver.delete(uri, null, null)
                    throw error
                }
        return appContext.getString(edu.playground.djivln.R.string.downloads_log_path, displayName)
    }

    fun close() {
        executor.shutdown()
    }

    private fun rotateIfNeeded() {
        if (!activeFile.exists() || activeFile.length() < MAX_ACTIVE_BYTES) return
        val archived = File(logDirectory, "previous.jsonl")
        if (archived.exists()) archived.delete()
        activeFile.renameTo(archived)
    }

    private fun append(
        type: String,
        fields: Map<String, Any?>,
        wallTime: Long,
        elapsed: Long,
    ) = synchronized(writeLock) {
        logDirectory.mkdirs()
        rotateIfNeeded()
        val json = JSONObject()
            .put("time_ms", wallTime)
            .put("elapsed_ms", elapsed)
            .put("type", type)
        DiagnosticLogSanitizer.sanitizeFields(fields).forEach { (key, value) ->
            json.put(key, value ?: JSONObject.NULL)
        }
        activeFile.appendText(json.toString() + "\n", Charsets.UTF_8)
    }

    private fun diagnosticFields(
        level: String,
        source: String,
        message: String,
        error: Throwable?,
        fields: Map<String, Any?>,
    ): Map<String, Any?> = buildMap {
        put("level", level)
        put("source", source)
        put("message", message)
        putAll(fields)
        if (error != null) {
            put("throwable_class", error.javaClass.name)
            put("throwable_message", error.message)
            put("stack_trace", StringWriter().also { writer ->
                error.printStackTrace(PrintWriter(writer))
            }.toString())
        }
    }

    private fun writeAllLogs(output: java.io.OutputStream) {
        listOf(File(logDirectory, "previous.jsonl"), activeFile).forEach { file ->
            if (file.exists()) file.inputStream().buffered().use { it.copyTo(output) }
        }
    }

    private companion object {
        const val MAX_ACTIVE_BYTES = 5L * 1024L * 1024L
    }
}
