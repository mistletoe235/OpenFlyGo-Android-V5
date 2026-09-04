package edu.playground.djivln.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class PublicArtifactStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "public-artifact-writer").apply { isDaemon = true }
    }

    fun save(
        directory: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        callback: (Result<String>) -> Unit,
    ) {
        val copy = bytes.copyOf()
        writer.execute {
            val result = runCatching { write(directory, displayName, mimeType, copy) }
            android.os.Handler(appContext.mainLooper).post { callback(result) }
        }
    }

    fun saveFile(
        directory: String,
        displayName: String,
        mimeType: String,
        file: File,
        callback: (Result<String>) -> Unit,
    ) {
        writer.execute {
            val result = runCatching { file.inputStream().use { write(directory, displayName, mimeType, it) } }
            android.os.Handler(appContext.mainLooper).post { callback(result) }
        }
    }

    private fun write(directory: String, displayName: String, mimeType: String, bytes: ByteArray): String {
        return bytes.inputStream().use { write(directory, displayName, mimeType, it) }
    }

    private fun write(directory: String, displayName: String, mimeType: String, input: InputStream): String {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/DJI-VLN/$directory"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { appContext.getString(edu.playground.djivln.R.string.artifact_create_failed, displayName) }
            try {
                appContext.contentResolver.openOutputStream(uri, "w")?.use { input.copyTo(it) }
                    ?: error(appContext.getString(edu.playground.djivln.R.string.artifact_write_failed, displayName))
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (error: Throwable) {
                appContext.contentResolver.delete(uri, null, null)
                throw error
            }
            return appContext.getString(
                edu.playground.djivln.R.string.downloads_artifact_path,
                directory,
                displayName,
            )
        }
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val output = File(root, "DJI-VLN/$directory/$displayName")
        require(output.parentFile?.mkdirs() != false) {
            appContext.getString(edu.playground.djivln.R.string.artifact_export_directory_create_failed)
        }
        FileOutputStream(output).use { input.copyTo(it) }
        return output.absolutePath
    }

    override fun close() {
        writer.shutdown()
    }
}
