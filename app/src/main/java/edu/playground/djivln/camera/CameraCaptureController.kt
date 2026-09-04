package edu.playground.djivln.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.PhotoFileFormat
import dji.sdk.keyvalue.value.camera.PhotoIntervalShootSettings
import dji.sdk.keyvalue.value.camera.PhotoRatio
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import edu.playground.djivln.R

/**
 * Small, defensive adapter around the camera keys used by the capture rail.
 * Every SDK entry point is guarded because camera availability can change while a callback is in flight.
 */
class CameraCaptureController(
    private val context: Context,
    private val onSnapshot: (Snapshot) -> Unit
) {
    enum class MessageSeverity { INFO, ERROR }

    data class IntervalProgress(
        val shootCount: Int?,
        val availablePhotoCount: Int?,
        val shooting: Boolean?,
        val shootingPhoto: Boolean?,
        val storing: Boolean?,
        val shootNotAllowed: Boolean?,
    )

    data class Snapshot(
        val cameraIndex: ComponentIndexType = ComponentIndexType.UNKNOWN,
        val connected: Boolean = false,
        val mode: CameraMode = CameraMode.UNKNOWN,
        val recording: Boolean = false,
        val recordingSeconds: Int = 0,
        val storageLocation: CameraStorageLocation = CameraStorageLocation.UNKNOWN,
        val supportedStorage: List<CameraStorageLocation> = emptyList(),
        val storageState: String = "UNKNOWN",
        val availablePhotoCount: Int? = null,
        val availableVideoSeconds: Int? = null,
        val shootingPhoto: Boolean = false,
        val storingPhoto: Boolean = false,
        val captureShootCount: Int? = null,
        val shootPhotoMode: String? = null,
        val photoFileFormat: String? = null,
        val photoRatioAndSize: String? = null,
        val captureMinimumInterval: Int? = null,
        val photoProcessTimeSeconds: Double? = null,
        val busy: Boolean = false,
        val message: String = "",
        val messageSeverity: MessageSeverity = MessageSeverity.INFO,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listenerOwner = Any()
    private var snapshot = Snapshot()
    private var operationId = 0L
    private var operationCompletion: ((Result<Unit>) -> Unit)? = null
    private var destroyed = false

    fun bind(cameraIndex: ComponentIndexType, force: Boolean = false) {
        if (destroyed || (!force && cameraIndex == snapshot.cameraIndex)) return
        runCatching { KeyManager.getInstance().cancelListen(listenerOwner) }
        snapshot = Snapshot(cameraIndex = cameraIndex, message = text(R.string.camera_reading_status))
        publish()
        if (cameraIndex == ComponentIndexType.UNKNOWN) return

        safeListen(CameraKey.KeyConnection, cameraIndex) { connected ->
            update(
                connected = connected == true,
                message = text(if (connected == true) R.string.camera_connected else R.string.camera_disconnected),
                messageSeverity = if (connected == true) MessageSeverity.INFO else MessageSeverity.ERROR,
            )
        }
        safeListen(CameraKey.KeyCameraMode, cameraIndex) { mode ->
            update(mode = mode ?: CameraMode.UNKNOWN)
        }
        safeListen(CameraKey.KeyIsRecording, cameraIndex) { recording ->
            update(
                recording = recording == true,
                message = if (recording == true) text(R.string.camera_recording) else snapshot.message
            )
        }
        safeListen(CameraKey.KeyRecordingTime, cameraIndex) { seconds ->
            update(recordingSeconds = seconds ?: 0)
        }
        safeListen(CameraKey.KeyIsShootingPhoto, cameraIndex) { shooting ->
            update(shootingPhoto = shooting == true)
        }
        safeListen(CameraKey.KeyCameraStoringFile, cameraIndex) { storing ->
            update(storingPhoto = storing == true)
        }
        safeListen(CameraKey.KeyCameraCaptureShootCount, cameraIndex) { count ->
            update(captureShootCount = count?.shootCount)
        }
        safeListen(CameraKey.KeyShootPhotoMode, cameraIndex) { mode ->
            update(shootPhotoMode = mode?.name)
        }
        safeListen(CameraKey.KeyPhotoFileFormat, cameraIndex) { format ->
            update(photoFileFormat = format?.name)
        }
        safeListen(CameraKey.KeyPhotoRatioAndSize, cameraIndex) { value ->
            update(photoRatioAndSize = value?.toString())
        }
        safeListen(CameraKey.KeyCaptureMinimumInterval, cameraIndex) { interval ->
            update(captureMinimumInterval = interval)
        }
        safeListen(CameraKey.KeyPhotoProcessTime, cameraIndex) { seconds ->
            update(photoProcessTimeSeconds = seconds)
        }
        safeListen(CameraKey.KeyCameraStorageInfos, cameraIndex) { infos ->
            updateStorage(infos)
        }

        readSnapshot(cameraIndex)
    }

    fun selectPhotoMode() = setMode(CameraMode.PHOTO_NORMAL)

    fun selectVideoMode() = setMode(CameraMode.VIDEO_NORMAL)

    fun showMessage(message: String) = update(message = message, messageSeverity = MessageSeverity.INFO)

    fun showError(message: String) = update(message = message, messageSeverity = MessageSeverity.ERROR)

    fun takePhoto(
        onTriggered: ((elapsedRealtimeNanos: Long, epochMillis: Long) -> Unit)? = null,
        completion: ((Result<Unit>) -> Unit)? = null,
    ) {
        if (snapshot.recording) {
            val error = IllegalStateException(text(R.string.camera_stop_recording_first))
            fail(error.message.orEmpty())
            completion?.invoke(Result.failure(error))
            return
        }
        if (snapshot.mode.isPhotoMode) {
            triggerPhotoWhenReady(onTriggered, completion)
        } else {
            setMode(CameraMode.PHOTO_NORMAL) { triggerPhotoWhenReady(onTriggered, completion) }
        }
    }

    fun takePhotoDirectForCadenceTest(completion: (Result<Unit>) -> Unit) {
        val index = snapshot.cameraIndex
        val unavailable = when {
            destroyed -> text(R.string.camera_controller_closed)
            index == ComponentIndexType.UNKNOWN || !snapshot.connected -> text(R.string.camera_disconnected)
            snapshot.recording -> text(R.string.camera_stop_recording_first)
            !snapshot.mode.isPhotoMode -> text(R.string.camera_not_photo_mode)
            snapshot.storageState != "INSERTED" -> text(R.string.camera_storage_unavailable, snapshot.storageState)
            else -> null
        }
        if (unavailable != null) {
            completion(Result.failure(IllegalStateException(unavailable)))
            return
        }
        performCadenceAction(CameraKey.KeyStartShootPhoto, index, completion)
    }

    fun toggleRecording() {
        when {
            snapshot.recording -> triggerCapture()
            snapshot.mode.isVideoMode -> triggerCapture()
            else -> setMode(CameraMode.VIDEO_NORMAL) { triggerCapture() }
        }
    }

    fun triggerCapture(
        completion: ((Result<Unit>) -> Unit)? = null,
        onPhotoTriggered: ((elapsedRealtimeNanos: Long, epochMillis: Long) -> Unit)? = null,
    ) {
        val unavailable = actionUnavailableReason()
        if (unavailable != null) {
            fail(unavailable)
            completion?.invoke(Result.failure(IllegalStateException(unavailable)))
            return
        }
        if (snapshot.storageState != "INSERTED") {
            val message = when (snapshot.storageState) {
                "NOT_INSERTED" -> text(R.string.camera_sd_not_inserted_capture)
                else -> text(R.string.camera_storage_unavailable, snapshot.storageState)
            }
            fail(message)
            completion?.invoke(Result.failure(IllegalStateException(message)))
            return
        }
        if (snapshot.mode.isPhotoMode && snapshot.availablePhotoCount == 0) {
            val message = text(R.string.camera_no_photo_capacity)
            fail(message)
            completion?.invoke(Result.failure(IllegalStateException(message)))
            return
        }
        if (snapshot.mode.isVideoMode && snapshot.availableVideoSeconds == 0) {
            val message = text(R.string.camera_no_video_capacity)
            fail(message)
            completion?.invoke(Result.failure(IllegalStateException(message)))
            return
        }
        when {
            snapshot.recording -> perform(
                CameraKey.KeyStopRecord,
                text(R.string.camera_stopping_recording),
                text(R.string.camera_recording_saved),
                completion,
            )
            snapshot.mode.isVideoMode -> perform(
                CameraKey.KeyStartRecord,
                text(R.string.camera_starting_recording),
                text(R.string.camera_recording_started),
                completion,
            )
            snapshot.mode.isPhotoMode -> perform(
                CameraKey.KeyStartShootPhoto,
                text(R.string.camera_taking_photo),
                text(R.string.camera_photo_captured),
                completion,
                onIssued = {
                    onPhotoTriggered?.invoke(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
                },
            )
            else -> {
                val message = text(R.string.camera_select_mode_first)
                fail(message)
                completion?.invoke(Result.failure(IllegalStateException(message)))
            }
        }
    }

    fun currentSnapshot(): Snapshot = snapshot

    fun cadenceSettingsSummary(): String {
        val index = snapshot.cameraIndex
        if (index == ComponentIndexType.UNKNOWN) return "camera=UNKNOWN"
        val manager = KeyManager.getInstance()
        fun <T> read(key: dji.sdk.keyvalue.key.DJIKeyInfo<T>): T? =
            runCatching { manager.getValue(KeyTools.createKey(key, index)) }.getOrNull()
        return listOf(
            "storage=${snapshot.storageLocation}",
            "storage_supported=${snapshot.supportedStorage.joinToString(",")}",
            "storage_state=${snapshot.storageState}",
            "mode=${read(CameraKey.KeyCameraMode)}",
            "shoot=${read(CameraKey.KeyShootPhotoMode)}",
            "format=${read(CameraKey.KeyPhotoFileFormat)}",
            "ratio=${read(CameraKey.KeyPhotoRatio)}",
            "size=${read(CameraKey.KeyPhotoSize)}",
            "size_range=${read(CameraKey.KeyPhotoSizeRange)}",
            "size_settable=${read(CameraKey.KeyPhotoSizeSettable)}",
            "ratio_range=${read(CameraKey.KeyPhotoRatioRange)}",
            "resolution=${read(CameraKey.KeyPhotoResolution)}",
            "minimum_interval=${read(CameraKey.KeyCaptureMinimumInterval)}",
            "interval_range=${read(CameraKey.KeyIntervalModeParamRange)}",
            "storing=${read(CameraKey.KeyCameraStoringFile)}",
            "not_allowed=${read(CameraKey.KeyShootPhotoNotAllowed)}",
            "process_s=${read(CameraKey.KeyPhotoProcessTime)}",
            "exposure=${read(CameraKey.KeyExposureMode)}",
            "shutter=${read(CameraKey.KeyShutterSpeed)}",
            "iso=${read(CameraKey.KeyISO)}",
            "ratio_resolution_range=${read(CameraKey.KeyPhotoSizeRatioAndResolutionRange)}",
        ).joinToString(" ")
    }

    fun prepareCadencePhotoMode(lowResolution: Boolean, completion: (Result<Unit>) -> Unit) {
        val index = snapshot.cameraIndex
        if (index == ComponentIndexType.UNKNOWN || !snapshot.connected) {
            completion(Result.failure(IllegalStateException(text(R.string.camera_disconnected))))
            return
        }
        val steps = buildList<((Result<Unit>) -> Unit) -> Unit> {
            if (snapshot.mode != CameraMode.PHOTO_NORMAL) {
                add { next -> setCadenceValue(CameraKey.KeyCameraMode, CameraMode.PHOTO_NORMAL, index, next) }
            }
            val currentFormat = runCatching {
                KeyManager.getInstance().getValue(KeyTools.createKey(CameraKey.KeyPhotoFileFormat, index))
            }.getOrNull()
            if (currentFormat != PhotoFileFormat.JPEG) {
                add { next -> setCadenceValue(CameraKey.KeyPhotoFileFormat, PhotoFileFormat.JPEG, index, next) }
            }
            val requestedRatio = if (lowResolution) {
                PhotoRatio.RATIO_4COLON3
            } else {
                PhotoRatio.RATIO_16COLON9
            }
            val currentRatio = runCatching {
                KeyManager.getInstance().getValue(KeyTools.createKey(CameraKey.KeyPhotoRatio, index))
            }.getOrNull()
            if (currentRatio != requestedRatio) {
                add { next -> setCadenceValue(CameraKey.KeyPhotoRatio, requestedRatio, index, next) }
            }
        }
        fun runStep(position: Int) {
            if (position >= steps.size) {
                handler.postDelayed({ completion(Result.success(Unit)) }, CADENCE_SETTINGS_SETTLE_MILLIS)
                return
            }
            steps[position] { result ->
                result.onSuccess { runStep(position + 1) }.onFailure { completion(Result.failure(it)) }
            }
        }
        runStep(0)
    }

    fun startIntervalCapture(
        periodMillis: Long,
        count: Int,
        completion: (Result<Unit>) -> Unit,
    ) {
        val index = snapshot.cameraIndex
        if (index == ComponentIndexType.UNKNOWN || !snapshot.connected) {
            completion(Result.failure(IllegalStateException(text(R.string.camera_disconnected))))
            return
        }
        val settings = PhotoIntervalShootSettings(count, periodMillis / 1_000.0)
        setCadenceValue(CameraKey.KeyChangeToIntervalShootModeWithSettings, settings, index) { atomicResult ->
            if (atomicResult.isSuccess) {
                startIntervalAction(index, completion)
                return@setCadenceValue
            }
            setCadenceValue(CameraKey.KeyCameraMode, CameraMode.PHOTO_INTERVAL, index) { modeResult ->
                if (modeResult.isFailure) {
                    completion(Result.failure(modeResult.exceptionOrNull() ?: IllegalStateException(text(R.string.camera_switch_interval_failed))))
                    return@setCadenceValue
                }
                setCadenceValue(CameraKey.KeyPhotoIntervalShootSettings, settings, index) { settingsResult ->
                    if (settingsResult.isFailure) {
                        completion(Result.failure(settingsResult.exceptionOrNull() ?: IllegalStateException(text(R.string.camera_interval_settings_failed))))
                    } else {
                        startIntervalAction(index, completion)
                    }
                }
            }
        }
    }

    fun stopIntervalCapture(completion: (Result<Unit>) -> Unit = {}) {
        val index = snapshot.cameraIndex
        if (index == ComponentIndexType.UNKNOWN) {
            completion(Result.failure(IllegalStateException(text(R.string.camera_disconnected))))
            return
        }
        performCadenceAction(CameraKey.KeyStopShootPhoto, index, completion)
    }

    fun intervalProgress(): IntervalProgress {
        val index = snapshot.cameraIndex
        if (index == ComponentIndexType.UNKNOWN) return IntervalProgress(null, null, null, null, null, null)
        val manager = KeyManager.getInstance()
        fun <T> read(key: dji.sdk.keyvalue.key.DJIKeyInfo<T>): T? =
            runCatching { manager.getValue(KeyTools.createKey(key, index)) }.getOrNull()
        return IntervalProgress(
            shootCount = read(CameraKey.KeyCameraCaptureShootCount)?.shootCount,
            availablePhotoCount = read(CameraKey.KeyCameraStorageInfos)?.currentCameraStorageInfo?.availablePhotoCount,
            shooting = read(CameraKey.KeyIsShootingIntervalPhotos),
            shootingPhoto = read(CameraKey.KeyIsShootingPhoto),
            storing = read(CameraKey.KeyCameraStoringFile),
            shootNotAllowed = read(CameraKey.KeyShootPhotoNotAllowed),
        )
    }

    fun destroy() {
        destroyed = true
        operationCompletion?.invoke(Result.failure(IllegalStateException(text(R.string.camera_controller_closed))))
        operationCompletion = null
        operationId += 1
        handler.removeCallbacksAndMessages(null)
        runCatching { KeyManager.getInstance().cancelListen(listenerOwner) }
    }

    private fun setMode(mode: CameraMode, afterSuccess: () -> Unit = {}) {
        if (!readyForAction()) return
        if (snapshot.recording) {
            fail(text(R.string.camera_stop_recording_first))
            return
        }
        val index = snapshot.cameraIndex
        begin(text(R.string.camera_switching_mode, text(if (mode.isPhotoMode) R.string.camera_mode_photo else R.string.camera_mode_video)))
        val id = operationId
        runCatching {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(CameraKey.KeyCameraMode, index),
                mode,
                completion(id) {
                    update(
                        mode = mode,
                        busy = false,
                        message = text(if (mode.isPhotoMode) R.string.camera_photo_mode else R.string.camera_video_mode),
                    )
                    afterSuccess()
                }
            )
        }.onFailure { fail(text(R.string.camera_mode_switch_failed, it.safeMessage())) }
    }

    private fun perform(
        key: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        pendingMessage: String,
        successMessage: String,
        completion: ((Result<Unit>) -> Unit)? = null,
        onIssued: (() -> Unit)? = null,
    ) {
        val index = snapshot.cameraIndex
        val id = begin(pendingMessage, completion)
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(key, index),
                actionCompletion(id) {
                    completeOperation(id, Result.success(Unit), successMessage)
                }
            )
            onIssued?.invoke()
        }.onFailure {
            completeOperation(
                id,
                Result.failure(it),
                text(R.string.camera_operation_failed, it.safeMessage()),
            )
        }
    }

    private fun readyForAction(): Boolean {
        val unavailable = actionUnavailableReason() ?: return true
        if (snapshot.busy) update(message = unavailable) else fail(unavailable)
        return false
    }

    private fun triggerPhotoWhenReady(
        onTriggered: ((elapsedRealtimeNanos: Long, epochMillis: Long) -> Unit)?,
        completion: ((Result<Unit>) -> Unit)?,
        startedAtMillis: Long = SystemClock.uptimeMillis(),
        readySinceMillis: Long? = null,
    ) {
        if (destroyed) {
            completion?.invoke(Result.failure(IllegalStateException(text(R.string.camera_controller_closed))))
            return
        }
        val progress = intervalProgress()
        val ready = progress.storing != true && progress.shootNotAllowed != true && progress.shootingPhoto != true
        val now = SystemClock.uptimeMillis()
        val stableSince = if (ready) readySinceMillis ?: now else null
        if (ready && stableSince != null && now - stableSince >= PHOTO_READY_STABLE_MILLIS) {
            triggerCapture(completion, onTriggered)
            return
        }
        if (now - startedAtMillis >= PHOTO_READY_TIMEOUT_MILLIS) {
            val message = text(
                R.string.camera_ready_timeout,
                progress.storing,
                progress.shootNotAllowed,
                progress.shootingPhoto,
            )
            fail(message)
            completion?.invoke(Result.failure(IllegalStateException(message)))
            return
        }
        handler.postDelayed(
            { triggerPhotoWhenReady(onTriggered, completion, startedAtMillis, stableSince) },
            PHOTO_READY_POLL_MILLIS,
        )
    }

    private fun actionUnavailableReason(): String? = when {
        destroyed -> text(R.string.camera_controller_closed)
        snapshot.busy -> text(R.string.camera_busy)
        snapshot.cameraIndex == ComponentIndexType.UNKNOWN || !snapshot.connected -> text(R.string.camera_disconnected)
        else -> null
    }

    private fun begin(message: String, completion: ((Result<Unit>) -> Unit)? = null): Long {
        operationId += 1
        val id = operationId
        operationCompletion = completion
        update(busy = true, message = message, messageSeverity = MessageSeverity.INFO)
        handler.postDelayed({
            if (!destroyed && operationId == id && snapshot.busy) {
                completeOperation(
                    id,
                    Result.failure(IllegalStateException(text(R.string.camera_operation_timeout))),
                    text(R.string.camera_operation_timeout),
                )
            }
        }, OPERATION_TIMEOUT_MS)
        return id
    }

    private fun completeOperation(id: Long, result: Result<Unit>, message: String) {
        if (destroyed || operationId != id) return
        val completion = operationCompletion
        operationCompletion = null
        operationId += 1
        update(
            busy = false,
            message = message,
            messageSeverity = if (result.isFailure) MessageSeverity.ERROR else MessageSeverity.INFO,
        )
        completion?.invoke(result)
    }

    private fun readSnapshot(index: ComponentIndexType) {
        runCatching {
            val manager = KeyManager.getInstance()
            update(
                connected = manager.getValue(KeyTools.createKey(CameraKey.KeyConnection, index)) ?: false,
                mode = manager.getValue(KeyTools.createKey(CameraKey.KeyCameraMode, index)) ?: CameraMode.UNKNOWN,
                recording = manager.getValue(KeyTools.createKey(CameraKey.KeyIsRecording, index)) ?: false,
                recordingSeconds = manager.getValue(KeyTools.createKey(CameraKey.KeyRecordingTime, index)) ?: 0,
                shootingPhoto = manager.getValue(KeyTools.createKey(CameraKey.KeyIsShootingPhoto, index)) ?: false,
                storingPhoto = manager.getValue(KeyTools.createKey(CameraKey.KeyCameraStoringFile, index)) ?: false,
                captureShootCount = manager.getValue(
                    KeyTools.createKey(CameraKey.KeyCameraCaptureShootCount, index),
                )?.shootCount,
                shootPhotoMode = manager.getValue(KeyTools.createKey(CameraKey.KeyShootPhotoMode, index))?.name,
                photoFileFormat = manager.getValue(KeyTools.createKey(CameraKey.KeyPhotoFileFormat, index))?.name,
                photoRatioAndSize = manager.getValue(
                    KeyTools.createKey(CameraKey.KeyPhotoRatioAndSize, index),
                )?.toString(),
                captureMinimumInterval = manager.getValue(
                    KeyTools.createKey(CameraKey.KeyCaptureMinimumInterval, index),
                ),
                photoProcessTimeSeconds = manager.getValue(
                    KeyTools.createKey(CameraKey.KeyPhotoProcessTime, index),
                ),
            )
            updateStorage(manager.getValue(KeyTools.createKey(CameraKey.KeyCameraStorageInfos, index)))
        }.onFailure { fail(text(R.string.camera_state_read_failed, it.safeMessage())) }
    }

    private fun <T> safeListen(
        key: dji.sdk.keyvalue.key.DJIKeyInfo<T>,
        index: ComponentIndexType,
        callback: (T?) -> Unit
    ) {
        runCatching {
            KeyManager.getInstance().listen(KeyTools.createKey(key, index), listenerOwner) { _, value ->
                handler.post {
                    if (!destroyed && snapshot.cameraIndex == index) callback(value)
                }
            }
        }.onFailure { fail(text(R.string.camera_state_listen_failed, it.safeMessage())) }
    }

    private fun completion(id: Long, success: () -> Unit) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            handler.post {
                if (!destroyed && operationId == id) success()
            }
        }

        override fun onFailure(error: IDJIError) {
            handler.post {
                if (!destroyed && operationId == id) fail(text(R.string.camera_operation_failed, error.safeDescription()))
            }
        }
    }

    private fun actionCompletion(id: Long, success: () -> Unit) =
        object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(value: EmptyMsg) {
                handler.post {
                    if (!destroyed && operationId == id) success()
                }
            }

            override fun onFailure(error: IDJIError) {
                handler.post {
                    if (!destroyed && operationId == id) {
                        completeOperation(
                            id,
                            Result.failure(IllegalStateException(error.safeDescription())),
                            text(R.string.camera_operation_failed, error.safeDescription()),
                        )
                    }
                }
            }
        }

    private fun IDJIError.safeDescription(): String {
        val details = listOf(
            description()?.takeIf(String::isNotBlank),
            hint()?.takeIf(String::isNotBlank),
            errorCode()?.takeIf(String::isNotBlank)?.let { "code=$it" },
            innerCode()?.takeIf(String::isNotBlank)?.let { "inner=$it" },
        ).filterNotNull().distinct()
        return details.joinToString(" · ").ifBlank { errorType().toString() }
    }

    private fun fail(message: String) {
        operationCompletion?.invoke(Result.failure(IllegalStateException(message)))
        operationCompletion = null
        operationId += 1
        update(busy = false, message = message, messageSeverity = MessageSeverity.ERROR)
    }

    private fun update(
        connected: Boolean = snapshot.connected,
        mode: CameraMode = snapshot.mode,
        recording: Boolean = snapshot.recording,
        recordingSeconds: Int = snapshot.recordingSeconds,
        storageLocation: CameraStorageLocation = snapshot.storageLocation,
        supportedStorage: List<CameraStorageLocation> = snapshot.supportedStorage,
        storageState: String = snapshot.storageState,
        availablePhotoCount: Int? = snapshot.availablePhotoCount,
        availableVideoSeconds: Int? = snapshot.availableVideoSeconds,
        shootingPhoto: Boolean = snapshot.shootingPhoto,
        storingPhoto: Boolean = snapshot.storingPhoto,
        captureShootCount: Int? = snapshot.captureShootCount,
        shootPhotoMode: String? = snapshot.shootPhotoMode,
        photoFileFormat: String? = snapshot.photoFileFormat,
        photoRatioAndSize: String? = snapshot.photoRatioAndSize,
        captureMinimumInterval: Int? = snapshot.captureMinimumInterval,
        photoProcessTimeSeconds: Double? = snapshot.photoProcessTimeSeconds,
        busy: Boolean = snapshot.busy,
        message: String = snapshot.message,
        messageSeverity: MessageSeverity = snapshot.messageSeverity,
    ) {
        if (destroyed) return
        snapshot = snapshot.copy(
            connected = connected,
            mode = mode,
            recording = recording,
            recordingSeconds = recordingSeconds,
            storageLocation = storageLocation,
            supportedStorage = supportedStorage,
            storageState = storageState,
            availablePhotoCount = availablePhotoCount,
            availableVideoSeconds = availableVideoSeconds,
            shootingPhoto = shootingPhoto,
            storingPhoto = storingPhoto,
            captureShootCount = captureShootCount,
            shootPhotoMode = shootPhotoMode,
            photoFileFormat = photoFileFormat,
            photoRatioAndSize = photoRatioAndSize,
            captureMinimumInterval = captureMinimumInterval,
            photoProcessTimeSeconds = photoProcessTimeSeconds,
            busy = busy,
            message = message,
            messageSeverity = messageSeverity,
        )
        publish()
    }

    private fun updateStorage(infos: CameraStorageInfos?) {
        if (infos == null) return
        val entries = infos.cameraStorageInfoList.orEmpty()
        val current = infos.currentStorageType ?: CameraStorageLocation.UNKNOWN
        val currentInfo = infos.currentCameraStorageInfo
        update(
            storageLocation = current,
            supportedStorage = entries.mapNotNull { it.storageType }.filter { it != CameraStorageLocation.UNKNOWN }.distinct(),
            storageState = currentInfo?.storageState?.name ?: "UNKNOWN",
            availablePhotoCount = currentInfo?.availablePhotoCount,
            availableVideoSeconds = currentInfo?.availableVideoDuration,
            message = when (currentInfo?.storageState?.name) {
                "NOT_INSERTED" -> text(R.string.camera_sd_not_inserted)
                "INSERTED" -> if (snapshot.message == text(R.string.camera_sd_not_inserted)) {
                    text(R.string.camera_connected)
                } else snapshot.message
                else -> snapshot.message
            },
            messageSeverity = if (currentInfo?.storageState?.name == "NOT_INSERTED") {
                MessageSeverity.ERROR
            } else snapshot.messageSeverity,
        )
    }

    private fun publish() {
        if (!destroyed) onSnapshot(snapshot)
    }

    private fun text(resource: Int, vararg args: Any?): String = context.getString(resource, *args)

    private fun Throwable.safeMessage() = message ?: javaClass.simpleName

    private fun <T> setCadenceValue(
        key: dji.sdk.keyvalue.key.DJIKeyInfo<T>,
        value: T,
        index: ComponentIndexType,
        completion: (Result<Unit>) -> Unit,
    ) {
        runCatching {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(key, index),
                value,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        handler.post { completion(Result.success(Unit)) }
                    }
                    override fun onFailure(error: IDJIError) {
                        handler.post {
                            completion(Result.failure(IllegalStateException(error.safeDescription())))
                        }
                    }
                },
            )
        }.onFailure { completion(Result.failure(it)) }
    }

    private fun startIntervalAction(
        index: ComponentIndexType,
        completion: (Result<Unit>) -> Unit,
    ) = performCadenceAction(CameraKey.KeyStartShootPhoto, index, completion)

    private fun performCadenceAction(
        key: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        index: ComponentIndexType,
        completion: (Result<Unit>) -> Unit,
    ) {
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(key, index),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) {
                        handler.post { completion(Result.success(Unit)) }
                    }

                    override fun onFailure(error: IDJIError) {
                        handler.post {
                            completion(Result.failure(IllegalStateException(error.safeDescription())))
                        }
                    }
                },
            )
        }.onFailure { completion(Result.failure(it)) }
    }

    private companion object {
        const val PHOTO_READY_POLL_MILLIS = 50L
        const val PHOTO_READY_STABLE_MILLIS = 200L
        const val PHOTO_READY_TIMEOUT_MILLIS = 8_000L
        const val OPERATION_TIMEOUT_MS = 8_000L
        const val CADENCE_SETTINGS_SETTLE_MILLIS = 1_000L
    }
}
