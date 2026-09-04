package edu.playground.djivln.camera

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileListStateListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import edu.playground.djivln.storage.PublicArtifactStore
import edu.playground.djivln.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MediaGalleryOverlay(
    private val activity: Activity,
    private val cameraIndex: () -> ComponentIndexType,
    private val availableStorage: () -> Set<CameraStorageLocation>,
    private val preferredStorage: () -> CameraStorageLocation,
    private val canOpen: () -> String?,
    private val onClosed: () -> Unit,
    private val log: (String) -> Unit
) {
    val view: FrameLayout

    private val mediaManager = MediaDataCenter.getInstance().mediaManager
    private val statusView: TextView
    private val listContainer: GridLayout
    private val previewView: ImageView
    private var storageLocation = CameraStorageLocation.SDCARD
    private var requestId = 0L
    private var enabled = false
    private var destroyed = false
    private var lastErrorMessage: String? = null
    private val artifactStore = PublicArtifactStore(activity)
    private var activeDownload: MediaFile? = null
    private var downloadOutput: FileOutputStream? = null
    private var downloadFile: File? = null
    private var latestFiles: List<MediaFile> = emptyList()
    private var mediaFilter = MediaFilter.ALL

    private val stateListener = MediaFileListStateListener { state ->
        activity.runOnUiThread {
            if (destroyed || view.visibility != View.VISIBLE) return@runOnUiThread
            statusView.text = when (state) {
                MediaFileListState.UPDATING -> activity.getString(R.string.media_reading)
                MediaFileListState.UP_TO_DATE -> lastErrorMessage ?: activity.getString(R.string.media_list_updated)
                MediaFileListState.IDLE -> activity.getString(R.string.gallery_idle)
                else -> activity.getString(R.string.media_state, state.name)
            }
            if (state == MediaFileListState.UP_TO_DATE && lastErrorMessage == null) {
                renderFiles(mediaManager.mediaFileListData?.data.orEmpty())
            }
        }
    }

    init {
        statusView = textView(activity.getString(R.string.gallery_waiting_open), 12f, 0xFFD2DAE4.toInt())
        previewView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF0D1117.toInt())
            contentDescription = activity.getString(R.string.media_preview)
        }
        listContainer = GridLayout(activity).apply {
            columnCount = 5
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(0xFF171C22.toInt())
            addView(button(activity.getString(R.string.action_back)) { hide() }, LinearLayout.LayoutParams(dp(78), dp(38)))
            addView(textView(activity.getString(R.string.aircraft_sd_media), 18f, Color.WHITE).apply {
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button(activity.getString(R.string.action_refresh)) { refresh() }, LinearLayout.LayoutParams(dp(72), dp(38)).apply { leftMargin = dp(6) })
        }

        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(0xFF11171E.toInt())
            addView(button(activity.getString(R.string.media_all)) { mediaFilter = MediaFilter.ALL; renderFiles(latestFiles) }, LinearLayout.LayoutParams(dp(76), dp(30)))
            addView(button(activity.getString(R.string.media_photos)) { mediaFilter = MediaFilter.PHOTO; renderFiles(latestFiles) }, LinearLayout.LayoutParams(dp(76), dp(30)).apply { leftMargin = dp(6) })
            addView(button(activity.getString(R.string.media_videos)) { mediaFilter = MediaFilter.VIDEO; renderFiles(latestFiles) }, LinearLayout.LayoutParams(dp(76), dp(30)).apply { leftMargin = dp(6) })
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button(activity.getString(R.string.storage_sd_card)) { selectStorage(CameraStorageLocation.SDCARD) }, LinearLayout.LayoutParams(dp(72), dp(30)))
            addView(button(activity.getString(R.string.storage_internal_short)) { selectStorage(CameraStorageLocation.INTERNAL) }, LinearLayout.LayoutParams(dp(72), dp(30)).apply { leftMargin = dp(6) })
        }

        val content = FrameLayout(activity).apply {
            addView(ScrollView(activity).apply { addView(listContainer) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(6); bottomMargin = dp(12)
            })
            previewView.visibility = View.GONE
            previewView.setOnClickListener { previewView.visibility = View.GONE }
        }

        view = FrameLayout(activity).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF0B0E12.toInt())
            addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP))
            addView(filters, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(36), Gravity.TOP).apply {
                topMargin = dp(52)
            })
            addView(statusView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(34), Gravity.TOP).apply {
                topMargin = dp(88)
                leftMargin = dp(16)
                rightMargin = dp(16)
            })
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(122)
            })
        }
    }

    fun show() {
        if (destroyed) return
        val blockedReason = canOpen()
        if (blockedReason != null) {
            view.visibility = View.VISIBLE
            view.bringToFront()
            statusView.text = blockedReason
            listContainer.removeAllViews()
            listContainer.addView(textView(blockedReason, 14f, 0xFFFFB4A9.toInt()))
            return
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        val preferred = preferredStorage()
        if (preferred != CameraStorageLocation.UNKNOWN &&
            (availableStorage().isEmpty() || preferred in availableStorage())) {
            storageLocation = preferred
        }
        refresh()
    }

    fun hide() {
        if (view.visibility != View.VISIBLE) return
        requestId += 1
        view.visibility = View.GONE
        previewView.setImageDrawable(null)
        runCatching { mediaManager.stopPullMediaFileListFromCamera() }
        if (enabled) {
            enabled = false
            runCatching { mediaManager.disable(simpleCompletion(activity.getString(R.string.gallery_closed))) }
        }
        onClosed()
    }

    fun isVisible(): Boolean = view.visibility == View.VISIBLE

    fun destroy() {
        destroyed = true
        requestId += 1
        runCatching { mediaManager.stopPullMediaFileListFromCamera() }
        runCatching { mediaManager.removeMediaFileListStateListener(stateListener) }
        if (enabled) runCatching { mediaManager.disable(simpleCompletion("")) }
        enabled = false
        previewView.setImageDrawable(null)
        activeDownload?.stopPullOriginalMediaFileFromCamera(null)
        runCatching { downloadOutput?.close() }
        downloadFile?.delete()
        artifactStore.close()
    }

    private fun refresh() {
        if (destroyed) return
        val blockedReason = canOpen()
        if (blockedReason != null) {
            statusView.text = blockedReason
            return
        }
        val index = cameraIndex()
        if (index == ComponentIndexType.UNKNOWN) {
            statusView.text = activity.getString(R.string.no_available_camera)
            return
        }
        requestId += 1
        val id = requestId
        lastErrorMessage = null
        statusView.text = activity.getString(R.string.gallery_opening, index.name, storageName())
        listContainer.removeAllViews()
        previewView.setImageDrawable(null)

        try {
            mediaManager.removeMediaFileListStateListener(stateListener)
            mediaManager.addMediaFileListStateListener(stateListener)
            // Match DJI's official sample: component and storage are applied as two source updates.
            mediaManager.setMediaFileDataSource(
                MediaFileListDataSource.Builder().setIndexType(index).build()
            )
            mediaManager.setMediaFileDataSource(
                MediaFileListDataSource.Builder().setLocation(storageLocation).build()
            )
            if (enabled) {
                pullList(id)
            } else {
                mediaManager.enable(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = activity.runOnUiThread {
                        enabled = true
                        if (destroyed || requestId != id || view.visibility != View.VISIBLE) {
                            enabled = false
                            runCatching { mediaManager.disable(simpleCompletion("")) }
                            return@runOnUiThread
                        }
                        pullList(id)
                    }

                    override fun onFailure(error: IDJIError) = showError(id, activity.getString(R.string.gallery_init_failed, error.description()))
                })
            }
        } catch (error: Throwable) {
            showError(id, activity.getString(R.string.gallery_init_failed, error.safeMessage()))
        }
    }

    private fun selectStorage(location: CameraStorageLocation) {
        val supported = availableStorage()
        if (supported.isNotEmpty() && location !in supported) {
            statusView.text = if (location == CameraStorageLocation.SDCARD) {
                activity.getString(R.string.sd_card_unavailable)
            } else {
                activity.getString(R.string.internal_storage_unsupported)
            }
            return
        }
        storageLocation = location
        refresh()
    }

    private fun pullList(id: Long) {
        try {
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .build()
            mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = activity.runOnUiThread {
                    if (destroyed || requestId != id) return@runOnUiThread
                    val files = mediaManager.mediaFileListData?.data.orEmpty()
                    renderFiles(files)
                }

                override fun onFailure(error: IDJIError) = showError(id, activity.getString(R.string.media_read_failed, error.description()))
            })
        } catch (error: Throwable) {
            showError(id, activity.getString(R.string.media_read_failed, error.safeMessage()))
        }
    }

    private fun renderFiles(files: List<MediaFile>) {
        latestFiles = files
        val visibleFiles = files.filter { file ->
            val video = file.fileType?.name?.contains("VIDEO", ignoreCase = true) == true
            when (mediaFilter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTO -> !video
                MediaFilter.VIDEO -> video
            }
        }
        statusView.text = activity.getString(R.string.media_file_count, storageName(), visibleFiles.size, files.size)
        listContainer.removeAllViews()
        if (visibleFiles.isEmpty()) {
            listContainer.addView(textView(activity.getString(R.string.no_media_files), 14f, 0xFFD2DAE4.toInt()))
            return
        }
        visibleFiles.forEach { file ->
            val thumbnail = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF0D1117.toInt())
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFF171C22.toInt())
                    setStroke(dp(1), 0xFF30363D.toInt())
                }
                addView(thumbnail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(82)))
                addView(textView(file.fileName ?: activity.getString(R.string.unnamed_media), 10f, Color.WHITE).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                })
                addView(textView(file.summary(), 8f, 0xFF9DA7B3.toInt()).apply { maxLines = 1 })
                setOnClickListener { loadPreview(file) }
                setOnLongClickListener { downloadOriginal(file); true }
            }
            listContainer.addView(row, GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
            runCatching {
                file.pullPreviewFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<android.graphics.Bitmap> {
                    override fun onSuccess(bitmap: android.graphics.Bitmap) = activity.runOnUiThread { thumbnail.setImageBitmap(bitmap) }
                    override fun onFailure(error: IDJIError) = Unit
                })
            }
        }
    }

    private fun loadPreview(file: MediaFile) {
        requestId += 1
        val id = requestId
        statusView.text = activity.getString(R.string.media_preview_loading,
            file.fileName ?: activity.getString(R.string.media_generic))
        previewView.setImageDrawable(null)
        try {
            file.pullPreviewFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<android.graphics.Bitmap> {
                override fun onSuccess(bitmap: android.graphics.Bitmap) = activity.runOnUiThread {
                    if (destroyed || requestId != id || view.visibility != View.VISIBLE) return@runOnUiThread
                    previewView.setImageBitmap(bitmap)
                    previewView.visibility = View.VISIBLE
                    statusView.text = activity.getString(R.string.media_preview_name,
                        file.fileName ?: activity.getString(R.string.media_generic))
                }

                override fun onFailure(error: IDJIError) = showError(id, activity.getString(R.string.media_preview_failed, error.description()))
            })
        } catch (error: Throwable) {
            showError(id, activity.getString(R.string.media_preview_failed, error.safeMessage()))
        }
    }

    private fun downloadOriginal(media: MediaFile) {
        if (activeDownload != null) return showError(requestId, activity.getString(R.string.media_download_active))
        val name = media.fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf(String::isNotBlank) ?: "DJI_${System.currentTimeMillis()}"
        val temporary = File(activity.cacheDir, "media-download/$name")
        temporary.parentFile?.mkdirs()
        activeDownload = media
        downloadFile = temporary
        downloadOutput = FileOutputStream(temporary)
        statusView.text = activity.getString(R.string.media_download_started, name)
        media.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            override fun onStart() = Unit
            override fun onProgress(total: Long, current: Long) = activity.runOnUiThread {
                statusView.text = activity.getString(R.string.media_download_progress,
                    name, if (total > 0) current * 100 / total else 0)
            }
            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                runCatching { downloadOutput?.write(data) }.onFailure(::finishDownload)
            }
            override fun onFinish() = finishDownload(null)
            override fun onFailure(error: IDJIError) = finishDownload(IllegalStateException(error.toString()))
        })
    }

    @Synchronized
    private fun finishDownload(error: Throwable?) {
        val file = downloadFile
        runCatching { downloadOutput?.close() }
        downloadOutput = null
        downloadFile = null
        activeDownload = null
        if (error != null || file == null) {
            file?.delete()
            activity.runOnUiThread { showError(requestId, activity.getString(R.string.media_download_failed,
                error?.safeMessage() ?: activity.getString(R.string.file_invalid))) }
            return
        }
        artifactStore.saveFile("media", file.name, "application/octet-stream", file) { result ->
            file.delete()
            result.onSuccess { path -> statusView.text = activity.getString(R.string.media_saved, path); log("media exported: $path") }
                .onFailure { showError(requestId, activity.getString(R.string.media_export_failed, it.safeMessage())) }
        }
    }

    private fun showError(id: Long, message: String) = activity.runOnUiThread {
        if (destroyed || requestId != id) return@runOnUiThread
        val displayMessage = if (storageLocation == CameraStorageLocation.SDCARD) {
            activity.getString(R.string.sd_media_error_with_detail, message)
        } else {
            message
        }
        lastErrorMessage = displayMessage
        statusView.text = displayMessage
        if (listContainer.childCount == 0) {
            listContainer.addView(textView(displayMessage, 14f, 0xFFFFB4A9.toInt()))
        }
        log(displayMessage)
    }

    private fun simpleCompletion(message: String) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            if (message.isNotEmpty()) log(message)
        }

        override fun onFailure(error: IDJIError) {
            log(activity.getString(R.string.gallery_close_failed, error.description()))
        }
    }

    private fun MediaFile.summary(): String {
        val size = when {
            fileSize >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", fileSize / (1024.0 * 1024.0 * 1024.0))
            fileSize >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", fileSize / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.0f KB", fileSize / 1024.0)
        }
        val durationText = duration?.takeIf { it > 0 }?.let { " · ${it}s" }.orEmpty()
        val dateText = date?.let { date ->
            " · %04d-%02d-%02d %02d:%02d".format(
                date.year ?: 0,
                date.month ?: 0,
                date.day ?: 0,
                date.hour ?: 0,
                date.minute ?: 0
            )
        }.orEmpty()
        return "${fileType?.name ?: "UNKNOWN"} · $size$durationText$dateText"
    }

    private fun storageName() = when (storageLocation) {
        CameraStorageLocation.SDCARD -> activity.getString(R.string.storage_sd_card)
        CameraStorageLocation.INTERNAL -> activity.getString(R.string.storage_internal)
        CameraStorageLocation.INTERNAL_SSD -> activity.getString(R.string.storage_internal_ssd)
        else -> activity.getString(R.string.storage_unknown)
    }

    private fun textView(text: String, size: Float, color: Int) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }

    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setPadding(dp(4), 0, dp(4), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(0xFF2B3138.toInt())
        }
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun Throwable.safeMessage() = message ?: javaClass.simpleName

    private enum class MediaFilter {
        ALL,
        PHOTO,
        VIDEO,
    }

}
