package edu.playground.djivln.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.PhotoRatio
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.SimulatorManager
import edu.playground.djivln.R
import edu.playground.djivln.adapter.dji.DjiV5FlightControlPort
import edu.playground.djivln.adapter.dji.DjiV5CameraDiscovery
import edu.playground.djivln.adapter.dji.DjiV5CameraFrameSource
import edu.playground.djivln.adapter.dji.DjiV5GimbalPort
import edu.playground.djivln.adapter.dji.DjiWaylinePartition
import edu.playground.djivln.adapter.dji.DjiWpmzPackageWriter
import edu.playground.djivln.adapter.dji.DjiWpmzPayloadLens
import edu.playground.djivln.adapter.dji.DjiWpmzRoutePolicy
import edu.playground.djivln.survey.RecaptureFlightMode
import edu.playground.djivln.survey.RecaptureFlightModePolicy
import edu.playground.djivln.survey.ContinuousCapturePosePolicy
import edu.playground.djivln.survey.StoppedCapturePosePolicy
import edu.playground.djivln.adapter.dji.DjiV5WaylinePort
import edu.playground.djivln.camera.CameraCaptureController
import edu.playground.djivln.camera.SurveyTriggerFrameRecorder
import edu.playground.djivln.databinding.ViewSurveyFeatureBinding
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylinePort
import edu.playground.djivln.domain.wayline.WaylineMissionName
import edu.playground.djivln.domain.wayline.WaylineState
import edu.playground.djivln.domain.wayline.WaylineUiPolicy
import edu.playground.djivln.localization.resolve
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.ActiveRecaptureMissionRegionFilter
import edu.playground.djivln.survey.ActiveRecaptureMissionGroupCatalog
import edu.playground.djivln.survey.ActiveRecaptureMissionValidator
import edu.playground.djivln.survey.ImportedMissionCameraCompatibilityPolicy
import edu.playground.djivln.survey.SurveyAltitudeMode
import edu.playground.djivln.survey.SurveyCaptureTriggerMode
import edu.playground.djivln.survey.SurveyCaptureView
import edu.playground.djivln.survey.SurveyCollectionMode
import edu.playground.djivln.survey.SurveyCompletionAction
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyMissionExecutionOverrides
import edu.playground.djivln.survey.SurveyMissionCaptureViewFilter
import edu.playground.djivln.survey.SurveyMissionLibrary
import edu.playground.djivln.survey.SurveyMissionReplay
import edu.playground.djivln.survey.SurveyReplayState
import edu.playground.djivln.survey.SurveyCustomExecutionEngine
import edu.playground.djivln.survey.SurveyCustomExecutionSnapshot
import edu.playground.djivln.survey.SurveyDjiRemainingEstimator
import edu.playground.djivln.survey.SurveyRemainingEstimate
import edu.playground.djivln.survey.SurveyEtaSnapshot
import edu.playground.djivln.survey.SurveyEtaPhase
import edu.playground.djivln.survey.SurveyDjiBreakpointEstimator
import edu.playground.djivln.survey.DjiKmzAppCaptureCoordinator
import edu.playground.djivln.survey.DjiWaylineTelemetryPolicy
import edu.playground.djivln.survey.DjiCameraProfileCatalog
import edu.playground.djivln.survey.DjiWaylineCaptureStrategy
import edu.playground.djivln.survey.SurveyExecutionBackend
import edu.playground.djivln.survey.SurveyExecutionBlock
import edu.playground.djivln.survey.SurveyExecutionCheckpoint
import edu.playground.djivln.survey.SurveyExecutionCheckpointJson
import edu.playground.djivln.survey.SurveyGimbalSettlePolicy
import edu.playground.djivln.survey.SurveyFeatureAvailability
import edu.playground.djivln.survey.SurveyMissionJson
import edu.playground.djivln.survey.SurveyWaylineExport
import edu.playground.djivln.survey.SurveyParameterPolicy
import edu.playground.djivln.survey.SurveyPlanner
import edu.playground.djivln.survey.SurveyPhotoTrigger
import edu.playground.djivln.survey.SurveyObliqueHeadingMode
import edu.playground.djivln.survey.SurveyStartPointMode
import edu.playground.djivln.survey.SurveyTakeoffMode
import edu.playground.djivln.survey.STANDARD_SURVEY_CAPTURE_VIEWS
import edu.playground.djivln.survey.GeoTiffTerrain
import edu.playground.djivln.survey.GlobalBuildingHeightDownloader
import edu.playground.djivln.survey.GlobalTerrainDownloader
import edu.playground.djivln.survey.CompositeSurfaceElevationSource
import edu.playground.djivln.survey.SurveyTerrainPlanner
import edu.playground.djivln.survey.SurveyTerrainSourceCatalog
import edu.playground.djivln.survey.SurveyTerrainSourceKind
import edu.playground.djivln.survey.SurveyTerrainTakeoffReference
import edu.playground.djivln.survey.SurveyTerrainTakeoffReferencePolicy
import edu.playground.djivln.survey.SurveyTerrainTakeoffReferenceSource
import edu.playground.djivln.survey.TerrainElevationSource
import edu.playground.djivln.survey.TerrainImportSafety
import edu.playground.djivln.survey.TerrainPreviewData
import edu.playground.djivln.survey.TerrainPreviewSampler
import edu.playground.djivln.survey.surveyPasses
import edu.playground.djivln.hil.HilRuntimeSession
import edu.playground.djivln.hil.DjiV5SimulatorAuthority
import edu.playground.djivln.reconstruction.V86HttpClient
import edu.playground.djivln.reconstruction.V86OfflineImageCompressor
import edu.playground.djivln.reconstruction.V86PointCloudActivity
import edu.playground.djivln.reconstruction.V86PointCloudCachePolicy
import edu.playground.djivln.reconstruction.V86SessionConfig
import edu.playground.djivln.reconstruction.V86ReplayCaptureViewCatalog
import edu.playground.djivln.reconstruction.V86StreamingController
import edu.playground.djivln.reconstruction.V86WorkflowPolicy
import edu.playground.djivln.storage.PublicArtifactStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class SurveyFeatureController(
    private val activity: Activity,
    container: FrameLayout,
    private val snapshot: () -> AircraftSnapshot,
    private val hilSession: HilRuntimeSession,
    private val cameraDiscovery: DjiV5CameraDiscovery,
    private val mapEditor: SurveyMapEditor,
    private val waylinePort: WaylinePort = DjiV5WaylinePort(activity),
    private val recordEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val accountIssue: () -> String? = { null },
    private val onPhotoCaptureFeedback: (Boolean) -> Unit = {},
    private val onMissionChanged: (SurveyMission?) -> Unit = {},
    private val onEtaChanged: (SurveyEtaSnapshot?) -> Unit = {},
    private val onCenterMap: () -> Unit = {},
    private val isSimulatorActive: () -> Boolean = { false },
    private val simulatorOriginPoint: () -> GeoPoint? = { null },
    private val onSetSimulatorOrigin: (GeoPoint, (Boolean, String) -> Unit) -> Unit = { _, callback ->
        callback(false, activity.getString(R.string.simulator_origin_setting_unavailable))
    },
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private data class PendingCheckpointRestore(
        val mission: SurveyMission,
        val checkpoint: SurveyExecutionCheckpoint,
        val backend: SurveyExecutionBackend,
        val ueEndpoint: String,
        val sourceLabel: String,
    )

    private data class V86OfflineDocument(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val relativePath: String,
    )

    private val binding = ViewSurveyFeatureBinding.inflate(LayoutInflater.from(activity), container, false)
    private var djiContinuousCaptureIndices: Set<Int> = emptySet()
    private val djiMissedCapturePasses = mutableSetOf<Int>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "survey-feature-worker").apply { isDaemon = true }
    }
    private var mission: SurveyMission? = null
    private var etaExecutionMissionId: String? = null
    private var djiEtaMissionId: String? = null
    private var djiEtaSegmentFloor = 0
    private var kmzFile: File? = null
    private var kmzMissionId: String? = null
    private var kmzRthHeightMeters: Double? = null
    private var kmzPayloadPositionIndex: Int? = null
    private var kmzPayloadLensName: String? = null
    private var kmzExecutionSpeedMetersPerSecond: Double? = null
    private var kmzObliqueSpeedMetersPerSecond: Double? = null
    private var kmzTakeoffSpeedMetersPerSecond: Double? = null
    private var uploadedKmzSessionSignature: String? = null
    private var kmzPreparationGeneration = 0L
    private var waylineState = WaylineState()
    private var operationMessage: String? = null
    private val terrainSources = SurveyTerrainSourceCatalog(activity)
    private val terrain: TerrainElevationSource?
        get() = terrainSources.active?.source
    private val globalTerrainBase: TerrainElevationSource?
        get() = terrainSources.bareEarth?.source
    private val terrainSha256: String?
        get() = terrainSources.active?.normalizedSha256
    private var terrainPreview: TerrainPreviewData? = null
    private var editedRoi: List<GeoPoint> = emptyList()
    private var selectedVertexIndex: Int? = null
    private var captureSelectionSourceMission: SurveyMission? = null
    private var activeRecaptureSourceMission: SurveyMission? = null
    private var replay: SurveyMissionReplay? = null
    private var manualTakeoverPreflightRejected = false
    private val replayHandler = Handler(Looper.getMainLooper())
    private val persistenceHandler = Handler(Looper.getMainLooper())
    private val artifactStore = PublicArtifactStore(activity)
    private val pendingTriggerFrameSources = mutableSetOf<DjiV5CameraFrameSource>()
    private var v86Snapshot = V86StreamingController.Snapshot()
    private var v86OfflineImporting = false
    @Volatile private var v86OfflineProgressMessage: String? = null
    @Volatile private var v86OfflineProgressError: String? = null
    @Volatile private var v86PlyProgressLabel: String? = null
    private var v86PlyPreparing = false
    private var v86DialogStatus: TextView? = null
    private var v86NewSessionButton: Button? = null
    private var v86FinalizeButton: Button? = null
    private var v86RetryUploadButton: Button? = null
    private var v86RetryProcessingButton: Button? = null
    private var v86ImportMissionButton: Button? = null
    private var v86CloseSessionButton: Button? = null
    private var v86DialogSetupViews: List<View> = emptyList()
    private val v86RefreshHandler = Handler(Looper.getMainLooper())
    private val v86Controller = V86StreamingController(activity) { state ->
        activity.runOnUiThread {
            val hadSession = v86Snapshot.sessionId != null
            v86Snapshot = state
            renderV86Status()
            if (!hadSession && state.sessionId != null) {
                scheduleV86Refresh(immediate = false)
            }
        }
    }
    private val v86OfflineCompressor = V86OfflineImageCompressor(
        File(activity.cacheDir, "v86-offline-exif"),
        activity,
    )
    private var v86ReplayNameFilter = ""
    private var v86ReplayMaximumImages = 0
    private val v86RefreshRunnable = object : Runnable {
        override fun run() {
            val state = v86Snapshot
            if (state.sessionId != null && !state.busy) v86Controller.refresh()
            if (state.sessionId != null) {
                val interval = when {
                    v86DialogStatus != null -> V86_REFRESH_INTERVAL_MILLIS
                    state.sealed && !state.completed -> V86_BACKGROUND_PROCESSING_REFRESH_MILLIS
                    !state.sealed -> V86_BACKGROUND_STREAMING_REFRESH_MILLIS
                    else -> V86_BACKGROUND_COMPLETE_REFRESH_MILLIS
                }
                v86RefreshHandler.postDelayed(this, interval)
            }
        }
    }
    private val triggerFrameRecorder = SurveyTriggerFrameRecorder(
        save = artifactStore::save,
        onEvent = recordEvent,
        exifWriter = edu.playground.djivln.camera.SurveyFrameExifWriter(
            java.io.File(activity.cacheDir, "survey-frame-exif"),
            activity,
        ),
        metadataTransform = v86Controller::enrichCaptureMetadata,
        onSaved = { frame ->
            val cloud = v86Controller.current()
            if (cloud.sessionId != null && !cloud.sealed) {
                val queued = v86Controller.enqueueCapture(
                    trigger = frame.trigger,
                    metadata = frame.metadata,
                    displayName = frame.displayName,
                    mimeType = frame.mimeType,
                    bytes = frame.bytes,
                )
                recordSurveyEvent(
                    "v86_capture_queued",
                    mapOf(
                        "session_id" to cloud.sessionId,
                        "sequence" to queued.getOrNull(),
                        "success" to queued.isSuccess,
                        "error" to queued.exceptionOrNull()?.message,
                        "saved_path" to frame.savedPath,
                    ),
                )
            }
        },
        onSkipped = { reason -> v86Controller.reportCaptureRejected(reason) },
        context = activity,
    )
    private val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
    /**
     * Checkpoints are intentionally isolated from the multi-megabyte mission/library preferences.
     * An emergency RTH must not rewrite the whole mission library before the resume point is safe.
     */
    private val checkpointPreferences = activity.getSharedPreferences(
        CHECKPOINT_PREFERENCES,
        Activity.MODE_PRIVATE,
    )
    /** Version history is large and manually managed; keep it away from frequently saved drafts. */
    private val libraryPreferences = activity.getSharedPreferences(
        LIBRARY_PREFERENCES,
        Activity.MODE_PRIVATE,
    )
    private val simulatorManager = SimulatorManager.getInstance()
    private val djiCaptureCoordinator = DjiKmzAppCaptureCoordinator()
    private val djiCaptureCamera = CameraCaptureController(activity) { }
    private var djiCaptureGimbal: DjiV5GimbalPort? = null
    private var djiCaptureGimbalIndex = dji.sdk.keyvalue.value.common.ComponentIndexType.UNKNOWN
    private var djiCaptureTargetPitchDegrees: Double? = null
    private var djiCaptureGimbalCommandAcceptedAtMillis = 0L
    private var djiCaptureGimbalLastCommandAtMillis = 0L
    private var djiCaptureGimbalSettlingStartedAtMillis = 0L
    private var djiCaptureGimbalVerificationStartedAtMillis = 0L
    private var djiCaptureGimbalTimeoutHandled = false
    private var djiCaptureGimbalLastGateLogAtMillis = 0L
    private var djiCapturePoseWaypointIndex: Int? = null
    private var djiCapturePoseStableSinceMillis = 0L
    private var djiCapturePoseVerificationStartedAtMillis = 0L
    private var djiCapturePoseTimeoutHandled = false
    private var djiCapturePoseLastGateLogAtMillis = 0L
    private var customState = SurveyCustomExecutionSnapshot(message = activity.getString(R.string.not_started))
    private var selectedTab = SurveyTab.AREA
    private var mapThreeDimensional = false
    private var simulatorOriginPickMode = false
    private var renderedSimulatorOrigin: GeoPoint? = null
    private var settingsRestoring = false
    private var captureSelectionChanging = false
    private var backendSelectionChanging = false
    private var lastBackendPosition = 0
    private var lastTerrainKindPosition = TERRAIN_KIND_SURFACE_DSM
    private var preparingDjiExecution = false
    private var djiPreparationGeneration = 0L
    private var lastCheckpointSignature: String? = null
    private var lastDjiCheckpointSignature: String? = null
    private var lastDjiEventSignature: String? = null
    private var lastHandledDjiActionFailure: String? = null
    private var lastCustomEventSignature: String? = null
    private var lastCommandLogElapsedNanos = 0L
    private var diagnosticGimbal: DjiV5GimbalPort? = null
    private var lastLiveRefreshElapsedNanos = 0L
    private var lastLiveConnected = false
    private var pendingCheckpointRestore: PendingCheckpointRestore? = null
    private var restoredDjiCheckpoint: PendingCheckpointRestore? = null
    private var activeDjiRecoveryDisplayCheckpoint: SurveyExecutionCheckpoint? = null
    private var continuingDjiWaylines = false
    private var pendingDjiContinuationWaylineId: Int? = null
    private var djiPartitionContinuationGeneration = 0L
    private var completedDjiMissionFileName: String? = null
    private val persistSettingsRunnable = Runnable(::persistPlannerSettings)
    private val flightControlPort = DjiV5FlightControlPort()
    private val customExecution = SurveyCustomExecutionEngine(
        context = activity,
        flightPort = flightControlPort,
        gimbalPortFactory = ::DjiV5GimbalPort,
        snapshot = ::effectiveSnapshot,
        cameraIndex = { cameraDiscovery.current().index },
        cameraGeometryMatches = { cameraGeometryMatches(it) },
        latestHilFrame = hilSession::latestFrame,
        saveHilCapture = { displayName, mimeType, bytes, callback ->
            artifactStore.save("survey-captures", displayName, mimeType, bytes) { result ->
                recordSurveyEvent(
                    "capture_file",
                    mapOf(
                        "display_name" to displayName,
                        "mime_type" to mimeType,
                        "bytes" to bytes.size,
                        "saved_path" to result.getOrNull(),
                        "error" to result.exceptionOrNull()?.message,
                    ),
                )
                callback(result)
            }
        },
        onPhotoTriggered = ::captureDjiTriggerFrame,
        onCommand = { command ->
            hilSession.updateCommand(command)
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastCommandLogElapsedNanos >= COMMAND_LOG_INTERVAL_NANOS ||
                command == edu.playground.djivln.domain.flight.BodyVelocityCommand.ZERO
            ) {
                lastCommandLogElapsedNanos = now
                recordSurveyEvent(
                    "control_command",
                    mapOf(
                        "forward_mps" to command.forwardMetersPerSecond,
                        "right_mps" to command.rightMetersPerSecond,
                        "up_mps" to command.upMetersPerSecond,
                        "yaw_rate_dps" to command.yawRateDegreesPerSecond,
                    ),
                )
            }
        },
        onEvent = ::recordSurveyEvent,
        hilPeerHost = { hilSession.status()?.peerHost },
    ) { state ->
        activity.runOnUiThread {
            customState = state
            persistCustomCheckpoint(state)
            recordCustomState(state)
            renderStatus()
        }
    }
    private val replayTick = object : Runnable {
        override fun run() {
            val current = replay ?: return
            val state = current.advance()
            mapEditor.renderReplayPoint(state.point, state.headingDegrees, state.captureActive)
            operationMessage = activity.getString(R.string.survey_replay_progress, state.sampleIndex + 1, state.totalSamples, "%.0f".format(state.progress * 100))
            binding.previewSurvey.text = when (state.state) {
                SurveyReplayState.RUNNING -> activity.getString(R.string.survey_replay_pause)
                SurveyReplayState.PAUSED -> activity.getString(R.string.survey_replay_resume)
                else -> activity.getString(R.string.survey_preview)
            }
            renderStatus()
            if (state.state == SurveyReplayState.RUNNING) {
                replayHandler.postDelayed(this, REPLAY_TICK_MILLIS)
            }
        }
    }

    init {
        container.addView(binding.root)
        compactSurveyActionLayout()
        binding.surveyPreview.setMapOverlayMode(true)
        applyCockpitStyle()
        binding.tabArea.setOnClickListener { selectTab(SurveyTab.AREA) }
        binding.tabFlight.setOnClickListener { selectTab(SurveyTab.FLIGHT) }
        binding.tabCapture.setOnClickListener { selectTab(SurveyTab.CAPTURE) }
        binding.tabTerrain.setOnClickListener { selectTab(SurveyTab.TERRAIN) }
        binding.tabTerrain.visibility = View.GONE
        binding.sectionTerrain.visibility = View.GONE
        binding.followTerrain.isChecked = false
        binding.closeSurvey.setOnClickListener { onClose() }
        binding.surveyMore.setOnClickListener { showMoreMenu() }
        binding.surveyExecutionStatus.setOnClickListener { showExecutionStatusDetails() }
        binding.surveyHeaderPause.setOnClickListener { handlePrimaryExecutionAction() }
        binding.surveyHeaderAbort.setOnClickListener { binding.stopWayline.performClick() }
        binding.generateSurvey.setOnClickListener { generateSafely(obliqueFiveDirection = false) }
        binding.generateFiveDirection.setOnClickListener { generateSafely(obliqueFiveDirection = true) }
        binding.activeRecaptureGroups.setOnClickListener { showActiveRecaptureGroupDialog() }
        binding.continuousRecapture.setOnClickListener { confirmRecaptureFlightMode() }
        binding.cloudReconstruction.setOnClickListener { showV86Dialog() }
        binding.openPointCloud.setOnClickListener { handleV86PointCloudAction() }
        binding.obliqueAngle.setOnClickListener { showObliqueAngleDialog() }
        binding.previewSurvey.setOnClickListener { toggleReplay() }
        binding.preflightSurvey.setOnClickListener { preflight() }
        binding.centerSurvey.setOnClickListener { onCenterMap() }
        binding.setSimulatorOriginOnMap.setOnClickListener {
            simulatorOriginPickMode = !simulatorOriginPickMode
            binding.setSimulatorOriginOnMap.setText(
                if (simulatorOriginPickMode) R.string.simulator_origin_cancel else R.string.simulator_origin_set,
            )
            operationMessage = if (simulatorOriginPickMode) {
                activity.getString(R.string.simulator_origin_pick_map_hint)
            } else {
                activity.getString(R.string.simulator_origin_pick_cancelled)
            }
            renderStatus()
        }
        binding.undoSurveyVertex.setOnClickListener { undoSurveyVertex() }
        binding.deleteSurveyVertex.setOnClickListener { deleteSelectedSurveyVertex() }
        binding.clearSurveyVertices.setOnClickListener { clearSurveyVertices() }
        binding.setRthHeight.setOnClickListener { setRthHeight() }
        scheduleV86Refresh(immediate = false)
        binding.buildingCogTemplate.setText(
            activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                .getString(KEY_BUILDING_COG_TEMPLATE, "").orEmpty(),
        )
        binding.executionBackend.adapter = ArrayAdapter(
            activity,
            edu.playground.djivln.R.layout.item_survey_spinner,
            SurveyExecutionBackend.values().map { activity.getString(it.labelRes) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.executionBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!backendSelectionChanging && position != lastBackendPosition && isMissionLockedForEditing()) {
                    backendSelectionChanging = true
                    binding.executionBackend.setSelection(lastBackendPosition)
                    backendSelectionChanging = false
            reject(activity.getString(R.string.survey_stop_before_backend_switch))
                    return
                }
                lastBackendPosition = position
                schedulePersistPlannerSettings()
                renderStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        setupPlanningOptions()
        listOf(
            binding.viewNadir,
            binding.viewForward,
            binding.viewBackward,
            binding.viewLeft,
            binding.viewRight,
        ).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, checked ->
                onCaptureViewChanged(checkbox, checked)
            }
        }
        installTerrainFollowListener()
        binding.terrainKind.adapter = ArrayAdapter(
            activity,
            edu.playground.djivln.R.layout.item_survey_spinner,
            listOf(activity.getString(R.string.terrain_surface_dsm), activity.getString(R.string.terrain_bare_earth_dem)),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.terrainKind.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val previousPosition = lastTerrainKindPosition
                val selectionChanged = position != previousPosition
                val requestedKind = if (position == TERRAIN_KIND_BARE_EARTH) {
                    SurveyTerrainSourceKind.BARE_EARTH
                } else {
                    SurveyTerrainSourceKind.SURFACE_DSM
                }
                if (settingsRestoring || terrainSources.bareEarth == null && terrainSources.surface == null) {
                    terrainSources.prefer(requestedKind)
                } else if (selectionChanged) {
                    val switched = terrainSources.select(requestedKind)
                    if (switched.isFailure) {
                        binding.terrainKind.setSelection(previousPosition)
                        reject(switched.exceptionOrNull()?.message ?: activity.getString(R.string.terrain_data_not_loaded))
                        return
                    }
                }
                lastTerrainKindPosition = position
                val buildingSurface = position == TERRAIN_KIND_SURFACE_DSM
                binding.dsmBuildingConfirm.isEnabled = buildingSurface && terrain != null
                if (!buildingSurface) binding.dsmBuildingConfirm.isChecked = false
                if (!settingsRestoring && selectionChanged && mission != null) {
                    invalidateGeneratedMission(activity.getString(R.string.terrain_type_changed_regenerate))
                }
                schedulePersistPlannerSettings()
                renderTerrainStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.dsmBuildingConfirm.setOnCheckedChangeListener { _, _ ->
            if (!settingsRestoring && mission != null) {
                invalidateGeneratedMission(activity.getString(R.string.dsm_review_changed_regenerate))
            }
            schedulePersistPlannerSettings()
            renderTerrainStatus()
        }
        setupGsdAltitudeLink()
        settingsRestoring = true
        restorePlannerSettings()
        restoreMissionSession()
        settingsRestoring = false
        setupPlannerSettingsPersistence()
        binding.exportKmz.setOnClickListener { exportKmz() }
        binding.exportSurveyArtifacts.setOnClickListener { exportArtifacts() }
        binding.importDsm.setOnClickListener { activity.startActivityForResult(openTerrainDocument(), REQUEST_IMPORT_DSM) }
        binding.downloadTerrainDem.setOnClickListener { downloadGlobalTerrain() }
        binding.importMission.setOnClickListener { activity.startActivityForResult(openJsonDocument(), REQUEST_IMPORT_MISSION) }
        binding.importCheckpoint.setOnClickListener {
            activity.startActivityForResult(openJsonDocument(), REQUEST_IMPORT_CHECKPOINT)
        }
        binding.downloadBuildingDsm.setOnClickListener { openOrDownloadBuildingHeight() }
        binding.uploadWayline.setOnClickListener { uploadWayline() }
        binding.executeWayline.setOnClickListener { handlePrimaryExecutionAction() }
        binding.pauseWayline.setOnClickListener { pauseSelectedBackend() }
        binding.resumeWayline.setOnClickListener { resumeSelectedBackend() }
        binding.stopWayline.setOnClickListener { stopSelectedBackend() }
        waylinePort.start { state ->
            activity.runOnUiThread {
                val previousState = waylineState
                waylineState = state
                refreshRestoredDjiCheckpoint(state)
                updateDjiRecoveryDisplay(state)
                if (handlePartitionedDjiMissionState(previousState, state)) {
                    recordDjiState(state)
                    renderStatus()
                    return@runOnUiThread
                }
                if (state.phase in setOf(
                        edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED,
                        edu.playground.djivln.domain.wayline.WaylinePhase.ERROR,
                        edu.playground.djivln.domain.wayline.WaylinePhase.DISCONNECTED,
                    )
                ) stopDjiAppCapture()
                if (state.phase == edu.playground.djivln.domain.wayline.WaylinePhase.DISCONNECTED) {
                    uploadedKmzSessionSignature = null
                }
                preserveDjiRecoveryAfterUnexpectedStop(previousState, state)
                persistDjiCheckpoint(state)
                recordDjiState(state)
                handleDjiActionFailure(state)
                renderStatus()
            }
        }
        selectTab(selectedTab)
        updateCaptureViewActions()
        renderTerrainStatus()
        refreshRthHeight()
        refreshBuildingHeightActionLabel()
        updateSpeedLimitHint()
        renderStatus()
    }

    /** Keep only the stateful primary action dock fixed; secondary capture/cloud controls scroll. */
    private fun compactSurveyActionLayout() = with(binding) {
        listOf(captureViewSelector, generateActionsRow, activeRecaptureGroups, cloudActionsRow).forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            sectionCapture.addView(view)
        }
        (stopWayline.parent as? ViewGroup)?.removeView(stopWayline)
        primaryActionsRow.addView(
            stopWayline,
            LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(3) },
        )
        executionActionsRow.visibility = View.GONE
        surveyHeaderPause.visibility = View.GONE
        surveyHeaderAbort.visibility = View.GONE
    }

    fun mapHost(): FrameLayout = binding.surveyMapHost

    fun attachTo(container: FrameLayout) {
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
        container.addView(binding.root)
    }

    fun setMapEditingEnabled(enabled: Boolean) {
        if (!enabled) {
            mapEditor.endEditing()
            return
        }
        mapEditor.beginEditing(::onSurveyMapTap, ::onSurveyVertexTap)
        renderEditableVertices()
    }

    private var cameraGeometryPausePending = false

    fun refreshLiveState() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastLiveRefreshElapsedNanos < LIVE_REFRESH_INTERVAL_NANOS) return
        lastLiveRefreshElapsedNanos = now
        val connected = snapshot().connected
        if (connected != lastLiveConnected) {
            lastLiveConnected = connected
            refreshRthHeight()
        }
        restorePendingCheckpointWhenReady()
        val current = mission
        if (!cameraGeometryPausePending && current != null && waylineState.phase in setOf(
                edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
                edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
            ) && !cameraGeometryMatches(current)) {
            cameraGeometryPausePending = true
            pauseForExternalIntervention(activity.getString(R.string.current_camera_not_calibrated)) {
                cameraGeometryPausePending = false
            }
        }
        updateDjiAppCapture()
        renderSimulatorOriginAction()
        if (manualTakeoverPreflightRejected && !effectiveSnapshot().sticksActive) {
            manualTakeoverPreflightRejected = false
            operationMessage = activity.getString(R.string.rc_sticks_centered_ready)
        }
        renderStatus()
    }

    private fun onSurveyMapTap(point: GeoPoint) {
        if (simulatorOriginPickMode) {
            simulatorOriginPickMode = false
        binding.setSimulatorOriginOnMap.setText(R.string.simulator_origin_set)
            binding.setSimulatorOriginOnMap.isEnabled = false
            operationMessage = activity.getString(R.string.switching_simulator_origin)
            renderStatus()
            onSetSimulatorOrigin(point) { success, message ->
                activity.runOnUiThread {
                    binding.setSimulatorOriginOnMap.isEnabled = true
                    operationMessage = message
                    if (success) {
                        renderedSimulatorOrigin = point
                        mapEditor.renderSimulatorOrigin(point)
                    }
                    renderStatus()
                }
            }
            return
        }
        if (mission?.activeMapping != null) {
            operationMessage = activity.getString(R.string.cloud_recapture_read_only)
            renderStatus()
            return
        }
        if (rejectEditingIfLocked()) return
        val selected = selectedVertexIndex
        editedRoi = if (selected != null && selected in editedRoi.indices) {
            editedRoi.toMutableList().also { it[selected] = point }
        } else {
            editedRoi + point
        }
        selectedVertexIndex = null
        updateSuggestedRouteHeading()
        invalidateGeneratedMission(
            if (selected != null) activity.getString(R.string.boundary_point_moved_regenerate, selected + 1)
            else activity.getString(R.string.boundary_point_added_count, editedRoi.size),
        )
    }

    private fun renderSimulatorOriginAction() {
        val active = isSimulatorActive()
        binding.setSimulatorOriginOnMap.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) simulatorOriginPickMode = false
        val point = if (active) simulatorOriginPoint() else null
        if (point != renderedSimulatorOrigin) {
            renderedSimulatorOrigin = point
            mapEditor.renderSimulatorOrigin(point)
        }
    }

    private fun onSurveyVertexTap(index: Int) {
        if (mission?.activeMapping != null) return
        if (rejectEditingIfLocked()) return
        if (index !in editedRoi.indices) return
        selectedVertexIndex = index
        mapEditor.renderVertices(editedRoi, index)
        operationMessage = activity.getString(R.string.boundary_point_selected_hint, index + 1)
        renderStatus()
    }

    private fun renderEditableVertices() {
        val readOnlyActiveRecapture = mission?.activeMapping != null
        mapEditor.renderVertices(
            if (readOnlyActiveRecapture) emptyList() else editedRoi,
            if (readOnlyActiveRecapture) null else selectedVertexIndex,
        )
    }

    private fun undoSurveyVertex() {
        if (rejectEditingIfLocked()) return
        if (editedRoi.isEmpty()) return reject(activity.getString(R.string.no_boundary_point_to_undo))
        editedRoi = editedRoi.dropLast(1)
        selectedVertexIndex = null
        updateSuggestedRouteHeading()
        invalidateGeneratedMission(activity.getString(R.string.boundary_point_undone_count, editedRoi.size))
    }

    private fun deleteSelectedSurveyVertex() {
        if (rejectEditingIfLocked()) return
        val selected = selectedVertexIndex ?: return reject(activity.getString(R.string.select_boundary_point_first))
        if (selected !in editedRoi.indices) return
        editedRoi = editedRoi.filterIndexed { index, _ -> index != selected }
        selectedVertexIndex = null
        updateSuggestedRouteHeading()
        invalidateGeneratedMission(activity.getString(R.string.boundary_point_deleted_count, selected + 1, editedRoi.size))
    }

    private fun clearSurveyVertices() {
        if (rejectEditingIfLocked()) return
        if (editedRoi.isEmpty() && mission == null) return reject(activity.getString(R.string.no_survey_task_to_clear))
        AlertDialog.Builder(activity)
            .setTitle(R.string.survey_clear_title)
            .setMessage(R.string.survey_clear_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.survey_clear_confirm) { _, _ ->
                editedRoi = emptyList()
                selectedVertexIndex = null
                captureSelectionSourceMission = null
                activeRecaptureSourceMission = null
                invalidateGeneratedMission(activity.getString(R.string.survey_cleared_draw_boundary_hint))
            }
            .show()
    }

    private fun invalidateGeneratedMission(message: String) {
        stopReplay(clearMarker = true)
        pendingCheckpointRestore = null
        restoredDjiCheckpoint = null
        activeDjiRecoveryDisplayCheckpoint = null
        cancelPreparingDjiExecution(message, announce = false)
        mission = null
        captureSelectionSourceMission = null
        activeRecaptureSourceMission = null
        kmzFile = null
        kmzMissionId = null
        kmzRthHeightMeters = null
        kmzPayloadPositionIndex = null
        kmzPayloadLensName = null
        kmzExecutionSpeedMetersPerSecond = null
        kmzObliqueSpeedMetersPerSecond = null
        kmzTakeoffSpeedMetersPerSecond = null
        uploadedKmzSessionSignature = null
        clearPersistedSurveySession()
        mapEditor.renderVertices(editedRoi, selectedVertexIndex)
        binding.surveyPreview.showMission(null)
        binding.terrainAltitudeLegend.clearRange()
        if (editedRoi.size >= 3) binding.surveyPreview.editRoi(editedRoi, ::onRoiEdited)
        onMissionChanged(null)
        operationMessage = message
        renderStatus()
    }

    private fun isMissionLockedForEditing(): Boolean {
        if (binding.dsmDownloadProgress.visibility == View.VISIBLE) return true
        val djiLocked = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.UPLOADING,
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        val customLocked = customState.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
            edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
        )
        return preparingDjiExecution || djiLocked || customLocked
    }

    private fun rejectEditingIfLocked(): Boolean {
        if (!isMissionLockedForEditing()) return false
        if (binding.dsmDownloadProgress.visibility == View.VISIBLE) {
            reject(activity.getString(R.string.terrain_processing_wait))
            return true
        }
            reject(activity.getString(R.string.survey_edit_locked_during_execution))
        return true
    }

    private fun showMoreMenu() {
        val executionAction = when (primaryExecutionAction()) {
            PrimaryExecutionAction.EXECUTE -> activity.getString(R.string.execution)
            PrimaryExecutionAction.PAUSE -> activity.getString(R.string.action_pause)
            PrimaryExecutionAction.RESUME -> activity.getString(R.string.action_continue)
        }
        val actions = arrayOf(
            activity.getString(if (mapThreeDimensional) R.string.map_view_switch_2d else R.string.map_view_switch_3d),
            activity.getString(R.string.real_flight_readiness_audit),
            activity.getString(R.string.gimbal_diagnostic_nadir),
            activity.getString(R.string.gimbal_diagnostic_oblique),
            activity.getString(R.string.save_version),
            activity.getString(R.string.save_resume_point),
            activity.getString(R.string.mission_library),
            activity.getString(R.string.stop_preview),
            activity.getString(R.string.import_mission),
            activity.getString(R.string.export_mission),
            activity.getString(R.string.import_resume_point),
            activity.getString(R.string.generate_validate_kmz),
            activity.getString(R.string.upload_to_aircraft),
            activity.getString(R.string.mission_action, executionAction),
            activity.getString(R.string.abort_mission),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.survey_more_title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> setMapThreeDimensional(!mapThreeDimensional)
                    1 -> preflight()
                    2 -> runGimbalDiagnostic(-90.0)
                    3 -> runGimbalDiagnostic(-45.0)
                    4 -> saveMissionVersion(announce = true)
                    5 -> saveCurrentResumePoint()
                    6 -> showMissionLibrary()
                    7 -> stopReplay(clearMarker = true)
                    8 -> activity.startActivityForResult(openJsonDocument(), REQUEST_IMPORT_MISSION)
                    9 -> exportArtifacts()
                    10 -> activity.startActivityForResult(openJsonDocument(), REQUEST_IMPORT_CHECKPOINT)
                    11 -> exportKmz()
                    12 -> uploadWayline()
                    13 -> handlePrimaryExecutionAction()
                    14 -> stopSelectedBackend()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private var v86RemoteBrowser: edu.playground.djivln.reconstruction.V86RemoteSessionDialog? = null

    private fun showV86Dialog() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf(activity.getString(R.string.v86_remote_open), activity.getString(R.string.v86_remote_upload))) { _, which ->
                if (which == 0) {
                    val browser = v86RemoteBrowser ?: edu.playground.djivln.reconstruction.V86RemoteSessionDialog(
                        activity, ::reviewRemoteV86Mission,
                    ).also { v86RemoteBrowser = it }
                    browser.show()
                } else showV86UploadDialog()
            }.show()
    }

    private fun reviewRemoteV86Mission(raw: String) {
        if (rejectEditingIfLocked()) return
        runCatching { decodeMissionValidated(raw) }
            .onFailure { reject(activity.getString(R.string.v86_schema_parse_failed, it.message.orEmpty())) }
            .onSuccess { imported ->
                AlertDialog.Builder(activity)
                    .setTitle(R.string.v86_remote_mission)
                    .setMessage(imported.name + "\n" + activity.getString(R.string.v86_remote_review))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.v86_remote_import) { _, _ ->
                        if (activity.isFinishing || activity.isDestroyed || rejectEditingIfLocked()) return@setPositiveButton
                        settingsRestoring = true
                        applyMissionToFields(imported)
                        settingsRestoring = false
                        activateMission(imported, activity.getString(R.string.v86_mission_imported_review_required))
                    }.show()
            }
    }

    private fun showV86UploadDialog() {
        val camera = cameraDiscovery.current()
        val aircraft = snapshot()
        val takeoffReference = aircraft.homeLocation ?: aircraft.aircraftLocation
        val derivedTakeoffAsl = if (
            aircraft.altitudeAboveSeaLevelMeters?.isFinite() == true &&
            aircraft.relativeAltitudeMeters?.isFinite() == true &&
            !(kotlin.math.abs(requireNotNull(aircraft.altitudeAboveSeaLevelMeters)) < 0.01 &&
                kotlin.math.abs(requireNotNull(aircraft.relativeAltitudeMeters)) > 2.0)
        ) {
            requireNotNull(aircraft.altitudeAboveSeaLevelMeters) - requireNotNull(aircraft.relativeAltitudeMeters)
        } else null
        val takeoffAsl = aircraft.takeoffAbsoluteAltitudeMeters
            ?.takeIf(Double::isFinite)
            ?: derivedTakeoffAsl
            ?: cachedV86TakeoffAltitude(takeoffReference)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val setupViews = mutableListOf<View>()
        fun label(value: String) = TextView(activity).apply {
            text = value
            textSize = 11f
            setTextColor(0xffc8d4e3.toInt())
            setPadding(0, dp(7), 0, dp(2))
            content.addView(this)
            setupViews += this
        }
        fun field(value: String, hintValue: String, secret: Boolean = false): EditText = EditText(activity).apply {
            setText(value)
            hint = hintValue
            textSize = 12f
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xffaeb9c7.toInt())
            setSingleLine(true)
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            setupViews += this
        }
        label(activity.getString(R.string.v86_service_endpoint))
        val endpoint = field(v86Snapshot.endpoint, V86HttpClient.DEFAULT_ENDPOINT)
        label(activity.getString(R.string.v86_bearer_access_code_label))
        val accessCode = field(
            "",
            activity.getString(if (v86Snapshot.accessCodeStored) R.string.v86_access_code_saved else R.string.v86_access_code_hint),
            secret = true,
        )
        label(activity.getString(R.string.v86_task_name_label))
        val taskName = field(
            mission?.name?.let { activity.getString(R.string.v86_cloud_reconstruction_named, it) }
                ?: activity.getString(R.string.v86_default_task_name),
            activity.getString(R.string.v86_task_name_label),
        )
        label(activity.getString(R.string.v86_parameters_label))
        val parameters = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val fov = EditText(activity).apply {
            setText(String.format(Locale.US, "%.2f", camera.cameraProfile.horizontalFieldOfViewDegrees))
            hint = "FOV"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xffaeb9c7.toInt())
        }
        val takeoff = EditText(activity).apply {
            setText(takeoffAsl?.let { String.format(Locale.US, "%.2f", it) }.orEmpty())
            hint = activity.getString(R.string.takeoff_asl_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xffaeb9c7.toInt())
        }
        val maximumTasks = EditText(activity).apply {
            setText("10")
            hint = activity.getString(R.string.task_count_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xffaeb9c7.toInt())
        }
        parameters.addView(fov, LinearLayout.LayoutParams(0, dp(42), 1f))
        parameters.addView(takeoff, LinearLayout.LayoutParams(0, dp(42), 1f))
        parameters.addView(maximumTasks, LinearLayout.LayoutParams(0, dp(42), 0.7f))
        content.addView(parameters)
        setupViews += parameters
        val continuousCloudRecapture = CheckBox(activity).apply {
            setText(R.string.v86_cloud_continuous_option)
            setTextColor(0xffffb65c.toInt())
            textSize = 10f
            content.addView(this)
            setupViews += this
        }
        val relativeTest = CheckBox(activity).apply {
            setText(R.string.v86_relative_test_option)
            setTextColor(0xffffb65c.toInt())
            textSize = 10f
            setOnCheckedChangeListener { _, checked ->
                takeoff.isEnabled = !checked
                continuousCloudRecapture.isEnabled = !checked
                if (checked) continuousCloudRecapture.isChecked = false
            }
            content.addView(this)
            setupViews += this
        }
        val autoPreview = CheckBox(activity).apply {
            text = activity.getString(R.string.v86_auto_preview)
            textSize = 10f
            setTextColor(0xffc8d4e3.toInt())
            isChecked = preferences.getBoolean(KEY_V86_AUTO_PREVIEW, true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(KEY_V86_AUTO_PREVIEW, checked).apply()
            }
            content.addView(this)
            setupViews += this
        }
        TextView(activity).apply {
            text = activity.getString(R.string.v86_http_warning)
            textSize = 10f
            setTextColor(0xffb45309.toInt())
            setPadding(0, dp(6), 0, dp(6))
            content.addView(this)
            setupViews += this
        }
        val status = TextView(activity).apply {
            textSize = 11f
            setTextColor(0xff172033.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xffeef3f8.toInt())
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        v86DialogStatus = status
        fun actionRow(vararg actions: Pair<String, () -> Unit>): Pair<View, List<Button>> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            val buttons = actions.mapIndexed { index, (title, action) ->
                Button(activity).apply {
                        text = title
                        textSize = 9f
                        minHeight = 0
                        setOnClickListener { action() }
                    }.also { button -> row.addView(
                        button,
                        LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                        if (index > 0) marginStart = dp(4)
                        },
                    ) }
            }
            content.addView(row)
            return row to buttons
        }
        fun saveConnection(): Boolean {
            val token = accessCode.text.toString().trim().ifBlank { v86Controller.accessCode() }
            return v86Controller.saveConnection(endpoint.text.toString(), token).fold(
                onSuccess = { true },
                onFailure = {
                    status.text = activity.getString(R.string.connection_config_error, it.message)
                    false
                },
            )
        }
        val (primaryRow, primaryButtons) = actionRow(
            activity.getString(R.string.v86_create_streaming_task) to start@{
                if (!saveConnection()) return@start
                val config = runCatching {
                    val fovValue = fov.text.toString().toDouble()
                    val takeoffValue = if (relativeTest.isChecked) null else takeoff.text.toString().toDouble()
                    val maximum = maximumTasks.text.toString().toInt()
                    require(fovValue in 10.0..150.0) { activity.getString(R.string.v86_fov_range) }
                    require(relativeTest.isChecked || takeoffValue?.isFinite() == true) { activity.getString(R.string.v86_takeoff_asl_invalid) }
                    require(maximum in 1..30) { activity.getString(R.string.v86_recapture_region_range) }
                    V86SessionConfig(
                        name = taskName.text.toString().trim().ifBlank { activity.getString(R.string.v86_default_task_name) },
                        horizontalFovDegrees = fovValue,
                        takeoffAbsoluteAltitudeMeters = takeoffValue,
                        cameraModel = camera.cameraProfileLabel,
                        autoPreview = autoPreview.isChecked,
                        maximumTasks = maximum,
                        relativeHeightTest = relativeTest.isChecked,
                        recaptureFlightMode = if (continuousCloudRecapture.isChecked)
                            RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL else RecaptureFlightMode.STOP_AND_CAPTURE,
                    )
                }
                config.onSuccess { value ->
                    v86Controller.createSession(value) { result ->
                        result.onSuccess {
                            value.takeoffAbsoluteAltitudeMeters?.let { altitude -> rememberV86TakeoffAltitude(altitude, takeoffReference) }
                            recordSurveyEvent(
                                "v86_session_created",
                                mapOf(
                                    "session_id" to it.id,
                                    "camera" to camera.cameraProfileLabel,
                                    "auto_preview" to autoPreview.isChecked,
                                    "takeoff_asl_m" to value.takeoffAbsoluteAltitudeMeters,
                                    "relative_height_test" to value.relativeHeightTest,
                                    "takeoff_reference_available" to (takeoffReference != null),
                                ),
                            )
                        }
                    }
                }.onFailure { status.text = activity.getString(R.string.parameter_error, it.message) }
            },
            activity.getString(R.string.v86_end_capture) to {
                if (V86WorkflowPolicy.from(v86Snapshot).canDiscardEmpty) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.v86_discard_empty_title)
                        .setMessage(R.string.v86_discard_empty_message)
                        .setNegativeButton(R.string.v86_continue_capture, null)
                        .setPositiveButton(R.string.v86_discard_empty_confirm) { _, _ ->
                            v86Controller.discardEmptySession().onSuccess {
                                recordSurveyEvent("v86_empty_session_discarded")
                            }.onFailure { error ->
                                operationMessage = activity.getString(R.string.v86_discard_empty_failed, error.message.orEmpty())
                                renderStatus()
                            }
                        }
                        .show()
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.v86_finalize_title)
                        .setMessage(if (v86Snapshot.relativeHeightTest) R.string.v86_relative_finalize_message else R.string.v86_finalize_message)
                        .setNegativeButton(R.string.v86_continue_capture, null)
                        .setPositiveButton(R.string.v86_finalize_process) { _, _ -> v86Controller.finalizeSession() }
                        .show()
                }
            },
            activity.getString(R.string.action_refresh) to { v86Controller.refresh() },
        )
        v86NewSessionButton = primaryButtons[0]
        v86FinalizeButton = primaryButtons[1]
        val (resultRow, resultButtons) = actionRow(
            activity.getString(R.string.v86_retry_upload) to { v86Controller.retryUploads() },
            activity.getString(R.string.v86_retry_processing) to {
                clearV86PointCloudCache(v86Snapshot.sessionId)
                v86Controller.retryProcessing()
            },
            activity.getString(R.string.v86_import_recapture_mission) to { importV86Mission() },
        )
        v86RetryUploadButton = resultButtons[0]
        v86RetryProcessingButton = resultButtons[1]
        v86ImportMissionButton = resultButtons[2]
        val (closeRow, closeButtons) = actionRow(
            activity.getString(R.string.v86_close_current_task) to {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.v86_close_task_title)
                    .setMessage(R.string.v86_close_task_message)
                    .setNegativeButton(R.string.v86_continue_capture, null)
                    .setPositiveButton(R.string.v86_close_task_confirm) { _, _ ->
                        v86Controller.terminateCurrentSession { result -> result.onSuccess {
                            recordSurveyEvent("v86_current_session_closed")
                        }.onFailure { error ->
                            operationMessage = activity.getString(R.string.v86_close_task_failed, error.message)
                            renderStatus()
                        } }
                    }
                    .show()
            },
        )
        v86CloseSessionButton = closeButtons[0]
        actionRow(
            activity.getString(R.string.v86_replay_phone_photos) to replay@{
                if (snapshot().isFlying || snapshot().motorsOn) return@replay
                val inputs = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val filter = EditText(activity).apply {
                    hint = activity.getString(R.string.v86_replay_filter_hint)
                    setText(v86ReplayNameFilter)
                    inputs.addView(this)
                }
                val limit = EditText(activity).apply {
                    hint = activity.getString(R.string.v86_replay_limit_hint)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(if (v86ReplayMaximumImages > 0) v86ReplayMaximumImages.toString() else "40")
                    inputs.addView(this)
                }
                AlertDialog.Builder(activity).setTitle(R.string.v86_replay_phone_photos)
                    .setView(inputs).setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_continue) { _, _ ->
                        v86ReplayNameFilter = filter.text.toString().trim()
                        v86ReplayMaximumImages = limit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 40
                        startV86OfflineFolderPicker()
                    }.show()
            },
            activity.getString(R.string.v86_open_existing_task) to {
                val id = EditText(activity).apply { hint = "s20260831-..."; setSingleLine(true) }
                AlertDialog.Builder(activity).setTitle(R.string.v86_open_existing_task)
                    .setView(id).setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_continue) { _, _ ->
                        if (saveConnection()) v86Controller.openSealedSession(id.text.toString()) { result ->
                            if (result.isSuccess) activity.runOnUiThread {
                                v86OfflineProgressMessage = null
                                v86OfflineProgressError = null
                                renderV86Status()
                            }
                        }
                    }.show()
            },
        )
        // Flight-time information and actions stay above one-time connection/setup fields.
        // During an active task those fields are hidden completely, so the dialog is a
        // compact operational status panel rather than a developer configuration form.
        content.removeView(status)
        content.removeView(primaryRow)
        content.removeView(resultRow)
        content.removeView(closeRow)
        content.addView(status, 0)
        content.addView(primaryRow, 1)
        content.addView(resultRow, 2)
        content.addView(closeRow, 3)
        v86DialogSetupViews = setupViews
        val scroll = ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.v86_dialog_title)
            .setView(scroll)
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.setOnDismissListener {
            v86DialogStatus = null
            v86NewSessionButton = null
            v86FinalizeButton = null
            v86RetryUploadButton = null
            v86RetryProcessingButton = null
            v86ImportMissionButton = null
            v86CloseSessionButton = null
            v86DialogSetupViews = emptyList()
            scheduleV86Refresh(immediate = false)
        }
        dialog.show()
        dialog.window?.setLayout(dp(560), ViewGroup.LayoutParams.MATCH_PARENT)
        renderV86Status()
        scheduleV86Refresh(immediate = true)
    }

    private fun scheduleV86Refresh(immediate: Boolean) {
        v86RefreshHandler.removeCallbacks(v86RefreshRunnable)
        if (v86Snapshot.sessionId == null) return
        v86RefreshHandler.postDelayed(
            v86RefreshRunnable,
            if (immediate) 0L else V86_BACKGROUND_STREAMING_REFRESH_MILLIS,
        )
    }

    private fun renderV86Status() {
        val state = v86Snapshot
        val workflow = V86WorkflowPolicy.from(state)
        val workflowTitle = activity.resolve(workflow.title)
        val workflowDetail = activity.resolve(workflow.detail)
        binding.cloudReconstruction.text = "$workflowTitle\n$workflowDetail"
        binding.cloudReconstruction.alpha = if (workflow.needsAttention) 1f else 0.92f
        binding.openPointCloud.text = v86PlyProgressLabel ?: activity.resolve(workflow.plyButtonLabel)
        binding.openPointCloud.isEnabled = !v86PlyPreparing
        v86NewSessionButton?.visibility = if (state.sessionId == null || state.completed) View.VISIBLE else View.GONE
        v86FinalizeButton?.apply {
            visibility = if (state.sessionId != null && !state.sealed) View.VISIBLE else View.GONE
            isEnabled = workflow.canFinalize || workflow.canDiscardEmpty
            text = if (state.pendingCount > 0) {
                activity.getString(R.string.v86_pending_uploads, state.pendingCount)
            } else if (workflow.canDiscardEmpty) {
                activity.getString(R.string.v86_discard_empty)
            } else activity.getString(R.string.v86_end_capture)
        }
        v86RetryUploadButton?.visibility = if (state.pendingCount > 0 && state.lastError != null) View.VISIBLE else View.GONE
        v86RetryProcessingButton?.visibility = if (state.sealed && state.lastError != null) View.VISIBLE else View.GONE
        v86ImportMissionButton?.visibility = if (!state.missionUrl.isNullOrBlank()) View.VISIBLE else View.GONE
        // Termination is an explicit abort path and must remain available while
        // collecting, uploading, queued, processing, failed, or completed.
        v86CloseSessionButton?.visibility = if (state.sessionId != null) View.VISIBLE else View.GONE
        v86CloseSessionButton?.isEnabled = !state.busy
        val showSetup = state.sessionId == null || state.completed
        v86DialogSetupViews.forEach { it.visibility = if (showSetup) View.VISIBLE else View.GONE }
        v86DialogStatus?.text = buildString {
            if (state.relativeHeightTest) appendLine(activity.getString(R.string.v86_relative_test_warning))
            append(workflowTitle)
            append('\n')
            append(workflowDetail)
            append("\n\n")
            append(activity.getString(R.string.v86_session_phase, state.sessionId ?: "--", state.phase))
            if (state.busy) append(activity.getString(R.string.v86_busy_suffix))
            append('\n')
            append(activity.getString(R.string.v86_server_pending_status, state.imageCount, state.pendingCount, activity.resolve(state.message)))
            if (state.captureRejectedCount > 0) {
                append(activity.getString(R.string.v86_capture_rejected_count, state.captureRejectedCount))
                state.lastCaptureWarning?.let { append(" · $it") }
            }
            if (state.lastSuccessfulContactEpochMillis > 0L) {
                val ageSeconds = ((System.currentTimeMillis() - state.lastSuccessfulContactEpochMillis)
                    .coerceAtLeast(0L) / 1_000L)
                append(activity.getString(
                    R.string.v86_cloud_contact,
                    if (ageSeconds < 3) activity.getString(R.string.just_now) else activity.getString(R.string.seconds_ago, ageSeconds),
                ))
            }
            if (state.phase.equals("fast_sfm", ignoreCase = true) && state.fastSfmTargetImages > 0) {
                append('\n')
                append("Fast SfM ${state.fastSfmCompletedImages}/${state.fastSfmTargetImages}")
                if (state.fastSfmResumeFrom > 0) {
                    append(activity.getString(R.string.v86_fast_sfm_resumed_from, state.fastSfmResumeFrom))
                }
            }
            if (state.sessionId != null) {
                append('\n')
                append(activity.getString(R.string.v86_sfm_lane, state.sfmLanePhase))
                if (state.sfmLaneMessage.isNotBlank()) append(" · ${state.sfmLaneMessage}")
                append('\n')
                append(activity.getString(R.string.v86_scal3r_lane, state.scal3rLanePhase))
                if (state.scal3rLaneTargetWindows > 0) {
                    append(activity.getString(R.string.v86_scal3r_windows, state.scal3rLaneCompletedWindows, state.scal3rLaneTargetWindows))
                }
                if (state.scal3rLaneCacheHits > 0) append(activity.getString(R.string.v86_cache_hits, state.scal3rLaneCacheHits))
                if (state.scal3rLaneMessage.isNotBlank()) append(" · ${state.scal3rLaneMessage}")
            }
            if (!state.relativeHeightTest && (state.previewReady || state.completed)) {
                append('\n')
                append("V50 A ${state.detectorCounts.v50TierA} / B ${state.detectorCounts.v50TierB}")
                append(activity.getString(R.string.v86_v78_selected_status, state.detectorCounts.v78, state.detectorCounts.v78Selected))
                append(" · safe_to_execute=${state.safeToExecute}")
            }
            state.lastError?.let { append(activity.getString(R.string.error_detail_line, it)) }
            v86OfflineProgressMessage?.let { message ->
                append(activity.getString(R.string.v86_offline_replay_line, message))
                v86OfflineProgressError?.let { append(activity.getString(R.string.v86_offline_error_line, it)) }
            }
        }
    }

    private fun showV86OfflineProgress(message: String, error: String? = null) {
        v86OfflineProgressMessage = message
        v86OfflineProgressError = error
        activity.runOnUiThread(::renderV86Status)
    }

    private fun importV86Mission() {
        if (isMissionLockedForEditing()) return reject(activity.getString(R.string.survey_replace_locked))
        v86Controller.downloadMission { downloaded ->
        downloaded.onFailure { reject(activity.getString(R.string.v86_mission_download_failed, it.message.orEmpty())) }
            downloaded.onSuccess { raw ->
                if (activity.isFinishing || activity.isDestroyed || rejectEditingIfLocked()) return@onSuccess
                runCatching { decodeMissionValidated(raw) }
                    .onFailure { reject(activity.getString(R.string.v86_schema_parse_failed, it.message.orEmpty())) }
                    .onSuccess { imported ->
                        val counts = v86Snapshot.detectorCounts
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.v86_import_review_title)
                            .setMessage(activity.getString(
                                R.string.v86_import_review_message,
                                counts.v50TierA,
                                counts.v50TierB,
                                counts.v78Selected,
                            ))
                            .setNegativeButton(R.string.action_cancel, null)
                            .setPositiveButton(R.string.v86_import_review) { _, _ ->
                                if (activity.isFinishing || activity.isDestroyed || rejectEditingIfLocked()) return@setPositiveButton
                                settingsRestoring = true
                                applyMissionToFields(imported)
                                settingsRestoring = false
                                activateMission(
                                    imported,
                                    activity.getString(R.string.v86_mission_imported_review_required),
                                )
                                recordSurveyEvent(
                                    "v86_mission_imported",
                                    mapOf(
                                        "session_id" to v86Snapshot.sessionId,
                                        "waypoints" to imported.waypoints.size,
                                        "safe_to_execute" to false,
                                    ),
                                )
                            }
                            .show()
                    }
            }
        }
    }

    private fun startV86OfflineFolderPicker() {
        val state = v86Controller.current()
        if (state.sessionId == null) {
            v86Controller.showLocalMessage(
                activity.getString(R.string.v86_create_session_with_asl_first),
                activity.getString(R.string.v86_no_cloud_session),
            )
            return
        }
        if (state.sealed) {
            v86Controller.showLocalMessage(
                activity.getString(R.string.v86_sealed_create_new_for_replay),
                activity.getString(R.string.v86_task_sealed),
            )
            return
        }
        if (v86OfflineImporting) {
            showV86OfflineProgress(activity.getString(R.string.v86_offline_preparing_queue))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQUEST_V86_MEDIA_LOCATION)
            return
        }
        v86OfflineProgressMessage = null
        v86OfflineProgressError = null
        activity.startActivityForResult(openV86SfmFolder(), REQUEST_IMPORT_V86_SFM_TEST)
    }

    fun onMediaLocationPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_V86_MEDIA_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startV86OfflineFolderPicker()
        else showV86OfflineProgress(activity.getString(R.string.v86_photo_location_permission_required))
    }

    private fun openV86OriginalImage(uri: Uri): java.io.InputStream? {
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Convert SAF's document URI where supported, and explicitly require unredacted EXIF.
            val media = runCatching { MediaStore.getMediaUri(activity, uri) }.getOrNull()
            MediaStore.setRequireOriginal(media ?: uri)
        } else uri
        return activity.contentResolver.openInputStream(original)
    }

    private fun importV86OfflineFolder(treeUri: Uri) {
        if (v86OfflineImporting) return
        val state = v86Controller.current()
        val expectedSessionId = state.sessionId
        if (expectedSessionId == null || state.sealed) {
            v86Controller.showLocalMessage(
                activity.getString(R.string.v86_offline_replay_not_started),
                activity.getString(R.string.v86_create_unsealed_session_first),
            )
            return
        }
        runCatching {
            activity.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.edit().putString(KEY_LAST_V86_SFM_TREE_URI, treeUri.toString()).apply()
        v86OfflineImporting = true
        showV86OfflineProgress(activity.getString(R.string.v86_scanning_offline_folder))
        worker.execute {
            val result = runCatching {
                val filtered = listV86OfflineJpegs(treeUri).filter {
                    v86ReplayNameFilter.isBlank() || it.displayName.contains(v86ReplayNameFilter, ignoreCase = true)
                }
                val documents = if (v86ReplayMaximumImages > 0) filtered.take(v86ReplayMaximumImages) else filtered
                require(documents.isNotEmpty()) { activity.getString(R.string.v86_no_jpeg_in_folder) }
                val views = if (state.relativeHeightTest) V86ReplayCaptureViewCatalog.read(File(activity.filesDir, "flight_logs")) else emptyMap()
                fun captureView(name: String): String? = if (state.relativeHeightTest) {
                    requireNotNull(V86ReplayCaptureViewCatalog.keyForName(name)?.let(views::get)) {
                        activity.getString(R.string.v86_replay_view_missing, name)
                    }
                } else null
                val preflightIssues = mutableListOf<String>()
                var preflightIssueCount = 0
                documents.forEachIndexed { index, document ->
                    val inspection = runCatching {
                        captureView(document.displayName)
                        require(
                            document.sizeBytes <= V86OfflineImageCompressor.MAX_INPUT_BYTES ||
                                document.sizeBytes < 0L,
                        ) { activity.getString(R.string.v86_image_over_48_mib) }
                        openV86OriginalImage(document.uri)?.use { input ->
                            v86OfflineCompressor.validate(document.displayName, input, state.relativeHeightTest)
                        } ?: error(activity.getString(R.string.cannot_read))
                    }
                    inspection.exceptionOrNull()?.let { issue ->
                        preflightIssueCount += 1
                        if (preflightIssues.size < V86_OFFLINE_MAX_REPORTED_ISSUES) {
                            preflightIssues += "${document.relativePath}: ${issue.message}"
                        }
                    }
                    if (index == 0 || (index + 1) % 10 == 0 || index == documents.lastIndex) {
                        showV86OfflineProgress(activity.getString(
                            R.string.v86_exif_preflight_progress,
                            index + 1,
                            documents.size,
                            preflightIssueCount,
                        ))
                    }
                }
                require(preflightIssueCount == 0) {
                    activity.getString(
                        R.string.v86_exif_preflight_failed,
                        preflightIssueCount,
                        preflightIssues.joinToString(activity.getString(R.string.list_separator)),
                    )
                }
                var originalBytes = 0L
                var compressedBytes = 0L
                documents.forEachIndexed { index, document ->
                    require(v86Controller.current().sessionId == expectedSessionId) {
                        activity.getString(R.string.v86_session_changed_stop_replay)
                    }
                    waitForV86OfflineQueueRoom()
                    val prepared = openV86OriginalImage(document.uri)?.use { input ->
                        v86OfflineCompressor.prepare(document.displayName, input, state.relativeHeightTest,
                            captureView(document.displayName))
                    } ?: error(activity.getString(R.string.cannot_read_file, document.displayName))
                    originalBytes += prepared.originalBytes
                    compressedBytes += prepared.jpeg.size
                    v86Controller.enqueueOfflineImage(prepared).getOrThrow()
                    recordSurveyEvent(
                        "v86_offline_image_queued",
                        mapOf(
                            "session_id" to v86Controller.current().sessionId,
                            "index" to index,
                            "total" to documents.size,
                            "display_name" to prepared.displayName,
                            "source_path" to document.relativePath,
                            "original_bytes" to prepared.originalBytes,
                            "compressed_bytes" to prepared.jpeg.size,
                            "width" to prepared.width,
                            "height" to prepared.height,
                            "gps_source" to "image_exif",
                            "capture_view" to prepared.captureView,
                        ),
                    )
                    showV86OfflineProgress(activity.getString(
                        R.string.v86_simulated_stream_progress,
                        index + 1,
                        documents.size,
                        prepared.jpeg.size / 1024,
                    ))
                    Thread.sleep(V86_OFFLINE_STREAM_INTERVAL_MILLIS)
                }
                Triple(documents.size, originalBytes, compressedBytes)
            }
            activity.runOnUiThread {
                v86OfflineImporting = false
                result.onSuccess { (count, originalBytes, compressedBytes) ->
                    showV86OfflineProgress(activity.getString(
                        R.string.v86_offline_all_queued,
                        count,
                        originalBytes / 1024 / 1024,
                        compressedBytes / 1024 / 1024,
                    ))
                }.onFailure { error ->
                    showV86OfflineProgress(activity.getString(R.string.v86_offline_replay_stopped_uploads_continue), error.message)
                    recordSurveyEvent(
                        "v86_offline_replay_failed",
                        mapOf("session_id" to v86Controller.current().sessionId, "error" to error.message),
                    )
                }
            }
        }
    }

    private fun waitForV86OfflineQueueRoom() {
        val started = SystemClock.elapsedRealtime()
        while (v86Controller.current().pendingCount >= V86_OFFLINE_MAX_PENDING) {
            val state = v86Controller.current()
            require(!state.sealed) { activity.getString(R.string.v86_upload_ended) }
            state.lastError?.let { error(activity.getString(R.string.v86_upload_queue_paused, it)) }
            require(SystemClock.elapsedRealtime() - started < V86_OFFLINE_QUEUE_WAIT_TIMEOUT_MILLIS) {
                activity.getString(R.string.v86_queue_room_timeout)
            }
            Thread.sleep(V86_OFFLINE_QUEUE_POLL_MILLIS)
        }
    }

    private fun listV86OfflineJpegs(treeUri: Uri): List<V86OfflineDocument> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val rows = mutableListOf<V86OfflineDocument>()
        val visitedDirectories = mutableSetOf<String>()
        fun walk(documentId: String, parentPath: String) {
            require(visitedDirectories.add(documentId)) { activity.getString(R.string.v86_folder_cycle, parentPath) }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val childDirectories = mutableListOf<Pair<String, String>>()
            activity.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val relativePath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        childDirectories += childId to relativePath
                    } else if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) {
                        rows += V86OfflineDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                            displayName = name,
                            sizeBytes = if (cursor.isNull(sizeIndex)) -1L else cursor.getLong(sizeIndex),
                            relativePath = relativePath,
                        )
                    }
                }
            }
            childDirectories
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
                .forEach { (childId, relativePath) -> walk(childId, relativePath) }
        }
        walk(treeDocumentId, "")
        return rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
    }

    private fun handleV86PointCloudAction() {
        if (v86PlyPreparing) return
        val workflow = V86WorkflowPolicy.from(v86Snapshot)
        if (!workflow.plyReady) {
            v86PlyProgressLabel = activity.getString(R.string.v86_ply_checking)
            renderV86Status()
            v86Controller.refresh { result ->
                result.onFailure {
                    v86PlyProgressLabel = activity.getString(R.string.v86_ply_check_failed)
                    operationMessage = activity.getString(R.string.ply_status_check_failed, it.message.orEmpty())
                }
                result.onSuccess {
                    val refreshed = V86WorkflowPolicy.from(v86Controller.current())
                    if (refreshed.plyReady) {
                        prepareAndOpenV86PointCloud()
                    } else {
                        v86PlyProgressLabel = null
                        operationMessage = "${activity.resolve(refreshed.title)} · ${activity.resolve(refreshed.detail)}"
                    }
                }
                renderV86Status()
                renderStatus()
            }
            return
        }
        prepareAndOpenV86PointCloud()
    }

    private fun prepareAndOpenV86PointCloud() {
        val cacheSnapshot = v86Snapshot
        val sessionId = cacheSnapshot.sessionId ?: return
        val cacheRoot = File(activity.filesDir, "v86-point-cloud").apply { mkdirs() }
        val cacheStem = V86PointCloudCachePolicy.fileStem(cacheSnapshot)
        val ply = File(cacheRoot, "$cacheStem.ply")
        val candidates = File(cacheRoot, "$cacheStem-candidates.json")
        if (ply.isFile && ply.length() >= 64L) {
            if (!candidates.isFile) candidates.writeText("{\"candidates\":[]}")
            launchV86PointCloud(ply, candidates)
            return
        }
        v86PlyPreparing = true
        v86PlyProgressLabel = activity.getString(R.string.v86_candidate_data_loading)
        renderV86Status()
        v86Controller.downloadViewerData { viewerResult ->
            worker.execute {
                runCatching {
                    candidates.writeText(viewerResult.getOrElse { "{\"candidates\":[]}" })
                }
                activity.runOnUiThread {
                    v86Controller.downloadPointCloudTo(
                        target = ply,
                        onProgress = { downloaded, total ->
                            v86PlyProgressLabel = if (total != null && total > 0L) {
                                "PLY ${(downloaded * 100L / total).coerceIn(0L, 100L)}%"
                            } else {
                                "PLY ${formatV86Bytes(downloaded)}"
                            }
                            renderV86Status()
                        },
                    ) { result ->
                        v86PlyPreparing = false
                        result.onFailure {
                            v86PlyProgressLabel = activity.getString(R.string.v86_ply_download_failed_label)
                            operationMessage = activity.getString(R.string.ply_download_failed, it.message.orEmpty())
                            renderStatus()
                        }
                        result.onSuccess { file ->
                            v86PlyProgressLabel = null
                            recordSurveyEvent(
                                "v86_ply_ready_on_device",
                                mapOf("session_id" to sessionId, "bytes" to file.length()),
                            )
                            launchV86PointCloud(file, candidates)
                        }
                        renderV86Status()
                    }
                }
            }
        }
    }

    private fun launchV86PointCloud(ply: File, candidates: File) {
        activity.startActivity(
            Intent(activity, V86PointCloudActivity::class.java)
                .putExtra(V86PointCloudActivity.EXTRA_PLY_PATH, ply.absolutePath)
                .putExtra(V86PointCloudActivity.EXTRA_CANDIDATES_PATH, candidates.absolutePath),
        )
    }

    private fun clearV86PointCloudCache(sessionId: String?) {
        if (sessionId == null || !sessionId.matches(Regex("[a-z0-9][a-z0-9_-]{5,63}"))) return
        File(activity.filesDir, "v86-point-cloud").listFiles()
            ?.filter { it.name.startsWith("$sessionId-") || it.name == "$sessionId.ply" }
            ?.forEach(File::delete)
    }

    private fun formatV86Bytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun setMapThreeDimensional(enabled: Boolean) {
        if (!mapEditor.setThreeDimensional(enabled)) {
            return reject(activity.getString(R.string.map_not_ready_for_view_switch))
        }
        mapThreeDimensional = enabled
        operationMessage = if (enabled) {
            activity.getString(R.string.map_3d_visual_review_only)
        } else {
            activity.getString(R.string.map_switched_to_2d_nadir)
        }
        renderStatus()
    }

    private fun runGimbalDiagnostic(targetPitchDegrees: Double) {
        if (isMissionLockedForEditing()) return reject(activity.getString(R.string.stop_route_before_gimbal_diagnostic))
        if (!snapshot().connected) return reject(activity.getString(R.string.gimbal_diagnostic_aircraft_disconnected))
        val cameraIndex = cameraDiscovery.current().index
        if (cameraIndex == dji.sdk.keyvalue.value.common.ComponentIndexType.UNKNOWN) {
            return reject(activity.getString(R.string.gimbal_diagnostic_index_missing))
        }
        diagnosticGimbal?.stop()
        val port = DjiV5GimbalPort(cameraIndex, activity).also { diagnosticGimbal = it }
        port.start { }
        operationMessage = activity.getString(R.string.gimbal_diagnostic_sending, targetPitchDegrees.toInt())
        renderStatus()
        port.rotateToPitch(targetPitchDegrees, 1.0) { result ->
            activity.runOnUiThread {
                operationMessage = result.fold(
                    { activity.getString(R.string.gimbal_diagnostic_accepted, targetPitchDegrees.toInt()) },
                    { activity.getString(R.string.gimbal_diagnostic_failed, it.message ?: it.javaClass.simpleName) },
                )
                renderStatus()
                persistenceHandler.postDelayed({
                    if (diagnosticGimbal === port) {
                        port.stop()
                        diagnosticGimbal = null
                    }
                }, 5_000L)
            }
        }
    }

    private fun showExecutionStatusDetails() {
        val current = mission
        val detail = buildString {
            appendLine(binding.surveyExecutionStatus.text)
            operationMessage?.let { appendLine(it) }
            if (current != null) appendLine(summary(current))
            appendLine(activity.getString(R.string.dji_execution_detail, waylineState.phase, activity.resolve(waylineState.message)))
            append(activity.getString(R.string.custom_execution_detail, customState.status.state, customState.message))
        }.trim()
        AlertDialog.Builder(activity)
            .setTitle(R.string.survey_execution_status_title)
            .setMessage(detail.ifBlank { activity.getString(R.string.survey_not_preflighted) })
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    private fun showObliqueAngleDialog() {
        val labels = arrayOf(
            activity.getString(R.string.angle_degrees, -30),
            activity.getString(R.string.angle_degrees_recommended, -45),
            activity.getString(R.string.angle_degrees, -60),
            activity.getString(R.string.angle_degrees, -75),
        )
        val values = doubleArrayOf(-30.0, -45.0, -60.0, -75.0)
        val current = binding.obliquePitch.text.toString().toDoubleOrNull() ?: -45.0
        val checked = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 1
        AlertDialog.Builder(activity)
            .setTitle(R.string.oblique_pitch_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                binding.obliquePitch.setText(values[which].toInt().toString())
                binding.obliqueAngle.text = activity.getString(
                    R.string.oblique_pitch_value,
                    values[which].toInt().toString(),
                )
                if (mission != null) invalidateGeneratedMission(activity.getString(R.string.oblique_pitch_changed_regenerate))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toggleReplay() {
        val currentMission = mission ?: return reject(activity.getString(R.string.generate_or_import_before_replay))
        val currentReplay = replay?.takeIf { it.mission.id == currentMission.id }
            ?: SurveyMissionReplay(currentMission).also { replay = it }
        val state = when (currentReplay.state) {
            SurveyReplayState.RUNNING -> currentReplay.pause()
            SurveyReplayState.PAUSED -> currentReplay.resume()
            SurveyReplayState.IDLE, SurveyReplayState.COMPLETED -> currentReplay.start()
        }
        replayHandler.removeCallbacks(replayTick)
        mapEditor.renderReplayPoint(state.point, state.headingDegrees, state.captureActive)
        binding.previewSurvey.text = when (state.state) {
            SurveyReplayState.RUNNING -> activity.getString(R.string.survey_replay_pause)
            SurveyReplayState.PAUSED -> activity.getString(R.string.survey_replay_resume)
            else -> activity.getString(R.string.survey_preview)
        }
        operationMessage = activity.getString(R.string.survey_replay_read_only, state.sampleIndex + 1, state.totalSamples)
        renderStatus()
        if (state.state == SurveyReplayState.RUNNING) replayHandler.postDelayed(replayTick, REPLAY_TICK_MILLIS)
    }

    private fun stopReplay(clearMarker: Boolean) {
        replayHandler.removeCallbacks(replayTick)
        val hadReplay = replay != null
        replay?.stop()
        replay = null
        binding.previewSurvey.setText(R.string.survey_preview)
        if (clearMarker) mapEditor.renderReplayPoint(null)
        if (hadReplay && mission != null) {
            operationMessage = activity.getString(R.string.survey_replay_stopped)
            renderStatus()
        }
    }

    private fun selectTab(tab: SurveyTab) = with(binding) {
        val availableTab = if (tab == SurveyTab.TERRAIN && !SurveyFeatureAvailability.TERRAIN_FOLLOWING_ENABLED) {
            SurveyTab.AREA
        } else tab
        selectedTab = availableTab
        schedulePersistPlannerSettings()
        sectionArea.visibility = if (availableTab == SurveyTab.AREA) View.VISIBLE else View.GONE
        sectionFlight.visibility = if (availableTab == SurveyTab.FLIGHT) View.VISIBLE else View.GONE
        sectionCapture.visibility = if (availableTab == SurveyTab.CAPTURE) View.VISIBLE else View.GONE
        sectionTerrain.visibility = View.GONE
        editActionsRow.visibility = if (availableTab == SurveyTab.AREA) View.VISIBLE else View.GONE
        generateActionsRow.visibility = if (availableTab == SurveyTab.CAPTURE) View.VISIBLE else View.GONE
        captureViewSelector.visibility = if (availableTab == SurveyTab.CAPTURE) View.VISIBLE else View.GONE
        tabTerrain.setText(if (followTerrain.isChecked) R.string.terrain_on else R.string.terrain_settings)
        listOf(tabArea, tabFlight, tabCapture, tabTerrain).forEach {
            styleSurveyButton(it, 0xFFF3F5F7.toInt(), 0xFFD8DDE3.toInt(), 0xFF56606B.toInt())
        }
        when (availableTab) {
            SurveyTab.AREA -> tabArea
            SurveyTab.FLIGHT -> tabFlight
            SurveyTab.CAPTURE -> tabCapture
            SurveyTab.TERRAIN -> tabTerrain
        }.let { styleSurveyButton(it, 0xFF1F6FB2.toInt(), 0xFF155B96.toInt(), 0xFFFFFFFF.toInt()) }
        if (availableTab == SurveyTab.TERRAIN && terrain == null && mission == null) {
            operationMessage = if (editedRoi.size < 3) {
                activity.getString(R.string.terrain_step_draw_boundary)
            } else {
                activity.getString(R.string.terrain_step_load_elevation)
            }
            renderStatus()
        }
        surveyPlannerScroll.post { surveyPlannerScroll.scrollTo(0, 0) }
    }

    private fun updateCaptureViewActions() = with(binding) {
        val count = listOf(viewNadir, viewForward, viewBackward, viewLeft, viewRight).count { it.isChecked }
        generateFiveDirection.text = activity.getString(R.string.generate_selected_groups, count)
        generateFiveDirection.isEnabled = editedRoi.size >= 3 && count > 0 && !isMissionLockedForEditing()
    }

    private fun onCaptureViewChanged(checkbox: CheckBox, checked: Boolean) {
        if (captureSelectionChanging) return
        if (settingsRestoring) {
            checkbox.setTextColor(if (checked) 0xFFFFFFFF.toInt() else 0xFF56606B.toInt())
            updateCaptureViewActions()
            return
        }
        if (isMissionLockedForEditing()) {
            captureSelectionChanging = true
            checkbox.isChecked = !checked
            captureSelectionChanging = false
            checkbox.setTextColor(if (checkbox.isChecked) 0xFFFFFFFF.toInt() else 0xFF56606B.toInt())
            return reject(activity.getString(R.string.survey_stop_before_group_edit))
        }
        if (mission?.activeMapping != null) {
            captureSelectionChanging = true
            checkbox.isChecked = !checked
            captureSelectionChanging = false
            checkbox.setTextColor(if (checkbox.isChecked) 0xFFFFFFFF.toInt() else 0xFF56606B.toInt())
            return reject(activity.getString(R.string.active_recapture_groups_fixed))
        }
        if (!checked && selectedCaptureViews().isEmpty()) {
            captureSelectionChanging = true
            checkbox.isChecked = true
            captureSelectionChanging = false
            checkbox.setTextColor(0xFFFFFFFF.toInt())
            updateCaptureViewActions()
            return reject(activity.getString(R.string.keep_one_route_group))
        }
        checkbox.setTextColor(if (checked) 0xFFFFFFFF.toInt() else 0xFF56606B.toInt())
        updateCaptureViewActions()
        schedulePersistPlannerSettings()
        val current = mission
        if (current?.constraints?.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION) {
            val selected = selectedCaptureViews()
            val source = captureSelectionSourceMission
                ?.takeIf { it.constraints.enabledCaptureViews.containsAll(selected) }
                ?: current.also { captureSelectionSourceMission = it }
            runCatching { SurveyMissionCaptureViewFilter.select(source, selected, activity) }
                .onSuccess {
                    activateMission(
                        it,
                        activity.getString(R.string.selected_route_groups_generated, selected.size),
                        preserveCaptureSource = true,
                    )
                }
            .onFailure { reject(activity.getString(R.string.apply_selected_groups_failed, it.message.orEmpty())) }
            return
        }
        operationMessage = activity.getString(R.string.five_direction_groups_selected, captureViewSelectionLabel())
        renderStatus()
    }

    private fun captureViewSelectionLabel(): String = buildList {
        if (binding.viewNadir.isChecked) add(activity.getString(R.string.capture_view_short_nadir))
        if (binding.viewForward.isChecked) add(activity.getString(R.string.capture_view_short_forward))
        if (binding.viewBackward.isChecked) add(activity.getString(R.string.capture_view_short_backward))
        if (binding.viewLeft.isChecked) add(activity.getString(R.string.capture_view_short_left))
        if (binding.viewRight.isChecked) add(activity.getString(R.string.capture_view_short_right))
    }.joinToString("+").ifBlank { activity.getString(R.string.none_selected) }

    private fun installTerrainFollowListener() {
        binding.followTerrain.setOnCheckedChangeListener { _, checked ->
            if (settingsRestoring) return@setOnCheckedChangeListener
            if (isMissionLockedForEditing()) {
                binding.followTerrain.setOnCheckedChangeListener(null)
                binding.followTerrain.isChecked = !checked
                installTerrainFollowListener()
            reject(activity.getString(R.string.survey_stop_before_terrain_switch))
                return@setOnCheckedChangeListener
            }
            if (mission != null) invalidateGeneratedMission(
                if (checked) activity.getString(R.string.terrain_follow_selected_regenerate)
                else activity.getString(R.string.fixed_altitude_selected_regenerate),
            )
        binding.tabTerrain.setText(if (checked) R.string.terrain_on else R.string.terrain_settings)
            if (checked) {
                terrainPreview?.let(::showTerrainPreview)
            } else {
                binding.surveyPreview.clearTerrain()
                binding.terrainAltitudeLegend.clearRange()
            }
            schedulePersistPlannerSettings()
            renderTerrainStatus()
            renderStatus()
        }
    }

    private fun applyCockpitStyle() = with(binding) {
        listOf(
            importDsm, importMission, importCheckpoint, downloadTerrainDem, downloadBuildingDsm,
            centerSurvey, setRthHeight, obliqueAngle, surveyHeaderPause,
            exportSurveyArtifacts, uploadWayline, pauseWayline, resumeWayline, preflightSurvey,
            exportKmz, previewSurvey, undoSurveyVertex, deleteSurveyVertex, clearSurveyVertices,
        ).forEach { button ->
            styleSurveyButton(button, 0xFFF3F5F7.toInt(), 0xFFD8DDE3.toInt(), 0xFF343A40.toInt())
            button.textSize = 9f
            button.minHeight = 0
        }
        listOf(generateSurvey, generateFiveDirection, executeWayline).forEach { button ->
            styleSurveyButton(button, 0xFF3478C6.toInt(), 0xFF2768B2.toInt(), 0xFFFFFFFF.toInt())
            button.textSize = 9f
            button.minHeight = 0
        }
        styleSurveyButton(stopWayline, 0xFFFDECEC.toInt(), 0xFFE49A9A.toInt(), 0xFFB3261E.toInt())
        styleSurveyButton(surveyHeaderAbort, 0xFFFDECEC.toInt(), 0xFFE49A9A.toInt(), 0xFFB3261E.toInt())
        stopWayline.textSize = 9f
        styleSurveyButton(surveyMore, 0xFFF3F5F7.toInt(), 0xFFD8DDE3.toInt(), 0xFF343A40.toInt())
        styleSurveyButton(closeSurvey, 0xFFFFFFFF.toInt(), 0x00FFFFFF, 0xFF555D66.toInt(), 18)
        listOf(ueEndpoint, buildingCogTemplate).forEach { field ->
            field.setBackgroundResource(edu.playground.djivln.R.drawable.bg_survey_field)
            field.setPadding(dp(7), 0, dp(7), 0)
            field.setTextColor(0xFF20242A.toInt())
            field.setHintTextColor(0xFF9AA1AA.toInt())
            field.textSize = 10f
        }
        listOf(executionBackend, altitudeMode, takeoffMode, startMode, completionAction, captureMode, photoRatio,
            obliqueHeadingMode).forEach {
            it.setBackgroundResource(edu.playground.djivln.R.drawable.bg_survey_field)
        }
    }

    private fun styleSurveyButton(
        button: Button,
        fill: Int,
        stroke: Int,
        text: Int,
        radiusDp: Int = 5,
    ) {
        button.background = GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }
        button.setTextColor(text)
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(4), 0, dp(4), 0)
    }

    private fun applyLightPlannerStyle(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            when (child) {
                is EditText -> {
                    child.setBackgroundResource(edu.playground.djivln.R.drawable.bg_survey_field)
                    child.setTextColor(0xFF20242A.toInt())
                    child.setHintTextColor(0xFF9AA1AA.toInt())
                }
                is CheckBox -> child.setTextColor(0xFF20242A.toInt())
                is Spinner -> child.setBackgroundResource(edu.playground.djivln.R.drawable.bg_survey_field)
                is TextView -> if (child !is android.widget.Button) child.setTextColor(0xFF5A626D.toInt())
            }
            if (child is ViewGroup) applyLightPlannerStyle(child)
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private var linkingGsdAndAltitude = false

    private fun setupPlanningOptions() = with(binding) {
        fun Spinner.options(labels: List<String>) {
            adapter = ArrayAdapter(
                activity,
                edu.playground.djivln.R.layout.item_survey_spinner,
                labels,
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        altitudeMode.options(listOf(activity.getString(R.string.altitude_above_target_surface), activity.getString(R.string.altitude_relative_to_takeoff)))
        takeoffMode.options(listOf(activity.getString(R.string.takeoff_manual_default), activity.getString(R.string.takeoff_auto_simulator_only)))
        startMode.options(listOf(activity.getString(R.string.start_nearest_auto)) + (1..4).map { activity.getString(R.string.corner_number, it) })
        completionAction.options(listOf(activity.getString(R.string.action_return_home), activity.getString(R.string.action_hover), activity.getString(R.string.return_to_route_start)))
        captureMode.options(listOf(activity.getString(R.string.capture_distance_interval), activity.getString(R.string.capture_timed)))
        photoRatio.options(listOf("16:9", activity.getString(R.string.photo_ratio_full_frame_default)))
        photoRatio.setSelection(PHOTO_RATIO_4_BY_3, false)
        obliqueHeadingMode.options(listOf(activity.getString(R.string.heading_along_track), activity.getString(R.string.heading_fixed_observation)))
        captureMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val visibility = if (position == SurveyCaptureTriggerMode.TIME.ordinal) {
                    View.VISIBLE
                } else View.GONE
                timedCaptureRow.visibility = visibility
                schedulePersistPlannerSettings()
                updateSpeedLimitHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        photoRatio.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                schedulePersistPlannerSettings()
                updateGsdFromAltitude()
                updateSpeedLimitHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupGsdAltitudeLink() = with(binding) {
        flightAltitude.addTextChangedListener(simpleTextWatcher { updateGsdFromAltitude() })
        flightGsd.addTextChangedListener(simpleTextWatcher { updateAltitudeFromGsd() })
        updateGsdFromAltitude()
    }

    private fun updateGsdFromAltitude() {
        if (linkingGsdAndAltitude) return
        val altitude = binding.flightAltitude.text.toString().toDoubleOrNull() ?: return
        val camera = selectedPlanningCameraProfile()
        val footprintMeters = 2.0 * altitude * kotlin.math.tan(
            Math.toRadians(camera.horizontalFieldOfViewDegrees / 2.0),
        )
        linkingGsdAndAltitude = true
        binding.flightGsd.setText("%.2f".format(footprintMeters / camera.imageWidthPixels * 100.0))
        linkingGsdAndAltitude = false
    }

    private fun updateAltitudeFromGsd() {
        if (linkingGsdAndAltitude) return
        val gsd = binding.flightGsd.text.toString().toDoubleOrNull() ?: return
        val altitude = SurveyPlanner.altitudeForGroundSampleDistance(selectedPlanningCameraProfile(), gsd)
        linkingGsdAndAltitude = true
        binding.flightAltitude.setText("%.1f".format(altitude))
        linkingGsdAndAltitude = false
    }

    private fun startPointMode(): SurveyStartPointMode = when (binding.startMode.selectedItemPosition) {
        1 -> SurveyStartPointMode.FIRST_ROUTE_START
        2 -> SurveyStartPointMode.ROUTE_CORNER_2
        3 -> SurveyStartPointMode.ROUTE_CORNER_3
        4 -> SurveyStartPointMode.ROUTE_CORNER_4
        else -> SurveyStartPointMode.AUTO_NEAREST
    }

    private fun selectedCaptureViews(): Set<SurveyCaptureView> = buildSet {
        if (binding.viewNadir.isChecked) add(SurveyCaptureView.NADIR)
        if (binding.viewForward.isChecked) add(SurveyCaptureView.FORWARD_OBLIQUE)
        if (binding.viewBackward.isChecked) add(SurveyCaptureView.BACKWARD_OBLIQUE)
        if (binding.viewLeft.isChecked) add(SurveyCaptureView.LEFT_OBLIQUE)
        if (binding.viewRight.isChecked) add(SurveyCaptureView.RIGHT_OBLIQUE)
    }

    private fun applyMissionToFields(imported: SurveyMission) = with(binding) {
        val constraints = imported.constraints
        surveyName.setText(imported.name)
        flightAltitude.setText("%.1f".format(constraints.altitudeMetersAgl))
        flightSpeed.setText("%.1f".format(constraints.speedMetersPerSecond))
        obliqueSpeed.setText("%.1f".format(constraints.obliqueSpeedMetersPerSecond))
        takeoffSpeed.setText("%.1f".format(constraints.takeoffSpeedMetersPerSecond))
        descentSpeed.setText("%.1f".format(constraints.descentSpeedMetersPerSecond))
        routeHeading.setText("%.1f".format(constraints.routeHeadingDegrees))
        safeTakeoffAltitude.setText("%.1f".format(constraints.safeTakeoffAltitudeMeters))
        targetSurfaceOffset.setText("%.1f".format(constraints.targetSurfaceToTakeoffMeters))
        forwardOverlap.setText("%.0f".format(constraints.forwardOverlap * 100.0))
        sideOverlap.setText("%.0f".format(constraints.sideOverlap * 100.0))
        obliqueForwardOverlap.setText("%.0f".format(constraints.obliqueForwardOverlap * 100.0))
        obliqueSideOverlap.setText("%.0f".format(constraints.obliqueSideOverlap * 100.0))
        boundaryMargin.setText("%.1f".format(constraints.boundaryMarginMeters))
        obliquePitch.setText("%.0f".format(constraints.obliqueGimbalPitchDegrees))
        timedCaptureInterval.setText("%.1f".format(constraints.timedCaptureIntervalSeconds))
        altitudeMode.setSelection(constraints.altitudeMode.ordinal)
        takeoffMode.setSelection(constraints.takeoffMode.ordinal)
        startMode.setSelection(when (constraints.startPointMode) {
            SurveyStartPointMode.FIRST_ROUTE_START -> 1
            SurveyStartPointMode.ROUTE_CORNER_2 -> 2
            SurveyStartPointMode.ROUTE_CORNER_3 -> 3
            SurveyStartPointMode.ROUTE_CORNER_4 -> 4
            else -> 0
        })
        completionAction.setSelection(constraints.completionAction.ordinal)
        captureMode.setSelection(constraints.captureTriggerMode.ordinal)
        obliqueHeadingMode.setSelection(constraints.obliqueHeadingMode.ordinal)
        viewNadir.isChecked = SurveyCaptureView.NADIR in constraints.enabledCaptureViews
        viewForward.isChecked = SurveyCaptureView.FORWARD_OBLIQUE in constraints.enabledCaptureViews
        viewBackward.isChecked = SurveyCaptureView.BACKWARD_OBLIQUE in constraints.enabledCaptureViews
        viewLeft.isChecked = SurveyCaptureView.LEFT_OBLIQUE in constraints.enabledCaptureViews
        viewRight.isChecked = SurveyCaptureView.RIGHT_OBLIQUE in constraints.enabledCaptureViews
        imported.terrainPlan?.sourceKind?.let { sourceKind ->
            terrainKind.setSelection(
                if (sourceKind == SurveyTerrainSourceKind.BARE_EARTH) {
                    TERRAIN_KIND_BARE_EARTH
                } else {
                    TERRAIN_KIND_SURFACE_DSM
                },
            )
        }
        followTerrain.isChecked = false
        updateGsdFromAltitude()
    }

    private fun activateMission(
        activated: SurveyMission,
        message: String,
        persist: Boolean = true,
        preserveCaptureSource: Boolean = false,
        preserveActiveRecaptureSource: Boolean = false,
    ) {
        if (!SurveyFeatureAvailability.supportsMission(activated)) {
            reject(activity.getString(R.string.terrain_mission_rejected_when_disabled))
            return
        }
        stopReplay(clearMarker = true)
        pendingCheckpointRestore = null
        restoredDjiCheckpoint = null
        activeDjiRecoveryDisplayCheckpoint = null
        mission = activated
        djiMissedCapturePasses.clear()
        editedRoi = activated.roi
        selectedVertexIndex = null
        kmzFile = null
        kmzMissionId = null
        kmzRthHeightMeters = null
        kmzPayloadPositionIndex = null
        kmzPayloadLensName = null
        kmzExecutionSpeedMetersPerSecond = null
        kmzObliqueSpeedMetersPerSecond = null
        kmzTakeoffSpeedMetersPerSecond = null
        uploadedKmzSessionSignature = null
        cancelPreparingDjiExecution(activity.getString(R.string.switching_mission), announce = false)
        if (!preserveCaptureSource) {
            captureSelectionSourceMission = activated.takeIf {
                it.constraints.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION &&
                    it.constraints.enabledCaptureViews == STANDARD_SURVEY_CAPTURE_VIEWS
            }
        }
        if (!preserveActiveRecaptureSource || activeRecaptureSourceMission == null) {
            activeRecaptureSourceMission = activated.takeIf { it.activeMapping != null }
        }
        binding.surveyPreview.showMission(activated)
        binding.surveyPreview.editRoi(activated.roi, ::onRoiEdited)
        renderEditableVertices()
        onMissionChanged(activated)
        renderTerrainAltitudeLegend()
        operationMessage = message
        if (persist) persistMission(resetCheckpoint = true)
        renderStatus()
    }

    private fun persistMission(resetCheckpoint: Boolean) {
        val current = mission ?: return
        val editor = preferences.edit().putString(KEY_MISSION, SurveyMissionJson.encode(current))
        activeRecaptureSourceMission?.let {
            editor.putString(KEY_ACTIVE_RECAPTURE_SOURCE_MISSION, SurveyMissionJson.encode(it))
        } ?: editor.remove(KEY_ACTIVE_RECAPTURE_SOURCE_MISSION)
        editor.apply()
        if (resetCheckpoint) {
            clearCheckpointStorage(commit = false)
            lastCheckpointSignature = null
            lastDjiCheckpointSignature = null
            activeDjiRecoveryDisplayCheckpoint = null
        }
    }

    private fun clearPersistedSurveySession() {
        preferences.edit()
            .remove(KEY_MISSION)
            .remove(KEY_ACTIVE_RECAPTURE_SOURCE_MISSION)
            .apply()
        clearCheckpointStorage(commit = false)
        lastCheckpointSignature = null
        lastDjiCheckpointSignature = null
        activeDjiRecoveryDisplayCheckpoint = null
    }

    private fun clearPersistedCheckpoint() {
        clearCheckpointStorage(commit = true)
        lastCheckpointSignature = null
        lastDjiCheckpointSignature = null
        activeDjiRecoveryDisplayCheckpoint = null
    }

    private fun persistCheckpoint(checkpoint: SurveyExecutionCheckpoint, commit: Boolean): Boolean {
        val editor = checkpointPreferences.edit()
            .putString(KEY_CHECKPOINT, SurveyExecutionCheckpointJson.encode(checkpoint))
        return if (commit) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun clearCheckpointStorage(commit: Boolean) {
        val editor = checkpointPreferences.edit().remove(KEY_CHECKPOINT)
        if (commit) editor.commit() else editor.apply()
        if (preferences.contains(KEY_CHECKPOINT)) preferences.edit().remove(KEY_CHECKPOINT).apply()
    }

    private fun readPersistedCheckpoint(): String? {
        checkpointPreferences.getString(KEY_CHECKPOINT, null)?.let { return it }
        val legacy = preferences.getString(KEY_CHECKPOINT, null) ?: return null
        // One-time migration from the old large SharedPreferences file.
        checkpointPreferences.edit().putString(KEY_CHECKPOINT, legacy).commit()
        preferences.edit().remove(KEY_CHECKPOINT).apply()
        return legacy
    }

    private fun persistCustomCheckpoint(state: SurveyCustomExecutionSnapshot) {
        if (mission == null) return
        val active = state.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
            edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
        )
        if (!active) {
            if (lastCheckpointSignature != null) {
                clearCheckpointStorage(commit = false)
                lastCheckpointSignature = null
            }
            return
        }
        val checkpoint = customExecution.checkpoint() ?: return
        val signature = "${checkpoint.state}:${checkpoint.waypointIndex}:${checkpoint.executionLegIndex}:${checkpoint.phase}"
        if (signature == lastCheckpointSignature) return
        lastCheckpointSignature = signature
        persistCheckpoint(checkpoint, commit = false)
    }

    private fun persistDjiCheckpoint(state: WaylineState) {
        val current = mission ?: return
        val breakpoint = state.breakpoint
        val globalWaypointIndex = djiGlobalWaypointIndex(
            current,
            state.waylineId ?: breakpoint?.waylineId,
            state.waypointIndex ?: breakpoint?.waypointId,
        )
        if (state.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED) {
            val completedNormally = globalWaypointIndex == current.waypoints.lastIndex &&
                restoredDjiCheckpoint == null
            if (completedNormally) {
                clearCheckpointStorage(commit = false)
                lastDjiCheckpointSignature = null
                activeDjiRecoveryDisplayCheckpoint = null
            }
            return
        }
        if (state.phase !in setOf(
                edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
                edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
                edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
            )
        ) return
        val checkpoint = SurveyWaylineExport.checkpoint(
            current,
            state.copy(waypointIndex = globalWaypointIndex),
            System.currentTimeMillis(),
        ) ?: return
        val persistedBreakpoint = checkpoint.djiBreakpoint ?: return
        val signature = "DJI:${checkpoint.state}:${persistedBreakpoint.waylineId}:${persistedBreakpoint.waypointId}:${persistedBreakpoint.segmentProgress}"
        if (signature == lastDjiCheckpointSignature) return
        lastDjiCheckpointSignature = signature
        persistCheckpoint(checkpoint, commit = false)
    }

    private fun refreshRestoredDjiCheckpoint(state: WaylineState) {
        val pending = restoredDjiCheckpoint ?: return
        val breakpoint = state.breakpoint ?: return
        val previous = pending.checkpoint.djiBreakpoint
        if (!breakpoint.isNewerOrMorePreciseThan(previous)) return
        val currentMission = mission ?: return
        if (pending.mission.id != currentMission.id) return
        val waypointIndex = djiGlobalWaypointIndex(
            currentMission,
            breakpoint.waylineId,
            breakpoint.waypointId,
        ) ?: return
        val checkpoint = pending.checkpoint.copy(
            waypointIndex = waypointIndex,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = waypointIndex,
            djiBreakpoint = breakpoint,
        )
        restoredDjiCheckpoint = pending.copy(checkpoint = checkpoint)
        activeDjiRecoveryDisplayCheckpoint = checkpoint
        lastDjiCheckpointSignature =
            "DJI:${checkpoint.state}:${breakpoint.waylineId}:${breakpoint.waypointId}:${breakpoint.segmentProgress}"
        persistCheckpoint(checkpoint, commit = false)
            operationMessage = activity.getString(R.string.precise_resume_point_updated, waypointIndex + 1, (breakpoint.segmentProgress * 100).toInt())
        recordSurveyEvent(
            "wayline_recovery_refined",
            mapOf(
                "wayline_id" to breakpoint.waylineId,
                "waypoint_index" to waypointIndex,
                "segment_progress" to breakpoint.segmentProgress,
                "has_location" to (breakpoint.latitude != null && breakpoint.longitude != null),
            ),
        )
    }

    private fun updateDjiRecoveryDisplay(state: WaylineState) {
        val checkpoint = activeDjiRecoveryDisplayCheckpoint ?: return
        val currentMission = mission ?: return
        if (checkpoint.missionId != currentMission.id) {
            activeDjiRecoveryDisplayCheckpoint = null
            return
        }
        if (state.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED) {
            val liveIndex = djiGlobalWaypointIndex(
                currentMission,
                state.waylineId ?: state.breakpoint?.waylineId,
                state.waypointIndex ?: state.breakpoint?.waypointId,
            )
            if (liveIndex == currentMission.waypoints.lastIndex) {
                activeDjiRecoveryDisplayCheckpoint = null
            }
            return
        }
        if (state.phase != edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING) return
        val liveIndex = djiGlobalWaypointIndex(
            currentMission,
            state.waylineId ?: state.breakpoint?.waylineId,
            state.waypointIndex ?: state.breakpoint?.waypointId,
        )
        val recoveryPoint = djiCheckpointPoint(currentMission, checkpoint)
        val aircraft = snapshot().aircraftLocation
        val reachedByPosition = recoveryPoint != null && aircraft != null && distanceMeters(
            aircraft.latitude,
            aircraft.longitude,
            recoveryPoint.latitude,
            recoveryPoint.longitude,
        ) <= DJI_RECOVERY_DISPLAY_REACHED_METERS
        if (reachedByPosition || (liveIndex != null && liveIndex > checkpoint.waypointIndex)) {
            activeDjiRecoveryDisplayCheckpoint = null
            recordSurveyEvent(
                "wayline_recovery_display_reached",
                mapOf(
                    "checkpoint_waypoint_index" to checkpoint.waypointIndex,
                    "live_waypoint_index" to liveIndex,
                    "reached_by_position" to reachedByPosition,
                ),
            )
        }
    }

    private fun djiCheckpointPoint(
        currentMission: SurveyMission,
        checkpoint: SurveyExecutionCheckpoint,
    ): GeoPoint? {
        val start = currentMission.waypoints.getOrNull(checkpoint.waypointIndex)?.point ?: return null
        val breakpoint = checkpoint.djiBreakpoint ?: return start
        val end = currentMission.waypoints.getOrNull(checkpoint.waypointIndex + 1)?.point ?: return start
        val fraction = breakpoint.segmentProgress.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = breakpoint.latitude
                ?: start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = breakpoint.longitude
                ?: start.longitude + (end.longitude - start.longitude) * fraction,
            altitudeMeters = breakpoint.altitudeMeters
                ?: start.altitudeMeters + (end.altitudeMeters - start.altitudeMeters) * fraction,
        )
    }

    private fun WaylineBreakpoint.isNewerOrMorePreciseThan(previous: WaylineBreakpoint?): Boolean {
        if (previous == null) return true
        if (waylineId != previous.waylineId) return waylineId > previous.waylineId
        if (waypointId != previous.waypointId) return waypointId > previous.waypointId
        if (segmentProgress > previous.segmentProgress + 1e-6) return true
        val hasLocation = latitude != null && longitude != null && altitudeMeters != null
        val previousHasLocation = previous.latitude != null && previous.longitude != null && previous.altitudeMeters != null
        return hasLocation && !previousHasLocation
    }

    private fun preserveDjiRecoveryAfterUnexpectedStop(previous: WaylineState, currentState: WaylineState) {
        val currentMission = mission ?: return
        if (selectedBackend() != SurveyExecutionBackend.DJI_KMZ) return
        if (previous.phase !in DJI_RECOVERABLE_ACTIVE_PHASES) return
        if (currentState.phase !in DJI_UNEXPECTED_STOP_PHASES) return
        val rawBreakpoint = currentState.breakpoint ?: previous.breakpoint ?: return
        val localWaypointIndex = currentState.waypointIndex ?: previous.waypointIndex ?: rawBreakpoint.waypointId
        val waylineId = currentState.waylineId ?: previous.waylineId ?: rawBreakpoint.waylineId
        val waypointIndex = djiGlobalWaypointIndex(currentMission, waylineId, localWaypointIndex) ?: return
        val aircraftLocation = snapshot().aircraftLocation
        val breakpoint = SurveyDjiBreakpointEstimator.refine(
            mission = currentMission,
            breakpoint = rawBreakpoint.copy(waypointId = waypointIndex),
            aircraftLatitude = aircraftLocation?.latitude,
            aircraftLongitude = aircraftLocation?.longitude,
        ).copy(waypointId = rawBreakpoint.waypointId)
        if (currentState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED &&
            waypointIndex == currentMission.waypoints.lastIndex
        ) return
        val checkpoint = SurveyExecutionCheckpoint(
            missionId = currentMission.id,
            waypointIndex = waypointIndex.coerceIn(currentMission.waypoints.indices),
            state = edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = waypointIndex.coerceIn(currentMission.waypoints.indices),
            phase = edu.playground.djivln.survey.SurveyExecutionPhase.SURVEY,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = breakpoint,
        )
        restoredDjiCheckpoint = PendingCheckpointRestore(
            currentMission,
            checkpoint,
            SurveyExecutionBackend.DJI_KMZ,
            "",
            activity.getString(R.string.unexpected_interruption),
        )
        activeDjiRecoveryDisplayCheckpoint = checkpoint
        persistCheckpoint(checkpoint, commit = false)
                operationMessage = activity.getString(R.string.route_interrupted_resume_saved, waypointIndex + 1)
        recordSurveyEvent(
            "wayline_recovery_preserved",
            mapOf(
                "from_phase" to previous.phase.name,
                "to_phase" to currentState.phase.name,
                "wayline_id" to breakpoint.waylineId,
                "waypoint_index" to waypointIndex,
                "segment_progress" to breakpoint.segmentProgress,
                "segment_progress_source" to if (breakpoint.segmentProgress > rawBreakpoint.segmentProgress) {
                    "aircraft_position"
                } else {
                    "dji_breakpoint"
                },
            ),
        )
    }

    private fun requestDjiRecoverySnapshot(
        reason: String,
        prepareForResume: Boolean = false,
        completion: (Result<WaylineBreakpoint>) -> Unit = {},
    ) {
        val missionFileName = waylineState.missionFileName ?: kmzFile?.name
        if (missionFileName == null) {
            completion(Result.failure(IllegalStateException(activity.getString(R.string.dji_mission_name_missing))))
            return
        }
        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (!settled.compareAndSet(false, true)) return@Runnable
            val error = TimeoutException(activity.getString(R.string.dji_breakpoint_query_timeout))
            recordSurveyEvent(
                "wayline_recovery_query_failed",
                mapOf("reason" to reason, "error" to error.message, "timeout" to true),
            )
            completion(Result.failure(error))
        }
        persistenceHandler.postDelayed(timeout, DJI_BREAKPOINT_QUERY_TIMEOUT_MILLIS)
        waylinePort.queryBreakpoint(missionFileName) { result ->
            activity.runOnUiThread {
                if (!settled.compareAndSet(false, true)) return@runOnUiThread
                persistenceHandler.removeCallbacks(timeout)
                result.fold(
                    onSuccess = { breakpoint ->
                        if (breakpoint == null) {
                            completion(Result.failure(IllegalStateException(activity.getString(R.string.dji_precise_breakpoint_not_returned))))
                        } else {
                            if (persistExternalDjiRecovery(reason, breakpoint, prepareForResume)) {
                                completion(Result.success(breakpoint))
                            } else {
                                completion(Result.failure(IllegalStateException(activity.getString(R.string.precise_resume_point_save_failed))))
                            }
                        }
                    },
                    onFailure = { error ->
                    recordSurveyEvent(
                        "wayline_recovery_query_failed",
                        mapOf("reason" to reason, "error" to error.message),
                    )
                        completion(Result.failure(error))
                    },
                )
            }
        }
    }

    private fun persistExternalDjiRecovery(
        reason: String,
        breakpoint: WaylineBreakpoint,
        prepareForResume: Boolean = false,
    ): Boolean {
        val currentMission = mission ?: return false
        val waypointIndex = djiGlobalWaypointIndex(
            currentMission,
            breakpoint.waylineId,
            breakpoint.waypointId,
        ) ?: return false
        val checkpoint = SurveyExecutionCheckpoint(
            missionId = currentMission.id,
            waypointIndex = waypointIndex,
            state = edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = waypointIndex,
            phase = edu.playground.djivln.survey.SurveyExecutionPhase.SURVEY,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = breakpoint,
        )
        val persisted = persistCheckpoint(checkpoint, commit = true)
        if (!persisted) {
                operationMessage = activity.getString(R.string.route_paused_resume_save_failed)
            recordSurveyEvent(
                "wayline_recovery_persist_failed",
                mapOf(
                    "reason" to reason,
                    "wayline_id" to breakpoint.waylineId,
                    "waypoint_index" to waypointIndex,
                    "segment_progress" to breakpoint.segmentProgress,
                ),
            )
            renderStatus()
            return false
        }
        lastDjiCheckpointSignature =
            "DJI:${checkpoint.state}:${breakpoint.waylineId}:${breakpoint.waypointId}:${breakpoint.segmentProgress}"
        val shouldPrepareResume = prepareForResume || waylineState.phase in DJI_UNEXPECTED_STOP_PHASES
        if (shouldPrepareResume ||
            waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED
        ) {
            activeDjiRecoveryDisplayCheckpoint = checkpoint
        }
        if (shouldPrepareResume) {
            restoredDjiCheckpoint = PendingCheckpointRestore(
                currentMission,
                checkpoint,
                SurveyExecutionBackend.DJI_KMZ,
                "",
                reason,
            )
            operationMessage = activity.getString(
                R.string.route_paused_waypoint_saved,
                reason,
                waypointIndex + 1,
            )
        } else if (waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED) {
            operationMessage = activity.getString(
                R.string.precise_resume_saved_battery_swap,
                waypointIndex + 1,
                (breakpoint.segmentProgress * 100).toInt(),
            )
        }
        renderStatus()
        recordSurveyEvent(
            "wayline_recovery_snapshot",
            mapOf(
                "reason" to reason,
                "wayline_id" to breakpoint.waylineId,
                "waypoint_index" to waypointIndex,
                "segment_progress" to breakpoint.segmentProgress,
                "phase" to waylineState.phase.name,
            ),
        )
        return true
    }

    /**
     * Saves a usable local fallback immediately when RTH has already taken control and DJI can no
     * longer accept pause. A later SDK breakpoint query may refine this estimate.
     */
    private fun persistEstimatedExternalDjiRecovery(
        reason: String,
        prepareForResume: Boolean = true,
    ): Boolean {
        val currentMission = mission ?: return false
        val raw = waylineState.breakpoint
        val waylineId = waylineState.waylineId ?: raw?.waylineId ?: return false
        val localWaypointIndex = waylineState.waypointIndex ?: raw?.waypointId ?: return false
        val globalWaypointIndex = djiGlobalWaypointIndex(
            currentMission,
            waylineId,
            localWaypointIndex,
        ) ?: return false
        val aircraft = snapshot().aircraftLocation
        val estimatedGlobal = SurveyDjiBreakpointEstimator.refine(
            mission = currentMission,
            breakpoint = (raw ?: WaylineBreakpoint(
                waylineId = waylineId,
                waypointId = localWaypointIndex,
                segmentProgress = 0.0,
            )).copy(waypointId = globalWaypointIndex),
            aircraftLatitude = aircraft?.latitude,
            aircraftLongitude = aircraft?.longitude,
        )
        return persistExternalDjiRecovery(
            reason = reason,
            breakpoint = estimatedGlobal.copy(waypointId = localWaypointIndex),
            prepareForResume = prepareForResume,
        )
    }

    private fun recordDjiState(state: WaylineState) {
        val action = state.actionEvent
        val signature = "${state.phase}:${state.missionFileName}:${state.waylineId}:${state.waypointIndex}:" +
            "${state.error}:${action?.actionGroupId}:${action?.actionId}:${action?.started}:${action?.error}"
        if (signature == lastDjiEventSignature) return
        lastDjiEventSignature = signature
        recordSurveyEvent(
            "wayline_state",
            mapOf(
                "phase" to state.phase.name,
                "mission_file_name" to state.missionFileName,
                "wayline_id" to state.waylineId,
                "waypoint_index" to state.waypointIndex,
                "message" to activity.resolve(state.message),
                "error" to state.error,
                "action_group_id" to action?.actionGroupId,
                "action_id" to action?.actionId,
                "action_started" to action?.started,
                "action_error" to action?.error,
            ),
        )
    }

    private fun handleDjiActionFailure(state: WaylineState) {
        val action = state.actionEvent ?: return
        val error = action.error?.takeIf(String::isNotBlank) ?: return
        if (action.started || state.phase != edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING) return
        val signature = "${state.missionFileName}:${action.actionGroupId}:${action.actionId}:$error"
        if (signature == lastHandledDjiActionFailure) return
        lastHandledDjiActionFailure = signature
        operationMessage = activity.getString(R.string.kmz_capture_failed_auto_pausing, error)
        recordSurveyEvent(
            "wayline_photo_action_failed",
            mapOf(
                "mission_file_name" to state.missionFileName,
                "wayline_id" to state.waylineId,
                "waypoint_index" to state.waypointIndex,
                "action_group_id" to action.actionGroupId,
                "action_id" to action.actionId,
                "error" to error,
            ),
        )
        waylinePort.pause { result ->
            activity.runOnUiThread {
                operationMessage = result.fold(
                    onSuccess = { activity.getString(R.string.kmz_capture_failed_route_paused, error) },
                    onFailure = { activity.getString(R.string.kmz_capture_failed_pause_failed, it.message ?: it.javaClass.simpleName) },
                )
                renderStatus()
            }
        }
    }

    private fun recordCustomState(state: SurveyCustomExecutionSnapshot) {
        val signature = "${state.status.state}:${state.phase}:${state.executionLegIndex}:${state.message}"
        if (signature == lastCustomEventSignature) return
        lastCustomEventSignature = signature
        recordSurveyEvent(
            "custom_wayline_state",
            mapOf(
                "state" to state.status.state.name,
                "phase" to state.phase?.name,
                "execution_leg_index" to state.executionLegIndex,
                "execution_leg_count" to state.executionLegCount,
                "waypoint_index" to state.status.waypointIndex,
                "reason" to state.status.reason,
                "message" to state.message,
            ),
        )
    }

    private fun recordSurveyEvent(type: String, fields: Map<String, Any?> = emptyMap()) {
        recordEvent(
            type,
            mapOf(
                "mission_id" to mission?.id,
                "backend" to runCatching { selectedBackend().name }.getOrNull(),
            ) + fields,
        )
    }

    private fun missionVersions() = runCatching {
        val current = libraryPreferences.getString(KEY_LIBRARY, null)
        val legacy = preferences.getString(KEY_LIBRARY, null)
        val raw = current ?: legacy
        SurveyMissionLibrary.decode(raw).also {
            if (current == null && legacy != null) {
                libraryPreferences.edit().putString(KEY_LIBRARY, legacy).commit()
                preferences.edit().remove(KEY_LIBRARY).apply()
            }
        }
    }.getOrElse {
        libraryPreferences.edit().remove(KEY_LIBRARY).apply()
        emptyList()
    }

    private fun saveMissionVersion(announce: Boolean) {
        val current = mission ?: run {
            if (announce) reject(activity.getString(R.string.generate_or_import_before_save_version))
            return
        }
        val existing = missionVersions()
        val encoded = SurveyMissionJson.encode(current)
        val duplicate = existing.firstOrNull {
            it.missionName == current.name && it.missionJson == encoded
        }
        if (duplicate != null) {
            if (announce) {
                operationMessage = activity.getString(
                    R.string.mission_version_already_saved,
                    duplicate.missionName,
                    duplicate.revision,
                )
                renderStatus()
            }
            return
        }
        val versions = SurveyMissionLibrary.addVersion(existing, current)
        libraryPreferences.edit().putString(KEY_LIBRARY, SurveyMissionLibrary.encode(versions)).apply()
        if (announce) {
            val saved = versions.first()
            operationMessage = activity.getString(R.string.mission_version_saved, saved.missionName, saved.revision)
            renderStatus()
        }
    }

    private fun bestAvailableCheckpoint(current: SurveyMission): SurveyExecutionCheckpoint? {
        val inMemory = listOfNotNull(
            restoredDjiCheckpoint?.checkpoint,
            pendingCheckpointRestore?.checkpoint,
            activeDjiRecoveryDisplayCheckpoint,
        ).firstOrNull { it.missionId == current.id }
        if (inMemory != null) return inMemory
        val persisted = readPersistedCheckpoint()?.let { raw ->
            runCatching { SurveyExecutionCheckpointJson.decode(raw) }.getOrNull()
        }?.takeIf { it.missionId == current.id }
        if (persisted != null) return persisted
        val breakpoint = waylineState.breakpoint
        val globalWaypointIndex = djiGlobalWaypointIndex(
            current,
            waylineState.waylineId ?: breakpoint?.waylineId,
            waylineState.waypointIndex ?: breakpoint?.waypointId,
        ) ?: return customExecution.checkpoint()?.takeIf { it.missionId == current.id }
        return SurveyWaylineExport.checkpoint(
            current,
            waylineState.copy(waypointIndex = globalWaypointIndex),
            System.currentTimeMillis(),
        ) ?: customExecution.checkpoint()?.takeIf { it.missionId == current.id }
    }

    private fun saveCurrentResumePoint() {
        val current = mission ?: return reject(activity.getString(R.string.no_current_mission))
        if (selectedBackend() != SurveyExecutionBackend.DJI_KMZ) {
            val checkpoint = customExecution.checkpoint()
                ?: return reject(activity.getString(R.string.no_active_resume_point))
            if (!persistCheckpoint(checkpoint, commit = true)) {
                return reject(activity.getString(R.string.route_paused_resume_save_failed))
            }
            operationMessage = activity.getString(
                R.string.current_resume_point_saved,
                checkpoint.waypointIndex + 1,
                (checkpoint.djiBreakpoint?.segmentProgress?.times(100.0) ?: 0.0).toInt(),
            )
            renderStatus()
            return
        }
        val active = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        if (!active) {
            val saved = bestAvailableCheckpoint(current)
                ?: return reject(activity.getString(R.string.no_active_resume_point))
            operationMessage = activity.getString(
                R.string.current_resume_point_already_saved,
                saved.waypointIndex + 1,
            )
            renderStatus()
            return
        }
        val reason = activity.getString(R.string.user_saved_resume_point)
        val fallbackSaved = persistEstimatedExternalDjiRecovery(
            reason = reason,
            prepareForResume = false,
        )
        operationMessage = if (fallbackSaved) {
            activity.getString(R.string.resume_point_local_saved_refining)
        } else {
            activity.getString(R.string.saving_current_resume_point)
        }
        renderStatus()
        requestDjiRecoverySnapshot(reason, prepareForResume = false) { result ->
            val saved = bestAvailableCheckpoint(current)
            operationMessage = when {
                result.isSuccess && saved != null -> activity.getString(
                    R.string.current_resume_point_saved,
                    saved.waypointIndex + 1,
                    (saved.djiBreakpoint?.segmentProgress?.times(100.0) ?: 0.0).toInt(),
                )
                fallbackSaved && saved != null -> activity.getString(
                    R.string.current_resume_point_estimated_saved,
                    saved.waypointIndex + 1,
                )
                else -> activity.getString(
                    R.string.current_resume_point_save_failed,
                    result.exceptionOrNull()?.message.orEmpty(),
                )
            }
            renderStatus()
        }
    }

    private fun showMissionLibrary() {
        if (isMissionLockedForEditing()) return reject(activity.getString(R.string.stop_route_before_version_switch))
        val versions = missionVersions()
        if (versions.isEmpty()) return reject(activity.getString(R.string.mission_library_empty))
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val labels = versions.map {
            "${it.missionName}  v${it.revision}  ·  ${formatter.format(Date(it.savedAtEpochMillis))}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.mission_library_title, versions.size))
            .setItems(labels) { _, which ->
                runCatching {
                    versions[which].mission().also { restored ->
                        if (restored.activeMapping != null) ActiveRecaptureMissionValidator.validate(restored)
                    }
                }
                    .onSuccess { restored ->
                        settingsRestoring = true
                        applyMissionToFields(restored)
                        settingsRestoring = false
                        activateMission(restored, activity.getString(R.string.mission_version_restored, labels[which]))
                    }
                .onFailure { reject(activity.getString(R.string.mission_version_restore_failed, it.message.orEmpty())) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun restoreMissionSession() {
        val raw = preferences.getString(KEY_MISSION, null) ?: return
        runCatching { decodeMissionValidated(raw) }
            .onSuccess { restored ->
                activeRecaptureSourceMission = if (restored.activeMapping != null) {
                    preferences.getString(KEY_ACTIVE_RECAPTURE_SOURCE_MISSION, null)
                        ?.let { encoded -> runCatching { decodeMissionValidated(encoded) }.getOrNull() }
                        ?.takeIf { source ->
                            val available = source.activeMapping?.regions?.map { it.regionId }?.toSet().orEmpty()
                            restored.activeMapping.regions.all { it.regionId in available }
                        }
                        ?: restored
                } else null
                applyMissionToFields(restored)
                activateMission(
                    restored,
                    activity.getString(R.string.mission_restored, restored.name),
                    persist = false,
                    preserveActiveRecaptureSource = true,
                )
                readPersistedCheckpoint()?.let { checkpointRaw ->
                    runCatching { SurveyExecutionCheckpointJson.decode(checkpointRaw) }
                        .onSuccess { checkpoint ->
                            if (checkpoint.missionId == restored.id &&
                                checkpoint.state in setOf(
                                    edu.playground.djivln.survey.SurveyExecutionState.ARMING,
                                    edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
                                    edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
                                )
                            ) {
                                queueCheckpointRestore(restored, checkpoint, activity.getString(R.string.automatic_restore))
                            } else {
                                clearCheckpointStorage(commit = false)
                            }
                        }
                        .onFailure { clearCheckpointStorage(commit = false) }
                }
            }
            .onFailure {
                clearPersistedSurveySession()
                mission = null
                activeRecaptureSourceMission = null
                editedRoi = emptyList()
            }
    }

    private fun plannerTextInputs(): List<Pair<String, EditText>> = with(binding) {
        listOf(
            "survey_name" to surveyName,
            "flight_altitude" to flightAltitude,
            "target_surface_offset" to targetSurfaceOffset,
            "safe_takeoff_altitude" to safeTakeoffAltitude,
            "route_heading" to routeHeading,
            "takeoff_speed" to takeoffSpeed,
            "descent_speed" to descentSpeed,
            "flight_speed" to flightSpeed,
            "oblique_speed" to obliqueSpeed,
            "oblique_forward_overlap" to obliqueForwardOverlap,
            "oblique_side_overlap" to obliqueSideOverlap,
            "timed_capture_interval" to timedCaptureInterval,
            "forward_overlap" to forwardOverlap,
            "side_overlap" to sideOverlap,
            "boundary_margin" to boundaryMargin,
            "oblique_pitch" to obliquePitch,
            "ue_endpoint" to ueEndpoint,
            "building_cog_template" to buildingCogTemplate,
        )
    }

    private fun plannerSpinners(): List<Pair<String, Spinner>> = with(binding) {
        listOf(
            "takeoff_mode" to takeoffMode,
            "altitude_mode" to altitudeMode,
            "start_mode" to startMode,
            "completion_action" to completionAction,
            "capture_mode" to captureMode,
            "photo_ratio" to photoRatio,
            "oblique_heading_mode" to obliqueHeadingMode,
            "execution_backend" to executionBackend,
            "terrain_kind" to terrainKind,
        )
    }

    private fun setupPlannerSettingsPersistence() {
        val watcher = simpleTextWatcher {
            schedulePersistPlannerSettings()
            syncActiveRecaptureRouteSpeedFromField()
            binding.obliqueAngle.text = binding.obliquePitch.text.toString().trim()
                .takeIf { it.isNotEmpty() }?.let { activity.getString(R.string.pitch_value, it) }
                ?: activity.getString(R.string.set_pitch)
            refreshBuildingHeightActionLabel()
            updateSpeedLimitHint()
        }
        plannerTextInputs().forEach { (_, input) -> input.addTextChangedListener(watcher) }
        plannerSpinners().filterNot { (key, _) ->
            key in setOf("capture_mode", "photo_ratio", "execution_backend", "terrain_kind")
        }.forEach { (_, spinner) ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    schedulePersistPlannerSettings()
                    updateSpeedLimitHint()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun schedulePersistPlannerSettings() {
        if (settingsRestoring) return
        persistenceHandler.removeCallbacks(persistSettingsRunnable)
        persistenceHandler.postDelayed(persistSettingsRunnable, SETTINGS_SAVE_DELAY_MILLIS)
    }

    private fun persistPlannerSettings() {
        if (settingsRestoring) return
        runCatching {
            val text = JSONObject()
            plannerTextInputs().forEach { (key, input) -> text.put(key, input.text.toString()) }
            val spinners = JSONObject()
            plannerSpinners().forEach { (key, spinner) -> spinners.put(key, spinner.selectedItemPosition) }
            val captureViews = JSONArray()
            selectedCaptureViews().forEach { captureViews.put(it.name) }
            val root = JSONObject()
                .put("schema_version", SETTINGS_SCHEMA_VERSION)
                .put("text_inputs", text)
                .put("spinners", spinners)
                .put("enabled_capture_views", captureViews)
                .put("terrain_enabled", false)
                .put("selected_tab", selectedTab.name)
            preferences.edit()
                .putString(KEY_SETTINGS, root.toString())
                .putString(KEY_BUILDING_COG_TEMPLATE, binding.buildingCogTemplate.text.toString().trim())
                .apply()
        }
    }

    private fun restorePlannerSettings() {
        val raw = preferences.getString(KEY_SETTINGS, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            val schemaVersion = root.getInt("schema_version")
            require(schemaVersion in 1..SETTINGS_SCHEMA_VERSION)
            val text = root.getJSONObject("text_inputs")
            plannerTextInputs().forEach { (key, input) -> if (text.has(key)) input.setText(text.getString(key)) }
            if (schemaVersion < 3 && !text.has("oblique_speed") && text.has("flight_speed")) {
                binding.obliqueSpeed.setText(text.getString("flight_speed"))
            }
            val spinners = root.getJSONObject("spinners")
            plannerSpinners().forEach { (key, spinner) ->
                if (spinners.has(key)) {
                    val position = spinners.getInt(key)
                    if (position in 0 until spinner.count) spinner.setSelection(position)
                }
            }
            if (schemaVersion < SETTINGS_SCHEMA_VERSION) {
                binding.photoRatio.setSelection(PHOTO_RATIO_4_BY_3)
            }
            val restoredViews = buildSet {
                val array = root.optJSONArray("enabled_capture_views")
                if (array != null) for (index in 0 until array.length()) {
                    runCatching { SurveyCaptureView.valueOf(array.getString(index)) }.getOrNull()?.let(::add)
                }
            }.ifEmpty { STANDARD_SURVEY_CAPTURE_VIEWS }
            captureSelectionChanging = true
            binding.viewNadir.isChecked = SurveyCaptureView.NADIR in restoredViews
            binding.viewForward.isChecked = SurveyCaptureView.FORWARD_OBLIQUE in restoredViews
            binding.viewBackward.isChecked = SurveyCaptureView.BACKWARD_OBLIQUE in restoredViews
            binding.viewLeft.isChecked = SurveyCaptureView.LEFT_OBLIQUE in restoredViews
            binding.viewRight.isChecked = SurveyCaptureView.RIGHT_OBLIQUE in restoredViews
            captureSelectionChanging = false
            binding.followTerrain.isChecked = false
            selectedTab = runCatching { SurveyTab.valueOf(root.optString("selected_tab", SurveyTab.AREA.name)) }
                .getOrDefault(SurveyTab.AREA)
                .takeUnless { it == SurveyTab.TERRAIN } ?: SurveyTab.AREA
            lastBackendPosition = binding.executionBackend.selectedItemPosition
            lastTerrainKindPosition = binding.terrainKind.selectedItemPosition
            updateCaptureViewActions()
            binding.obliqueAngle.text = activity.getString(
                R.string.oblique_pitch_value,
                binding.obliquePitch.text.toString().trim(),
            )
        }.onFailure {
            preferences.edit().remove(KEY_SETTINGS).apply()
        }
    }

    private fun simpleTextWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after()
    }

    private fun updateSpeedLimitHint() = with(binding) {
        flightSpeed.error = null
        obliqueSpeed.error = null
        takeoffSpeed.error = null
        descentSpeed.error = null
        runCatching {
            val speed = flightSpeed.text.toString().toDouble()
            val obliqueRouteSpeed = obliqueSpeed.text.toString().toDouble()
            val climbSpeed = takeoffSpeed.text.toString().toDouble()
            val descentRouteSpeed = descentSpeed.text.toString().toDouble()
            val constraints = SurveyParameterPolicy.createConstraints(
                altitudeMetersAgl = flightAltitude.text.toString().toDouble(),
                routeHeadingDegrees = 0.0,
                forwardOverlapPercent = forwardOverlap.text.toString().toDouble(),
                sideOverlapPercent = sideOverlap.text.toString().toDouble(),
                speedMetersPerSecond = speed.coerceIn(0.5, 10.0),
                gimbalPitchDegrees = obliquePitch.text.toString().toDouble(),
                boundaryMarginMeters = 0.0,
                obliqueFiveDirection = mission?.constraints?.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                targetSurfaceToTakeoffMeters = 0.0,
                safeTakeoffAltitudeMeters = 30.0,
                takeoffSpeedMetersPerSecond = climbSpeed.coerceIn(0.5, 6.0),
                descentSpeedMetersPerSecond = descentRouteSpeed.coerceIn(0.5, 6.0),
                obliqueForwardOverlapPercent = obliqueForwardOverlap.text.toString().toDouble(),
                obliqueSideOverlapPercent = obliqueSideOverlap.text.toString().toDouble(),
                altitudeMode = SurveyAltitudeMode.ABOVE_TARGET_SURFACE,
                startPointMode = SurveyStartPointMode.AUTO_NEAREST,
                completionAction = SurveyCompletionAction.RETURN_TO_HOME,
                captureTriggerMode = SurveyCaptureTriggerMode.values()[captureMode.selectedItemPosition],
                timedCaptureIntervalSeconds = timedCaptureInterval.text.toString().toDouble(),
                takeoffMode = SurveyTakeoffMode.values()[takeoffMode.selectedItemPosition],
                enabledCaptureViews = selectedCaptureViews().ifEmpty { STANDARD_SURVEY_CAPTURE_VIEWS },
                obliqueHeadingMode = SurveyObliqueHeadingMode.values()[obliqueHeadingMode.selectedItemPosition],
                obliqueSpeedMetersPerSecond = obliqueRouteSpeed.coerceIn(0.5, 10.0),
                message = { activity.getString(it) },
            )
            val camera = selectedPlanningCameraProfile()
            val nadirLimit = minOf(
                10.0,
                SurveyPlanner.captureFeasibility(camera, constraints, oblique = false)
                    .maximumFeasibleSpeedMetersPerSecond,
            )
            val obliqueLimit = minOf(
                10.0,
                SurveyPlanner.captureFeasibility(camera, constraints, oblique = true)
                    .maximumFeasibleSpeedMetersPerSecond,
            )
            val exceeded = speed > nadirLimit || obliqueRouteSpeed > obliqueLimit ||
                climbSpeed > 6.0 || descentRouteSpeed > 6.0
            if (speed > nadirLimit) {
                flightSpeed.error = activity.getString(R.string.speed_nadir_exceeded, nadirLimit)
            }
            if (obliqueRouteSpeed > obliqueLimit) {
                obliqueSpeed.error = activity.getString(R.string.speed_oblique_exceeded, obliqueLimit)
            }
            if (climbSpeed > 6.0) takeoffSpeed.error = activity.getString(R.string.speed_climb_exceeded)
            if (descentRouteSpeed > 6.0) descentSpeed.error = activity.getString(R.string.speed_descent_exceeded)
            speedLimitHint.text = activity.getString(
                R.string.speed_limit_summary,
                nadirLimit,
                obliqueLimit,
                if (exceeded) activity.getString(R.string.speed_reduce_warning) else "",
            )
            speedLimitHint.setTextColor(if (exceeded) 0xFFB3261E.toInt() else 0xFF7A828D.toInt())
        }.onFailure {
            speedLimitHint.setText(R.string.speed_limit_hard_cap)
            speedLimitHint.setTextColor(0xFF7A828D.toInt())
        }
    }

    private fun preflight(allowPhotoRatioPreparation: Boolean = false): Boolean {
        val current = mission ?: run {
            reject(activity.getString(R.string.generate_or_import_route_first))
            return false
        }
        if (!recaptureExecutionAllowed(current)) return false
        if (!plannerFieldsMatch(current)) {
            reject(activity.getString(R.string.route_parameters_changed_preflight))
            return false
        }
        if (current.terrainPlan != null && selectedBackend() != SurveyExecutionBackend.UE_HIL) {
            val home = snapshot().homeLocation?.let { GeoPoint(it.latitude, it.longitude) }
            val verification = SurveyTerrainTakeoffReferencePolicy.verify(
                plan = current.terrainPlan,
                currentHome = home,
                terrain = terrain,
                context = activity,
            )
            if (!verification.valid) {
                reject(verification.reason ?: activity.getString(R.string.terrain_takeoff_baseline_failed))
                return false
            }
        }
        val backend = selectedBackend()
        if (backend != SurveyExecutionBackend.UE_HIL && !effectiveSnapshot().simulatorActive) {
            accountIssue()?.let { reason ->
                reject(reason)
                return false
            }
        }
        if (backend == SurveyExecutionBackend.DJI_KMZ) {
            WaylineUiPolicy.unavailableReason(snapshot().connected, waylineState)?.let { reason ->
                reject(activity.resolve(reason))
                return false
            }
        }
        val camera = cameraDiscovery.current()
        val speedLimit = SurveyPlanner.speedLimit(current.cameraProfile, current.constraints)
        if (current.activeMapping == null && speedLimit.exceeded) {
            reject(activity.getString(R.string.speed_exceeds_camera_interval, speedLimit.effectiveMaximumMetersPerSecond))
            return false
        }
        val currentAircraft = effectiveSnapshot()
        val allowSimulatorAutoTakeoff = backend != SurveyExecutionBackend.DJI_KMZ &&
            current.constraints.takeoffMode == SurveyTakeoffMode.AUTO_SIMULATOR_ONLY &&
            currentAircraft.simulatorActive && !currentAircraft.isFlying
        val blocks = customExecution.preflight(
            current,
            allowSimulatorAutoTakeoff = allowSimulatorAutoTakeoff,
        ).blocks.toMutableSet()
        val requiresDjiCamera = backend != SurveyExecutionBackend.UE_HIL
        val importedCameraCompatibility = if (requiresDjiCamera && current.activeMapping != null) {
            runCatching { ActiveRecaptureMissionValidator.validate(current) }
                .fold(
                    onSuccess = {
                        ImportedMissionCameraCompatibilityPolicy.evaluate(
                            mission = current,
                            currentCamera = camera.captureProfile ?: camera.cameraProfile,
                            cameraConnected = camera.cameraConnected,
                            profileVerified = camera.profileVerified,
                            requireKmzPayload = backend == SurveyExecutionBackend.DJI_KMZ,
                            payloadPositionSupported = DjiV5CameraDiscovery.wpmzPayloadPositionIndex(camera.index) != null,
                            payloadLensSupported = DjiWpmzPayloadLens.fromStreamSourceName(camera.streamSource?.name) != null,
                            context = activity,
                        )
                    },
                    onFailure = { error ->
                        edu.playground.djivln.survey.ImportedMissionCameraCompatibility(
                            compatible = false,
                            reasons = listOf(
                                activity.getString(
                                    R.string.recapture_mission_validation_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            ),
                            minimumPlannedCaptureIntervalSeconds = null,
                        )
                    },
                )
        } else null
        val cameraChanged = requiresDjiCamera && current.activeMapping == null &&
            current.cameraProfile != selectedPlanningCameraProfile(camera.cameraProfile)
        val cameraUnverified = requiresDjiCamera && (!camera.profileVerified ||
            (!allowPhotoRatioPreparation && !cameraGeometryMatches(current, camera)))
        val importedCameraReasons = importedCameraCompatibility?.reasons.orEmpty()
        val passed = blocks.isEmpty() && !cameraUnverified && !cameraChanged && importedCameraReasons.isEmpty()
        manualTakeoverPreflightRejected = SurveyExecutionBlock.MANUAL_TAKEOVER in blocks
        if (!passed) {
            recordSurveyEvent(
                "survey_preflight_rejected",
                mapOf(
                    "blocks" to blocks.map { it.name },
                    "left_horizontal" to currentAircraft.leftStickHorizontal,
                    "left_vertical" to currentAircraft.leftStickVertical,
                    "right_horizontal" to currentAircraft.rightStickHorizontal,
                    "right_vertical" to currentAircraft.rightStickVertical,
                    "sticks_active" to currentAircraft.sticksActive,
                ),
            )
        }
        operationMessage = if (passed) {
            activity.getString(
                if (allowSimulatorAutoTakeoff) {
                    R.string.survey_preflight_passed_simulator_takeoff
                } else {
                    R.string.survey_preflight_passed
                },
                current.roi.size,
                current.waypoints.size,
                camera.cameraProfileLabel,
            )
        } else buildString {
            append(activity.getString(R.string.survey_preflight_failed_prefix))
            val reasons = blocks.map(::surveyBlockLabel).toMutableList()
            if (cameraUnverified) reasons += activity.getString(R.string.current_camera_not_calibrated)
            if (cameraChanged) reasons += activity.getString(R.string.current_camera_changed_regenerate)
            reasons += importedCameraReasons
            append(reasons.distinct().joinToString(activity.getString(R.string.list_separator)))
            append(activity.getString(R.string.preflight_no_commands_sent_suffix))
        }
        renderStatus()
        return passed
    }

    private fun plannerFieldsMatch(current: SurveyMission): Boolean {
        if (current.activeMapping != null) return true
        return runCatching {
        val constraints = current.constraints
        fun EditText.matches(value: Double, tolerance: Double = 1e-6): Boolean =
            kotlin.math.abs(text.toString().toDouble() - value) <= tolerance
        binding.surveyName.text.toString().trim().ifBlank { activity.getString(R.string.unnamed_area_route) } == current.name &&
            binding.flightAltitude.matches(constraints.altitudeMetersAgl, 0.11) &&
            binding.flightSpeed.matches(constraints.speedMetersPerSecond, 0.051) &&
            binding.obliqueSpeed.matches(constraints.obliqueSpeedMetersPerSecond, 0.051) &&
            binding.takeoffSpeed.matches(constraints.takeoffSpeedMetersPerSecond, 0.051) &&
            binding.descentSpeed.matches(constraints.descentSpeedMetersPerSecond, 0.051) &&
            binding.routeHeading.matches(constraints.routeHeadingDegrees, 0.11) &&
            binding.safeTakeoffAltitude.matches(constraints.safeTakeoffAltitudeMeters, 0.11) &&
            binding.targetSurfaceOffset.matches(constraints.targetSurfaceToTakeoffMeters, 0.11) &&
            binding.forwardOverlap.matches(constraints.forwardOverlap * 100.0, 0.11) &&
            binding.sideOverlap.matches(constraints.sideOverlap * 100.0, 0.11) &&
            binding.boundaryMargin.matches(constraints.boundaryMarginMeters, 0.11) &&
            binding.timedCaptureInterval.matches(constraints.timedCaptureIntervalSeconds, 0.051) &&
            binding.altitudeMode.selectedItemPosition == constraints.altitudeMode.ordinal &&
            binding.takeoffMode.selectedItemPosition == constraints.takeoffMode.ordinal &&
            (startPointMode() == constraints.startPointMode ||
                (startPointMode() == SurveyStartPointMode.AUTO_NEAREST &&
                    constraints.startPointMode == SurveyStartPointMode.CUSTOM)) &&
            binding.completionAction.selectedItemPosition == constraints.completionAction.ordinal &&
            binding.captureMode.selectedItemPosition == constraints.captureTriggerMode.ordinal &&
            selectedPlanningCameraProfile().let { selectedCamera ->
                selectedCamera.imageWidthPixels == current.cameraProfile.imageWidthPixels &&
                    selectedCamera.imageHeightPixels == current.cameraProfile.imageHeightPixels
            } &&
            binding.obliqueHeadingMode.selectedItemPosition == constraints.obliqueHeadingMode.ordinal &&
            binding.followTerrain.isChecked == (current.terrainPlan != null) &&
            (!binding.followTerrain.isChecked || (
                terrain != null && terrainSha256 != null &&
                    current.terrainPlan?.sourceSha256.equals(terrainSha256, ignoreCase = true) &&
                    current.terrainPlan?.sourceKind == terrainSources.selectedKind &&
                    current.terrainPlan?.bareEarthBaseSha256.equals(
                        terrainSources.active?.normalizedBareEarthBaseSha256,
                        ignoreCase = true,
                    )
                )) &&
            (constraints.collectionMode != SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION || (
                binding.obliqueForwardOverlap.matches(constraints.obliqueForwardOverlap * 100.0, 0.11) &&
                    binding.obliqueSideOverlap.matches(constraints.obliqueSideOverlap * 100.0, 0.11) &&
                    binding.obliquePitch.matches(constraints.obliqueGimbalPitchDegrees, 0.11) &&
                    selectedCaptureViews() == constraints.enabledCaptureViews
                ))
        }.getOrDefault(false)
    }

    private fun surveyBlockLabel(block: edu.playground.djivln.survey.SurveyExecutionBlock): String = when (block) {
            edu.playground.djivln.survey.SurveyExecutionBlock.AIRCRAFT_DISCONNECTED -> activity.getString(R.string.preflight_aircraft_disconnected)
            edu.playground.djivln.survey.SurveyExecutionBlock.SIMULATOR_REQUIRED -> activity.getString(R.string.preflight_simulator_required)
            edu.playground.djivln.survey.SurveyExecutionBlock.SIMULATOR_NOT_FLYING -> activity.getString(R.string.preflight_simulator_not_flying)
            edu.playground.djivln.survey.SurveyExecutionBlock.SIMULATOR_MUST_BE_OFF -> activity.getString(R.string.preflight_simulator_must_be_off)
            edu.playground.djivln.survey.SurveyExecutionBlock.REAL_AIRCRAFT_NOT_FLYING -> activity.getString(R.string.preflight_manual_takeoff_hover)
            edu.playground.djivln.survey.SurveyExecutionBlock.REAL_AIRCRAFT_NOT_STABLY_HOVERING -> activity.getString(R.string.preflight_stable_hover_required)
            edu.playground.djivln.survey.SurveyExecutionBlock.REAL_REQUIRES_MANUAL_TAKEOFF -> activity.getString(R.string.preflight_real_manual_takeoff_only)
            edu.playground.djivln.survey.SurveyExecutionBlock.TELEMETRY_STALE -> activity.getString(R.string.preflight_telemetry_stale)
            edu.playground.djivln.survey.SurveyExecutionBlock.GPS_UNAVAILABLE -> activity.getString(R.string.preflight_gps_unavailable)
            edu.playground.djivln.survey.SurveyExecutionBlock.MANUAL_TAKEOVER -> activity.getString(R.string.preflight_manual_takeover)
            edu.playground.djivln.survey.SurveyExecutionBlock.VIRTUAL_STICK_REQUIRED -> activity.getString(R.string.preflight_vs_required)
            edu.playground.djivln.survey.SurveyExecutionBlock.UNSUPPORTED_COORDINATE_FRAME -> activity.getString(R.string.preflight_coordinate_frame)
            edu.playground.djivln.survey.SurveyExecutionBlock.MISSION_TOO_LONG -> activity.getString(R.string.preflight_mission_too_long)
            edu.playground.djivln.survey.SurveyExecutionBlock.MISSION_ALTITUDE_UNSAFE -> activity.getString(R.string.preflight_altitude_unsafe)
            edu.playground.djivln.survey.SurveyExecutionBlock.CAMERA_TRIGGER_UNSAFE -> activity.getString(R.string.preflight_camera_trigger_unsafe)
            edu.playground.djivln.survey.SurveyExecutionBlock.CAMERA_GEOMETRY_UNVERIFIED -> activity.getString(R.string.current_camera_not_calibrated)
            edu.playground.djivln.survey.SurveyExecutionBlock.RC_SIGNAL_WEAK -> activity.getString(R.string.preflight_rc_signal_weak)
            edu.playground.djivln.survey.SurveyExecutionBlock.GPS_SATELLITES_LOW -> activity.getString(R.string.preflight_gps_satellites_low)
            edu.playground.djivln.survey.SurveyExecutionBlock.GPS_SIGNAL_WEAK -> activity.getString(R.string.preflight_gps_signal_weak)
            edu.playground.djivln.survey.SurveyExecutionBlock.HOME_LOCATION_REQUIRED -> activity.getString(R.string.preflight_home_invalid)
            edu.playground.djivln.survey.SurveyExecutionBlock.GO_HOME_HEIGHT_UNSAFE -> activity.getString(R.string.preflight_rth_height_unsafe)
            edu.playground.djivln.survey.SurveyExecutionBlock.MAX_FLIGHT_HEIGHT_TOO_LOW -> activity.getString(R.string.preflight_max_height_low)
            edu.playground.djivln.survey.SurveyExecutionBlock.MAX_FLIGHT_RADIUS_REQUIRED -> activity.getString(R.string.preflight_radius_missing)
            edu.playground.djivln.survey.SurveyExecutionBlock.MAX_FLIGHT_RADIUS_TOO_SMALL -> activity.getString(R.string.preflight_radius_too_small)
            edu.playground.djivln.survey.SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE -> activity.getString(R.string.preflight_failsafe_active)
            edu.playground.djivln.survey.SurveyExecutionBlock.TERRAIN_REAL_FLIGHT_NOT_VERIFIED -> activity.getString(R.string.preflight_terrain_unverified)
            edu.playground.djivln.survey.SurveyExecutionBlock.TERRAIN_FEATURE_DISABLED -> activity.getString(R.string.terrain_feature_disabled)
    }

    private fun refreshRthHeight() {
        if (!snapshot().connected) {
            binding.rthHeightCurrent.setText(R.string.rth_height_disconnected)
            return
        }
        val value = runCatching {
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight))
        }.getOrNull()
        binding.rthHeightCurrent.text = value?.let {
            activity.getString(R.string.rth_height_value, it)
        } ?: activity.getString(R.string.rth_height_unsupported)
        if (value != null && binding.rthHeight.text.isNullOrBlank()) binding.rthHeight.setText(value.toString())
    }

    private fun setRthHeight() {
        val aircraft = snapshot()
        if (!aircraft.connected) return reject(activity.getString(R.string.rth_write_aircraft_disconnected))
        if (aircraft.isFlying || aircraft.motorsOn) return reject(activity.getString(R.string.rth_write_ground_only))
        if (isExecutionActive()) return reject(activity.getString(R.string.rth_write_execution_locked))
        val value = binding.rthHeight.text.toString().toIntOrNull()
            ?: return reject(activity.getString(R.string.rth_height_invalid_integer))
        if (value !in 20..500) return reject(activity.getString(R.string.rth_height_range))
        val required = mission?.waypoints?.maxOfOrNull { it.point.altitudeMeters }
            ?.let { kotlin.math.ceil(it) }?.toInt()
        if (required != null && value < required) {
            return reject(activity.getString(R.string.rth_below_mission_max, required))
        }
        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight),
            value,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = activity.runOnUiThread {
                    operationMessage = activity.getString(R.string.rth_height_written, value)
                    kmzFile = null
                    kmzMissionId = null
                    kmzRthHeightMeters = null
                    kmzPayloadPositionIndex = null
                    kmzPayloadLensName = null
                    kmzExecutionSpeedMetersPerSecond = null
                    kmzObliqueSpeedMetersPerSecond = null
                    kmzTakeoffSpeedMetersPerSecond = null
                    uploadedKmzSessionSignature = null
                    kmzPreparationGeneration += 1
                    refreshRthHeight()
                    renderStatus()
                }

                override fun onFailure(error: IDJIError) = activity.runOnUiThread {
                    reject(activity.getString(R.string.rth_height_write_failed, error))
                }
            },
        )
    }

    fun importDocument(requestCode: Int, uri: Uri) {
        if (requestCode == REQUEST_IMPORT_V86_SFM_TEST) {
            importV86OfflineFolder(uri)
            return
        }
        if (!SurveyFeatureAvailability.TERRAIN_FOLLOWING_ENABLED && requestCode in setOf(
                REQUEST_IMPORT_DSM,
                REQUEST_IMPORT_BUILDING_HEIGHT,
            )
        ) {
        reject(activity.getString(R.string.terrain_feature_disabled))
            return
        }
        if (isMissionLockedForEditing()) {
            reject(activity.getString(R.string.survey_stop_before_import))
            return
        }
        when (requestCode) {
            REQUEST_IMPORT_DSM -> {
                val roi = editedRoi.takeIf { it.size >= 3 } ?: mission?.roi
            ?: return reject(activity.getString(R.string.draw_roi_before_dsm_import))
                worker.execute {
                    activity.runOnUiThread {
                        binding.dsmDownloadProgress.visibility = View.VISIBLE
        operationMessage = activity.getString(R.string.reading_surface_dsm)
                        renderStatus()
                    }
                    val result = runCatching {
                        val bytes = activity.contentResolver.openInputStream(uri)?.use {
                            TerrainImportSafety.readBounded(it, context = activity)
                        }
                            ?: error(activity.getString(R.string.cannot_read_dsm))
                        val loaded = GeoTiffTerrain.read(bytes.inputStream(), uri.lastPathSegment ?: "DSM", activity)
                        TerrainImportSafety.requireCompleteCoverage(loaded, roi, activity)
                        Triple(
                            loaded,
                            SurveyTerrainPlanner.sha256(bytes),
                            TerrainPreviewSampler.forArea(loaded, roi, 72, 48, activity),
                        )
                    }
                    activity.runOnUiThread {
                        binding.dsmDownloadProgress.visibility = View.GONE
                        result.onSuccess { (loaded, sha256, preview) ->
                            terrainSources.installStandaloneSurface(loaded, sha256)
                            invalidateMissionForTerrainSourceChange(activity.getString(R.string.dsm_source_replaced_regenerate))
                            terrainPreview = preview
                            showTerrainPreview(checkNotNull(terrainPreview))
                            binding.terrainKind.setSelection(TERRAIN_KIND_SURFACE_DSM)
                            binding.dsmBuildingConfirm.isEnabled = true
                            binding.dsmBuildingConfirm.isChecked = false
                            binding.followTerrain.isChecked = true
                            operationMessage = activity.getString(R.string.dsm_imported, loaded.info.displayName, loaded.info.epsg)
                            renderTerrainStatus()
                            renderStatus()
                        }.onFailure { reject(activity.getString(R.string.dsm_import_failed, it.message.orEmpty())) }
                    }
                }
            }
            REQUEST_IMPORT_BUILDING_HEIGHT -> importBuildingHeight(uri)
            REQUEST_IMPORT_MISSION -> worker.execute {
                rememberJsonDocumentLocation(uri)
                val result = runCatching {
                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        decodeMissionValidated(it.readText())
                    } ?: error(activity.getString(R.string.cannot_read_mission))
                }
                activity.runOnUiThread {
                    result.onSuccess { imported ->
                        if (isMissionLockedForEditing()) {
            reject(activity.getString(R.string.survey_task_switch_locked))
                            return@onSuccess
                        }
                        settingsRestoring = true
                        applyMissionToFields(imported)
                        settingsRestoring = false
                        activateMission(
                            imported,
                            activity.getString(R.string.mission_imported_named, imported.name),
                        )
        }.onFailure { reject(activity.getString(R.string.mission_import_failed, it.message.orEmpty())) }
                }
            }
            REQUEST_IMPORT_CHECKPOINT -> worker.execute {
                rememberJsonDocumentLocation(uri)
                val result = runCatching {
                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        SurveyExecutionCheckpointJson.decode(it.readText())
                    } ?: error(activity.getString(R.string.cannot_read_resume_point))
                }
                activity.runOnUiThread {
                    result.onSuccess(::restoreCheckpoint)
            .onFailure { reject(activity.getString(R.string.resume_point_import_failed, it.message.orEmpty())) }
                }
            }
        }
    }

    override fun close() {
        v86RemoteBrowser?.close()
        pendingCheckpointRestore = null
        restoredDjiCheckpoint = null
        cancelPreparingDjiExecution(activity.getString(R.string.page_closed), announce = false)
        stopReplay(clearMarker = true)
        replayHandler.removeCallbacksAndMessages(null)
        persistenceHandler.removeCallbacks(persistSettingsRunnable)
        persistPlannerSettings()
        persistenceHandler.removeCallbacksAndMessages(null)
        diagnosticGimbal?.stop()
        diagnosticGimbal = null
        stopPendingTriggerFrameSources()
        worker.shutdownNow()
        customExecution.close()
        stopDjiAppCapture()
        djiCaptureCamera.destroy()
        waylinePort.stop()
        triggerFrameRecorder.close()
        v86RefreshHandler.removeCallbacksAndMessages(null)
        v86DialogStatus = null
        v86Controller.close()
        artifactStore.close()
    }

    fun pauseForExternalIntervention(reason: String, completion: () -> Unit = {}) {
        cancelPreparingDjiExecution(reason, announce = true)
        djiPartitionContinuationGeneration += 1
        pendingDjiContinuationWaylineId?.let { nextWaylineId ->
            mission?.let { preservePendingDjiPartitionContinuation(it, nextWaylineId, reason) }
            renderStatus()
            completion()
            return
        }
        val djiActive = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        val customActive = customState.controlAcquirePending || customState.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
        )
        if (!djiActive && !customActive && (pendingCheckpointRestore != null || restoredDjiCheckpoint != null)) {
                operationMessage = activity.getString(R.string.existing_resume_point_retained, reason)
            renderStatus()
            completion()
            return
        }
        when {
            djiActive -> {
                if (isDjiReturnOrLandingActive()) {
                    val fallbackSaved = persistEstimatedExternalDjiRecovery(reason)
                    if (!fallbackSaved) {
                        operationMessage = activity.getString(R.string.saving_resume_point_during_rth, reason)
                        renderStatus()
                    }
                    requestDjiRecoverySnapshot(reason, prepareForResume = true) { result ->
                        if (result.isFailure && fallbackSaved) {
                            operationMessage = activity.getString(R.string.estimated_resume_saved, reason)
                            renderStatus()
                        } else if (result.isFailure) {
                            operationMessage = activity.getString(
                                R.string.route_paused_resume_save_failed_detail,
                                result.exceptionOrNull()?.message.orEmpty(),
                            )
                            renderStatus()
                        }
                        completion()
                    }
                } else {
                    pauseDjiAndPersistRecovery(reason, completion)
                }
            }
            customActive -> customExecution.pause(reason, completion)
            else -> completion()
        }
    }

    fun pauseForLifecycle(reason: String) {
        stopReplay(clearMarker = true)
        persistenceHandler.removeCallbacks(persistSettingsRunnable)
        persistPlannerSettings()
        val djiRunning = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        val customRunning = customState.controlAcquirePending || customState.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
        )
        if (preparingDjiExecution) {
            cancelPreparingDjiExecution(reason, announce = true)
        }
        if (!djiRunning && !customRunning) return
        pauseForExternalIntervention(reason)
    }

    fun isExecutionActive(): Boolean =
        waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        ) || preparingDjiExecution || customState.controlAcquirePending || customState.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
        )

    fun isHilExecutionActive(): Boolean =
        selectedBackend() == SurveyExecutionBackend.UE_HIL && isExecutionActive()

    private fun generate(obliqueFiveDirection: Boolean) {
        if (rejectEditingIfLocked()) return
        binding.followTerrain.isChecked = false
        if (mission?.activeMapping != null) {
            error(activity.getString(R.string.active_recapture_fixed_views_clear_first))
        }
        val planningRoi = editedRoi.takeIf { it.size >= 3 }
            ?: error(activity.getString(R.string.roi_needs_three_points))
        val liveSnapshot = snapshot()
        val terrainTakeoffReference = if (binding.followTerrain.isChecked) {
            require(liveSnapshot.connected) { activity.getString(R.string.terrain_route_requires_connected_home_gps) }
            val home = liveSnapshot.homeLocation
            val aircraft = liveSnapshot.aircraftLocation
            val sourcePoint = home ?: aircraft
                ?: error(activity.getString(R.string.terrain_route_requires_real_home_gps))
            SurveyTerrainTakeoffReference(
                point = GeoPoint(sourcePoint.latitude, sourcePoint.longitude),
                source = if (home != null) {
                    SurveyTerrainTakeoffReferenceSource.HOME_LOCATION
                } else {
                    SurveyTerrainTakeoffReferenceSource.AIRCRAFT_LOCATION
                },
                capturedAtEpochMillis = System.currentTimeMillis(),
            )
        } else null
        val ordinaryReference = liveSnapshot.homeLocation ?: liveSnapshot.aircraftLocation
            ?: liveSnapshot.remoteControllerLocation
        val center = terrainTakeoffReference?.point
            ?: ordinaryReference?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(
                latitude = planningRoi.map { it.latitude }.average(),
                longitude = planningRoi.map { it.longitude }.average(),
            )
        val constraints = SurveyParameterPolicy.createConstraints(
            altitudeMetersAgl = binding.flightAltitude.number(activity.getString(R.string.altitude), 10.0..120.0),
            routeHeadingDegrees = binding.routeHeading.number(activity.getString(R.string.heading), -360.0..360.0),
            forwardOverlapPercent = binding.forwardOverlap.number(activity.getString(R.string.forward_overlap), 50.0..90.0),
            sideOverlapPercent = binding.sideOverlap.number(activity.getString(R.string.side_overlap), 40.0..90.0),
            speedMetersPerSecond = binding.flightSpeed.number(activity.getString(R.string.nadir_speed), 0.5..10.0),
            obliqueSpeedMetersPerSecond = binding.obliqueSpeed.number(activity.getString(R.string.oblique_speed), 0.5..10.0),
            gimbalPitchDegrees = if (obliqueFiveDirection) {
                binding.obliquePitch.number(activity.getString(R.string.five_direction_oblique_pitch), -80.0..-30.0)
            } else -90.0,
            boundaryMarginMeters = binding.boundaryMargin.number(activity.getString(R.string.boundary_margin), 0.0..30.0),
            obliqueFiveDirection = obliqueFiveDirection,
            targetSurfaceToTakeoffMeters = binding.targetSurfaceOffset.number(activity.getString(R.string.target_surface_offset), -500.0..500.0),
            safeTakeoffAltitudeMeters = binding.safeTakeoffAltitude.number(activity.getString(R.string.safe_takeoff_altitude), 5.0..120.0),
            takeoffSpeedMetersPerSecond = binding.takeoffSpeed.number(activity.getString(R.string.ascent_speed), 0.5..6.0),
            descentSpeedMetersPerSecond = binding.descentSpeed.number(activity.getString(R.string.descent_speed), 0.5..6.0),
            obliqueForwardOverlapPercent = binding.obliqueForwardOverlap.number(activity.getString(R.string.oblique_forward_overlap), 50.0..90.0),
            obliqueSideOverlapPercent = binding.obliqueSideOverlap.number(activity.getString(R.string.oblique_side_overlap), 40.0..90.0),
            altitudeMode = SurveyAltitudeMode.values()[binding.altitudeMode.selectedItemPosition],
            startPointMode = startPointMode(),
            completionAction = SurveyCompletionAction.values()[binding.completionAction.selectedItemPosition],
            captureTriggerMode = SurveyCaptureTriggerMode.values()[binding.captureMode.selectedItemPosition],
            timedCaptureIntervalSeconds = binding.timedCaptureInterval.number(activity.getString(R.string.timed_capture_interval), 1.0..60.0),
            takeoffMode = SurveyTakeoffMode.values()[binding.takeoffMode.selectedItemPosition],
            enabledCaptureViews = selectedCaptureViews(),
            obliqueHeadingMode = SurveyObliqueHeadingMode.values()[binding.obliqueHeadingMode.selectedItemPosition],
            message = { activity.getString(it) },
        )
        var generated = SurveyPlanner.plan(
            binding.surveyName.text.toString().trim().ifBlank { activity.getString(R.string.unnamed_area_route) },
            planningRoi,
            camera = selectedPlanningCameraProfile(),
            constraints = constraints,
            takeoffPoint = center,
        )
        if (binding.followTerrain.isChecked) {
            val loaded = terrain ?: error(activity.getString(R.string.import_dsm_covering_roi_first))
            TerrainImportSafety.requireCompleteCoverage(loaded, planningRoi, activity)
            if (binding.terrainKind.selectedItemPosition == TERRAIN_KIND_SURFACE_DSM &&
                !binding.dsmBuildingConfirm.isChecked
            ) {
                error(activity.getString(R.string.confirm_dsm_building_alignment_first))
            }
            generated = SurveyTerrainPlanner.apply(
                mission = generated,
                terrain = loaded,
                takeoffReference = checkNotNull(terrainTakeoffReference),
                sourceSha256 = checkNotNull(terrainSha256),
                sourceKind = terrainSources.selectedKind,
                bareEarthBaseSha256 = terrainSources.active?.normalizedBareEarthBaseSha256,
                context = activity,
            ).mission
        }
        terrain?.let { previewTerrain(it, generated.roi) }
            ?.also { terrainPreview = it }
            ?.let(::showTerrainPreview)
        if (generated.constraints.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION &&
            generated.constraints.enabledCaptureViews == STANDARD_SURVEY_CAPTURE_VIEWS
        ) {
            captureSelectionSourceMission = generated
        } else {
            captureSelectionSourceMission = null
        }
        activateMission(generated, activity.getString(R.string.route_regenerated_ready))
    }

    private fun decodeMissionValidated(raw: String): SurveyMission =
        SurveyMissionJson.decode(raw).also { imported ->
            SurveyFeatureAvailability.requireSupportedMission(
                imported, activity.getString(R.string.terrain_mission_rejected_when_disabled),
            )
            if (imported.activeMapping != null) ActiveRecaptureMissionValidator.validate(imported)
        }

    private fun onRoiEdited(roi: List<GeoPoint>) {
        if (rejectEditingIfLocked()) return
        editedRoi = roi
        selectedVertexIndex = null
        updateSuggestedRouteHeading()
        invalidateGeneratedMission(activity.getString(R.string.roi_edited_regenerate))
    }

    private fun updateSuggestedRouteHeading() {
        if (editedRoi.size < 3) return
        runCatching { SurveyPlanner.suggestedRouteHeading(editedRoi) }
            .onSuccess { binding.routeHeading.setText(String.format(Locale.US, "%.1f", it)) }
    }

    private fun downloadGlobalTerrain() {
        val roi = editedRoi.takeIf { it.size >= 3 } ?: mission?.roi
            ?: return reject(activity.getString(R.string.draw_three_roi_points_first))
        binding.dsmDownloadProgress.visibility = View.VISIBLE
        binding.downloadTerrainDem.isEnabled = false
        operationMessage = activity.getString(R.string.locating_downloading_global_dem)
        binding.dsmStatus.setText(R.string.dem_downloading)
        renderStatus()
        worker.execute {
            val result = runCatching {
                val downloaded = GlobalTerrainDownloader(activity).download(roi, GLOBAL_TERRAIN_ZOOM) { completed, total ->
                    activity.runOnUiThread {
                        binding.dsmStatus.text = activity.getString(R.string.dem_downloading_progress, completed, total)
                    }
                }
                TerrainImportSafety.requireCompleteCoverage(downloaded.terrain, roi, activity)
                downloaded
            }
            activity.runOnUiThread {
                binding.dsmDownloadProgress.visibility = View.GONE
                binding.downloadTerrainDem.isEnabled = true
                result.onSuccess { downloaded ->
                    terrainSources.installBareEarth(downloaded.terrain, downloaded.sha256, clearSurface = true)
                    invalidateMissionForTerrainSourceChange(activity.getString(R.string.dem_source_replaced_regenerate))
                    terrainPreview = TerrainPreviewSampler.forArea(downloaded.terrain, roi, 96, 64, activity)
                    showTerrainPreview(checkNotNull(terrainPreview))
                    binding.terrainKind.setSelection(TERRAIN_KIND_BARE_EARTH)
                    binding.dsmBuildingConfirm.isChecked = false
                    binding.dsmBuildingConfirm.isEnabled = false
                    binding.followTerrain.isChecked = true
                    operationMessage = activity.getString(R.string.bare_earth_dem_ready, downloaded.downloadedTiles, downloaded.cachedTiles)
                    renderTerrainStatus()
                    renderStatus()
                }.onFailure { reject(activity.getString(R.string.global_dem_download_failed, it.message.orEmpty())) }
            }
        }
    }

    private fun refreshBuildingHeightActionLabel() {
        binding.downloadBuildingDsm.text = if (binding.buildingCogTemplate.text.toString().trim().isEmpty()) {
            activity.getString(R.string.import_building_height)
        } else {
            activity.getString(R.string.download_building_height)
        }
    }

    private fun openOrDownloadBuildingHeight() {
        if (binding.buildingCogTemplate.text.toString().trim().isNotEmpty()) {
            downloadBuildingDsm()
            return
        }
        if (editedRoi.size < 3 && (mission?.roi?.size ?: 0) < 3) {
            return reject(activity.getString(R.string.generate_planning_area_first))
        }
        if (globalTerrainBase == null) {
            return reject(activity.getString(R.string.download_dem_before_building_height))
        }
        activity.startActivityForResult(openTerrainDocument(), REQUEST_IMPORT_BUILDING_HEIGHT)
    }

    private fun importBuildingHeight(uri: Uri) {
        val roi = editedRoi.takeIf { it.size >= 3 } ?: mission?.roi
            ?: return reject(activity.getString(R.string.generate_planning_area_first))
        val groundAsset = terrainSources.bareEarth
            ?: return reject(activity.getString(R.string.download_dem_before_building_height))
        val ground = groundAsset.source
        binding.dsmDownloadProgress.visibility = View.VISIBLE
        operationMessage = activity.getString(R.string.reading_building_height_geotiff)
        renderStatus()
        worker.execute {
            val result = runCatching {
                val bytes = activity.contentResolver.openInputStream(uri)?.use {
                    TerrainImportSafety.readBounded(it, context = activity)
                }
                    ?: error(activity.getString(R.string.cannot_read_building_relative_height))
                val name = uri.lastPathSegment ?: activity.getString(R.string.building_relative_height_geotiff)
                val buildingHeight = GeoTiffTerrain.read(bytes.inputStream(), name, activity)
                TerrainImportSafety.requireCompleteCoverage(buildingHeight, roi, activity)
                val heightPreview = buildingHeight.previewForArea(roi, 48, 32)
                require(heightPreview.elevations.none { it.isFinite() && it < 0.0 }) {
                    activity.getString(R.string.building_relative_height_negative)
                }
                val surface = CompositeSurfaceElevationSource(
                    ground,
                    buildingHeight,
                    activity.getString(R.string.surface_dsm_bare_earth_local_buildings),
                    activity,
                )
                val heightSha256 = SurveyTerrainPlanner.sha256(bytes)
                Triple(surface, heightSha256, previewTerrain(surface, roi, 96, 64))
            }
            activity.runOnUiThread {
                binding.dsmDownloadProgress.visibility = View.GONE
                result.onSuccess { (surface, heightSha256, preview) ->
                    terrainSources.installRelativeHeightSurface(surface, heightSha256)
                    invalidateMissionForTerrainSourceChange(activity.getString(R.string.building_surface_source_replaced_regenerate))
                    terrainPreview = preview
                    showTerrainPreview(preview)
                    binding.terrainKind.setSelection(TERRAIN_KIND_SURFACE_DSM)
                    binding.dsmBuildingConfirm.isChecked = false
                    binding.dsmBuildingConfirm.isEnabled = true
                    binding.followTerrain.isChecked = true
                    operationMessage = activity.getString(R.string.local_building_height_ready)
                    renderTerrainStatus()
                    renderStatus()
                }.onFailure { reject(activity.getString(R.string.building_height_import_failed, it.message.orEmpty())) }
            }
        }
    }

    private fun downloadBuildingDsm() {
        val roi = editedRoi.takeIf { it.size >= 3 } ?: mission?.roi
            ?: return reject(activity.getString(R.string.generate_planning_area_first))
        val groundAsset = terrainSources.bareEarth
            ?: return reject(activity.getString(R.string.download_dem_before_building_overlay))
        val ground = groundAsset.source
        val template = binding.buildingCogTemplate.text.toString().trim()
        if (template.isBlank()) return reject(activity.getString(R.string.cog_url_template_required))
        activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE).edit()
            .putString(KEY_BUILDING_COG_TEMPLATE, template)
            .apply()
        binding.dsmDownloadProgress.visibility = View.VISIBLE
        binding.downloadBuildingDsm.isEnabled = false
        operationMessage = activity.getString(R.string.downloading_global_building_tiles)
        renderStatus()
        worker.execute {
            val result = runCatching {
                GlobalBuildingHeightDownloader(activity, template).download(roi).also {
                    TerrainImportSafety.requireCompleteCoverage(it.heightAboveGround, roi, activity)
                }
            }
            activity.runOnUiThread {
                binding.dsmDownloadProgress.visibility = View.GONE
                binding.downloadBuildingDsm.isEnabled = true
                result.onSuccess { downloaded ->
                    val surface = CompositeSurfaceElevationSource(
                        ground,
                        downloaded.heightAboveGround,
                        activity.getString(R.string.surface_dsm_bare_earth_buildings),
                        activity,
                    )
                    terrainSources.installRelativeHeightSurface(surface, downloaded.sha256)
                    invalidateMissionForTerrainSourceChange(activity.getString(R.string.building_surface_source_replaced_regenerate))
                    binding.terrainKind.setSelection(TERRAIN_KIND_SURFACE_DSM)
                    binding.dsmBuildingConfirm.isChecked = false
                    binding.dsmBuildingConfirm.isEnabled = true
                    binding.followTerrain.isChecked = true
                    terrainPreview = previewTerrain(surface, roi, 96, 64)
                    showTerrainPreview(checkNotNull(terrainPreview))
                    val cacheStatus = activity.getString(
                        if (downloaded.cached) R.string.all_tiles_cached else R.string.cache_updated,
                    )
                    operationMessage = downloaded.sourceVersion?.let { version ->
                        activity.getString(
                            R.string.global_surface_dsm_composed_versioned,
                            downloaded.tileCount,
                            cacheStatus,
                            version,
                        )
                    } ?: activity.getString(
                        R.string.global_surface_dsm_composed,
                        downloaded.tileCount,
                        cacheStatus,
                    )
                    exportDownloadedDsm(downloaded.cacheFiles, 0, mutableListOf())
                    renderTerrainStatus()
                    renderStatus()
        }.onFailure { reject(activity.getString(R.string.building_dsm_download_failed, it.message.orEmpty())) }
            }
        }
    }

    private fun showTerrainPreview(preview: TerrainPreviewData) {
        binding.surveyPreview.showTerrain(
            preview.info,
            preview.elevations,
            preview.columns,
            preview.rows,
            activity.getString(
                if (binding.terrainKind.selectedItemPosition == TERRAIN_KIND_BARE_EARTH) {
                    R.string.terrain_label_dem
                } else {
                    R.string.terrain_label_dsm
                },
            ),
        )
        renderTerrainAltitudeLegend()
    }

    private fun renderTerrainAltitudeLegend() {
        val plan = mission?.terrainPlan
        if (!binding.followTerrain.isChecked || plan == null) {
            binding.terrainAltitudeLegend.clearRange()
            return
        }
        binding.terrainAltitudeLegend.showRange(
            plan.minimumWaypointAltitudeMeters,
            plan.maximumWaypointAltitudeMeters,
        )
    }

    private fun invalidateMissionForTerrainSourceChange(message: String) {
        if (mission != null) invalidateGeneratedMission(message)
    }

    private fun renderTerrainStatus() {
        val loaded = terrain
        if (loaded == null || !binding.followTerrain.isChecked) {
            if (loaded == null) {
                binding.dsmStatus.setText(R.string.terrain_data_missing)
            } else {
            binding.dsmStatus.setText(R.string.terrain_disabled_status)
            }
            binding.surveyPreview.clearTerrain()
            binding.terrainAltitudeLegend.clearRange()
            return
        }
        val info = loaded.info
        val kind = if (binding.terrainKind.selectedItemPosition == TERRAIN_KIND_BARE_EARTH) {
            activity.getString(R.string.terrain_bare_earth_dem_dtm)
        } else {
            activity.getString(R.string.terrain_surface_dsm_buildings_canopy)
        }
        binding.dsmStatus.text = buildString {
            append("$kind · ${info.displayName}\n")
            append("EPSG:${info.epsg} · ${info.width}×${info.height} · ")
            append(activity.getString(if (binding.followTerrain.isChecked) R.string.terrain_on else R.string.terrain_off_constant_altitude))
            if (binding.terrainKind.selectedItemPosition == TERRAIN_KIND_SURFACE_DSM &&
                !binding.dsmBuildingConfirm.isChecked
            ) append(activity.getString(R.string.pending_review_suffix))
        }
    }

    private fun exportDownloadedDsm(files: List<File>, index: Int, exported: MutableList<String>) {
        if (index >= files.size) {
            operationMessage = activity.getString(R.string.building_height_source_saved, exported.joinToString("\n"))
            renderStatus()
            return
        }
        val file = files[index]
        artifactStore.saveFile("building-height-source", file.name, "image/tiff", file) { result ->
            result.onSuccess(exported::add)
                .onFailure {
                    exported += activity.getString(R.string.export_file_failed, file.name, it.message.orEmpty())
                }
            exportDownloadedDsm(files, index + 1, exported)
        }
    }

    private fun previewTerrain(
        source: TerrainElevationSource,
        roi: List<GeoPoint>,
        columns: Int = 72,
        rows: Int = 48,
    ): TerrainPreviewData = TerrainPreviewSampler.forArea(source, roi, columns, rows, activity)

    private fun generateSafely(obliqueFiveDirection: Boolean) {
        runCatching { generate(obliqueFiveDirection) }
            .onFailure {
                val reason = it.message ?: it.javaClass.simpleName
            reject(activity.getString(R.string.route_generation_failed, reason))
                AlertDialog.Builder(activity)
                    .setTitle(R.string.mission_not_generated)
                    .setMessage(reason)
                    .setPositiveButton(R.string.action_got_it, null)
                    .show()
            }
    }

    private fun exportKmz() {
        val current = mission ?: return reject(activity.getString(R.string.no_current_mission))
        if (!plannerFieldsMatch(current)) {
            return reject(activity.getString(R.string.route_parameters_changed_export_kmz))
        }
        if (preparingDjiExecution) return reject(activity.getString(R.string.dji_mission_package_busy))
        preparingDjiExecution = true
        prepareKmz(current) { file ->
            preparingDjiExecution = false
            artifactStore.saveFile(
                "survey",
                file.name,
                "application/vnd.google-earth.kmz",
                file,
            ) { result ->
                operationMessage = result.fold(
                    onSuccess = { activity.getString(R.string.kmz_exported_public, it) },
                    onFailure = { activity.getString(R.string.kmz_export_failed, it.message.orEmpty()) },
                )
                renderStatus()
            }
        }
    }

    private fun prepareKmz(current: SurveyMission, onReady: (File) -> Unit) {
        val globalRthHeight = requiredGlobalRthHeight(current)
        val cameraSelection = cameraDiscovery.current()
        val cameraIndex = cameraSelection.index
        val payloadPositionIndex = DjiV5CameraDiscovery.wpmzPayloadPositionIndex(cameraIndex)
        if (payloadPositionIndex == null) {
            preparingDjiExecution = false
            reject(activity.getString(R.string.camera_index_not_mappable, cameraIndex.name))
            return
        }
        val payloadLensType = DjiWpmzPayloadLens.fromStreamSourceName(cameraSelection.streamSource?.name)
        if (payloadLensType == null) {
            preparingDjiExecution = false
            reject(activity.getString(R.string.camera_source_not_mappable, cameraSelection.streamSource?.name ?: "--"))
            return
        }
        val existing = kmzFile?.takeIf {
            it.isFile && kmzMissionId == current.id &&
                kotlin.math.abs((kmzRthHeightMeters ?: Double.NaN) - globalRthHeight) < 0.1 &&
                kmzPayloadPositionIndex == payloadPositionIndex &&
                kmzPayloadLensName == payloadLensType.name &&
                kotlin.math.abs((kmzExecutionSpeedMetersPerSecond ?: Double.NaN) - current.constraints.speedMetersPerSecond) < 0.01 &&
                kotlin.math.abs((kmzObliqueSpeedMetersPerSecond ?: Double.NaN) - current.constraints.obliqueSpeedMetersPerSecond) < 0.01 &&
                kotlin.math.abs((kmzTakeoffSpeedMetersPerSecond ?: Double.NaN) - current.constraints.takeoffSpeedMetersPerSecond) < 0.01
        }
        if (existing != null) {
            onReady(existing)
            return
        }
        operationMessage = activity.getString(R.string.wpmz_generating_validating)
        renderStatus()
        val requestGeneration = ++kmzPreparationGeneration
        worker.execute {
            // Each generation gets its own file. A cancelled/older worker can
            // therefore never overwrite the file selected for a newer mission.
            val output = File(activity.cacheDir, "waylines/${current.id}-$requestGeneration.kmz")
            val result = runCatching {
                DjiWpmzPackageWriter(activity).generate(
                    current,
                    output,
                    globalRthHeightMeters = globalRthHeight,
                    payloadPositionIndex = payloadPositionIndex,
                    payloadLensType = payloadLensType,
                )
            }
            activity.runOnUiThread {
                if (requestGeneration != kmzPreparationGeneration || mission?.id != current.id) {
                    output.delete()
                    return@runOnUiThread
                }
                result.onSuccess { validation ->
                    kmzFile = output.takeIf { validation.valid }
                    kmzMissionId = current.id.takeIf { validation.valid }
                    kmzRthHeightMeters = globalRthHeight.takeIf { validation.valid }
                    kmzPayloadPositionIndex = payloadPositionIndex.takeIf { validation.valid }
                    kmzPayloadLensName = payloadLensType.name.takeIf { validation.valid }
                    kmzExecutionSpeedMetersPerSecond = current.constraints.speedMetersPerSecond.takeIf { validation.valid }
                    kmzObliqueSpeedMetersPerSecond = current.constraints.obliqueSpeedMetersPerSecond.takeIf { validation.valid }
                    kmzTakeoffSpeedMetersPerSecond = current.constraints.takeoffSpeedMetersPerSecond.takeIf { validation.valid }
                    if (validation.valid) {
                        onReady(output)
                    } else {
                        preparingDjiExecution = false
                    operationMessage = activity.getString(R.string.kmz_validation_failed, validation.details)
                    }
                }.onFailure { error ->
                    preparingDjiExecution = false
                    kmzFile = null
                    kmzMissionId = null
                    kmzRthHeightMeters = null
                    kmzPayloadPositionIndex = null
                    kmzPayloadLensName = null
                    kmzExecutionSpeedMetersPerSecond = null
                    kmzObliqueSpeedMetersPerSecond = null
                    kmzTakeoffSpeedMetersPerSecond = null
                    uploadedKmzSessionSignature = null
                    operationMessage = activity.getString(R.string.kmz_generation_failed, error.message ?: error.javaClass.simpleName)
                }
                renderStatus()
            }
        }
    }

    private fun uploadWayline() {
        val current = mission ?: return reject(activity.getString(R.string.no_current_mission))
        if (!plannerFieldsMatch(current)) {
            return reject(activity.getString(R.string.route_parameters_changed_upload_kmz))
        }
        val file = currentKmzFile(current)
            ?: return reject(activity.getString(R.string.kmz_inputs_changed_regenerate))
        if (!snapshot().connected) return reject(activity.getString(R.string.dji_upload_aircraft_disconnected))
        operationMessage = activity.getString(R.string.upload_starting_file, file.name)
        renderStatus()
        waylinePort.upload(file.absolutePath) { result ->
            activity.runOnUiThread {
                uploadedKmzSessionSignature = result.fold(
                    onSuccess = { kmzSessionSignature(file) },
                    onFailure = { null },
                )
                operationMessage = result.fold(
                    { activity.getString(R.string.kmz_upload_complete) },
                    { activity.getString(R.string.kmz_upload_failed, it.message.orEmpty()) },
                )
                renderStatus()
            }
        }
    }

    private fun exportArtifacts() {
        val current = mission ?: return reject(activity.getString(R.string.no_current_mission))
        if (!plannerFieldsMatch(current)) {
            return reject(activity.getString(R.string.route_parameters_changed_export_task))
        }
        val prefix = current.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val artifacts = mutableListOf(
            Triple("$prefix.mission.json", "application/json", SurveyMissionJson.encode(current).toByteArray()),
        )
        currentKmzFile(current)?.let { file ->
            artifacts += Triple("$prefix.kmz", "application/vnd.google-earth.kmz", file.readBytes())
        }
        bestAvailableCheckpoint(current)?.let { checkpoint ->
            artifacts += Triple(
                "$prefix.checkpoint.json",
                "application/json",
                SurveyExecutionCheckpointJson.encode(checkpoint).toByteArray(),
            )
        }
        customExecution.checkpoint()?.let { checkpoint ->
            artifacts += Triple(
                "$prefix.custom-checkpoint.json",
                "application/json",
                SurveyExecutionCheckpointJson.encode(checkpoint).toByteArray(),
            )
        }
        operationMessage = activity.getString(R.string.exporting_files, artifacts.size)
        renderStatus()
        val exported = mutableListOf<String>()
        fun save(index: Int) {
            if (index >= artifacts.size) {
                operationMessage = activity.getString(R.string.export_complete_files, exported.joinToString("\n"))
                renderStatus()
                return
            }
            val (name, mime, bytes) = artifacts[index]
            artifactStore.save("survey", name, mime, bytes) { result ->
                result.onSuccess(exported::add)
                    .onFailure { error -> exported += activity.getString(R.string.export_file_failed, name, error.message.orEmpty()) }
                save(index + 1)
            }
        }
        save(0)
    }

    private fun executeWayline() {
        val current = mission ?: return reject(activity.getString(R.string.generate_or_import_task_first))
        val file = currentKmzFile(current)
            ?: return reject(activity.getString(R.string.kmz_inputs_changed_reupload))
        if (!snapshot().connected) return reject(activity.getString(R.string.dji_execute_aircraft_disconnected))
        if (!WaylineMissionName.matches(waylineState.missionFileName, file.name) ||
            waylineState.phase != edu.playground.djivln.domain.wayline.WaylinePhase.READY
        ) {
            return reject(activity.getString(R.string.route_not_uploaded_ready))
        }
        if (!preflight()) return
        clearPersistedCheckpoint()
        completedDjiMissionFileName = null
        pendingDjiContinuationWaylineId = null
        armDjiAppCapture(current)
        operation("execute", activity.getString(R.string.action_execute)) { completion ->
            val firstWaylineId = DjiWaylinePartition.segments(current, activity).first().waylineId
            recordSurveyEvent(
                "wayline_start_strategy",
                mapOf(
                    "strategy" to "explicit_first_wayline_id",
                    "wayline_id" to firstWaylineId,
                ),
            )
            waylinePort.execute(file.name, listOf(firstWaylineId)) { result ->
                if (result.isFailure) {
                    uploadedKmzSessionSignature = null
                    stopDjiAppCapture()
                }
                completion.complete(result)
            }
        }
    }

    private fun handlePartitionedDjiMissionState(previous: WaylineState, state: WaylineState): Boolean {
        val current = mission ?: return false
        val segments = DjiWaylinePartition.segments(current, activity)
        if (segments.size <= 1) return false
        val missionFileName = state.missionFileName ?: kmzFile?.name ?: return false
        if (state.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED &&
            previous.phase in DJI_RECOVERABLE_ACTIVE_PHASES
        ) {
            val completedWaylineId = state.waylineId ?: previous.waylineId ?: return false
            val completedLocalWaypoint = state.waypointIndex ?: previous.waypointIndex
            if (!DjiWaylinePartition.reachedSegmentEnd(
                    current,
                    completedWaylineId,
                    completedLocalWaypoint,
                    activity,
                )
            ) {
                recordSurveyEvent(
                    "wayline_partition_finished_before_segment_end",
                    mapOf(
                        "wayline_id" to completedWaylineId,
                        "local_waypoint_index" to completedLocalWaypoint,
                        "reason" to "external_interruption_or_rth",
                    ),
                )
                return false
            }
            val nextWaylineId = DjiWaylinePartition.remainingWaylineIds(
                current,
                completedWaylineId,
                activity,
            ).firstOrNull()
            if (nextWaylineId == null) {
                completePartitionedDjiMission(current, missionFileName)
                return false
            }
            pendingDjiContinuationWaylineId = nextWaylineId
            continuingDjiWaylines = false
            djiPartitionContinuationGeneration += 1
            operationMessage = activity.getString(R.string.internal_segment_complete_waiting, completedWaylineId + 1, segments.size)
            recordSurveyEvent(
                "wayline_partition_waiting",
                mapOf(
                    "completed_wayline_id" to completedWaylineId,
                    "next_wayline_id" to nextWaylineId,
                ),
            )
            return true
        }
        val nextWaylineId = pendingDjiContinuationWaylineId
        if (state.phase != edu.playground.djivln.domain.wayline.WaylinePhase.READY ||
            nextWaylineId == null || continuingDjiWaylines || preparingDjiExecution
        ) return false
        if (isDjiReturnOrLandingActive()) {
            preservePendingDjiPartitionContinuation(current, nextWaylineId, activity.getString(R.string.rth_or_landing_intervention))
            return true
        }
        continuingDjiWaylines = true
        val continuationGeneration = ++djiPartitionContinuationGeneration
        operationMessage = activity.getString(R.string.internal_segment_resuming, nextWaylineId + 1, segments.size)
        recordSurveyEvent(
            "wayline_partition_continue_scheduled",
            mapOf("next_wayline_id" to nextWaylineId),
        )
        persistenceHandler.postDelayed({
            if (continuationGeneration != djiPartitionContinuationGeneration) return@postDelayed
            if (pendingDjiContinuationWaylineId != nextWaylineId || preparingDjiExecution ||
                waylineState.phase != edu.playground.djivln.domain.wayline.WaylinePhase.READY
            ) {
                continuingDjiWaylines = false
                return@postDelayed
            }
            if (isDjiReturnOrLandingActive()) {
                continuingDjiWaylines = false
                preservePendingDjiPartitionContinuation(current, nextWaylineId, activity.getString(R.string.rth_or_landing_intervention))
                renderStatus()
                return@postDelayed
            }
            startPendingDjiPartition(current, missionFileName, nextWaylineId, previous, state)
        }, DJI_PARTITION_CONTINUATION_SETTLE_MILLIS)
        return true
    }

    private fun startPendingDjiPartition(
        current: SurveyMission,
        missionFileName: String,
        nextWaylineId: Int,
        previous: WaylineState,
        state: WaylineState,
    ) {
        if (!recaptureExecutionAllowed(current)) return
        val segmentCount = DjiWaylinePartition.segments(current, activity).size
        operationMessage = activity.getString(R.string.internal_segment_starting, nextWaylineId + 1, segmentCount)
        recordSurveyEvent(
            "wayline_partition_continuing",
            mapOf("next_wayline_id" to nextWaylineId),
        )
        val nextBreakpoint = WaylineBreakpoint(
            waylineId = nextWaylineId,
            waypointId = 0,
            segmentProgress = 0.0,
        )
        waylinePort.executeFromBreakpoint(missionFileName, nextBreakpoint) { result ->
            activity.runOnUiThread {
                continuingDjiWaylines = false
                result.onSuccess {
                    pendingDjiContinuationWaylineId = null
                    operationMessage = activity.getString(R.string.internal_segment_started, nextWaylineId + 1, segmentCount)
                }.onFailure { error ->
                    stopDjiAppCapture()
                    operationMessage = activity.getString(R.string.internal_segment_start_failed, nextWaylineId + 1, error.message.orEmpty())
                    preserveDjiRecoveryAfterUnexpectedStop(previous, state)
                    persistDjiCheckpoint(state)
                }
                recordSurveyEvent(
                    "wayline_partition_continue_result",
                    mapOf(
                        "next_wayline_id" to nextWaylineId,
                        "success" to result.isSuccess,
                        "error" to result.exceptionOrNull()?.message,
                    ),
                )
                renderStatus()
            }
        }
    }

    private fun preservePendingDjiPartitionContinuation(
        current: SurveyMission,
        nextWaylineId: Int,
        reason: String,
    ) {
        val segment = DjiWaylinePartition.segments(current, activity)
            .firstOrNull { it.waylineId == nextWaylineId } ?: return
        val breakpoint = WaylineBreakpoint(
            waylineId = nextWaylineId,
            waypointId = 0,
            segmentProgress = 0.0,
        )
        val checkpoint = SurveyExecutionCheckpoint(
            missionId = current.id,
            waypointIndex = segment.firstGlobalWaypointIndex,
            state = edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = segment.firstGlobalWaypointIndex,
            phase = edu.playground.djivln.survey.SurveyExecutionPhase.SURVEY,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = breakpoint,
        )
        djiPartitionContinuationGeneration += 1
        continuingDjiWaylines = false
        pendingDjiContinuationWaylineId = null
        restoredDjiCheckpoint = PendingCheckpointRestore(
            current,
            checkpoint,
            SurveyExecutionBackend.DJI_KMZ,
            "",
            reason,
        )
        activeDjiRecoveryDisplayCheckpoint = checkpoint
        persistCheckpoint(checkpoint, commit = false)
        operationMessage = activity.getString(R.string.next_internal_segment_saved, reason, nextWaylineId + 1)
        recordSurveyEvent(
            "wayline_partition_deferred_for_external_control",
            mapOf(
                "next_wayline_id" to nextWaylineId,
                "global_waypoint_index" to segment.firstGlobalWaypointIndex,
                "reason" to reason,
            ),
        )
    }

    private fun isDjiReturnOrLandingActive(): Boolean {
        val aircraft = snapshot()
        val goHome = aircraft.goHomeState?.uppercase().orEmpty()
        val mode = aircraft.flightMode?.uppercase().orEmpty()
        return (goHome.isNotBlank() && goHome != "IDLE") ||
            mode.contains("GOHOME") || mode.contains("GO_HOME") || mode.contains("LANDING")
    }

    private fun completePartitionedDjiMission(current: SurveyMission, missionFileName: String) {
        if (completedDjiMissionFileName == missionFileName) return
        completedDjiMissionFileName = missionFileName
        when (current.constraints.completionAction) {
            SurveyCompletionAction.HOVER -> {
                    operationMessage = activity.getString(R.string.all_segments_complete_hovering)
            }
            SurveyCompletionAction.RETURN_TO_HOME -> {
                    operationMessage = activity.getString(R.string.all_segments_complete_rth)
                flightControlPort.returnHome { result ->
                    activity.runOnUiThread {
                        operationMessage = result.fold(
                            { activity.getString(R.string.all_routes_complete_rth_accepted) },
                            { activity.getString(R.string.all_routes_complete_rth_failed, it.message.orEmpty()) },
                        )
                        recordSurveyEvent(
                            "wayline_partition_completion_action",
                            mapOf(
                                "action" to current.constraints.completionAction.name,
                                "success" to result.isSuccess,
                                "error" to result.exceptionOrNull()?.message,
                            ),
                        )
                        renderStatus()
                    }
                }
            }
            SurveyCompletionAction.RETURN_TO_ROUTE_START -> {
                    operationMessage = activity.getString(R.string.all_segments_complete_manual_rth)
                recordSurveyEvent(
                    "wayline_partition_completion_action",
                    mapOf(
                        "action" to current.constraints.completionAction.name,
                        "success" to false,
                        "error" to "multi-wayline return-to-route-start requires explicit follow-up mission",
                    ),
                )
            }
        }
    }

    private fun startDjiCaptureGimbal(index: dji.sdk.keyvalue.value.common.ComponentIndexType) {
        if (index == dji.sdk.keyvalue.value.common.ComponentIndexType.UNKNOWN) return
        if (djiCaptureGimbalIndex != index || djiCaptureGimbal == null) {
            djiCaptureGimbal?.stop()
            djiCaptureGimbal = DjiV5GimbalPort(index).also { it.start { } }
            djiCaptureGimbalIndex = index
        }
        djiCaptureTargetPitchDegrees = null
        djiCaptureGimbalCommandAcceptedAtMillis = 0L
        djiCaptureGimbalLastCommandAtMillis = 0L
        djiCaptureGimbalSettlingStartedAtMillis = 0L
        djiCaptureGimbalVerificationStartedAtMillis = 0L
        djiCaptureGimbalTimeoutHandled = false
        djiCaptureGimbalLastGateLogAtMillis = 0L
        resetDjiCapturePoseGate()
    }

    private fun stopDjiAppCapture() {
        if (waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED) {
            djiCaptureCoordinator.finish()
        }
        reportMissedRecapturePoints()
        djiCaptureCoordinator.stop()
        djiContinuousCaptureIndices = emptySet()
        djiCaptureGimbal?.stop()
        djiCaptureGimbal = null
        djiCaptureGimbalIndex = dji.sdk.keyvalue.value.common.ComponentIndexType.UNKNOWN
        djiCaptureTargetPitchDegrees = null
        djiCaptureGimbalCommandAcceptedAtMillis = 0L
        djiCaptureGimbalLastCommandAtMillis = 0L
        djiCaptureGimbalSettlingStartedAtMillis = 0L
        djiCaptureGimbalVerificationStartedAtMillis = 0L
        djiCaptureGimbalTimeoutHandled = false
        djiCaptureGimbalLastGateLogAtMillis = 0L
        resetDjiCapturePoseGate()
    }

    private fun resetDjiCapturePoseGate() {
        djiCapturePoseWaypointIndex = null
        djiCapturePoseStableSinceMillis = 0L
        djiCapturePoseVerificationStartedAtMillis = 0L
        djiCapturePoseTimeoutHandled = false
        djiCapturePoseLastGateLogAtMillis = 0L
    }

    private fun verifyDjiCaptureGimbal(
        target: DjiKmzAppCaptureCoordinator.GimbalTarget,
        actualPitchDegrees: Double?,
        nowElapsedMillis: Long,
    ): Boolean {
        val port = djiCaptureGimbal ?: return false
        val targetChanged = djiCaptureTargetPitchDegrees?.let {
            kotlin.math.abs(it - target.pitchDegrees) > 0.01
        } != false
        if (targetChanged) {
            djiCaptureTargetPitchDegrees = target.pitchDegrees
            djiCaptureGimbalCommandAcceptedAtMillis = 0L
            djiCaptureGimbalLastCommandAtMillis = 0L
            djiCaptureGimbalSettlingStartedAtMillis = nowElapsedMillis
            djiCaptureGimbalVerificationStartedAtMillis = nowElapsedMillis
            djiCaptureGimbalTimeoutHandled = false
        }
        val actual = actualPitchDegrees ?: port.state().pitchDegrees
        val settled = actual?.let {
            SurveyGimbalSettlePolicy.isSettled(target.pitchDegrees, it)
        } == true
        djiCaptureGimbalSettlingStartedAtMillis = SurveyGimbalSettlePolicy.updateUnsettledSince(
            nowElapsedMillis = nowElapsedMillis,
            unsettledSinceElapsedMillis = djiCaptureGimbalSettlingStartedAtMillis,
            settled = settled,
        )
        if (actual != null && SurveyGimbalSettlePolicy.isVerifiedForCapture(
                targetPitchDegrees = target.pitchDegrees,
                actualPitchDegrees = actual,
                commandAcceptedElapsedMillis = djiCaptureGimbalCommandAcceptedAtMillis,
                nowElapsedMillis = nowElapsedMillis,
            )
        ) return true

        if (SurveyGimbalSettlePolicy.shouldRetry(nowElapsedMillis, djiCaptureGimbalLastCommandAtMillis)) {
            djiCaptureGimbalLastCommandAtMillis = nowElapsedMillis
            recordSurveyEvent(
                "dji_capture_gimbal_command",
                mapOf(
                    "pass_index" to target.passIndex,
                    "capture_view" to target.captureView.name,
                    "target_pitch_deg" to target.pitchDegrees,
                    "actual_pitch_deg" to actual,
                ),
            )
            port.rotateToPitch(target.pitchDegrees, 1.0) { result ->
                activity.runOnUiThread {
                    if (djiCaptureGimbal !== port || djiCaptureTargetPitchDegrees != target.pitchDegrees) {
                        return@runOnUiThread
                    }
                    if (result.isSuccess) {
                        djiCaptureGimbalCommandAcceptedAtMillis = SystemClock.elapsedRealtime()
                    }
                    recordSurveyEvent(
                        "dji_capture_gimbal_command_result",
                        mapOf(
                            "pass_index" to target.passIndex,
                            "capture_view" to target.captureView.name,
                            "target_pitch_deg" to target.pitchDegrees,
                            "success" to result.isSuccess,
                            "error" to result.exceptionOrNull()?.message,
                        ),
                    )
                }
            }
        }
        if (nowElapsedMillis - djiCaptureGimbalLastGateLogAtMillis >=
            SurveyGimbalSettlePolicy.RETRY_INTERVAL_MS
        ) {
            djiCaptureGimbalLastGateLogAtMillis = nowElapsedMillis
            recordSurveyEvent(
                "dji_capture_blocked_gimbal",
                mapOf(
                    "pass_index" to target.passIndex,
                    "capture_view" to target.captureView.name,
                    "target_pitch_deg" to target.pitchDegrees,
                    "actual_pitch_deg" to actual,
                    "command_accepted_at_ms" to djiCaptureGimbalCommandAcceptedAtMillis,
                    "timed_out" to SurveyGimbalSettlePolicy.hasTimedOut(
                        nowElapsedMillis,
                        djiCaptureGimbalSettlingStartedAtMillis,
                    ),
                ),
            )
        }
        if (!djiCaptureGimbalTimeoutHandled && SurveyGimbalSettlePolicy.hasTimedOut(
                nowElapsedMillis,
                djiCaptureGimbalVerificationStartedAtMillis,
            )
        ) {
            djiCaptureGimbalTimeoutHandled = true
            val reason = activity.getString(R.string.dji_capture_gimbal_timeout)
            recordSurveyEvent(
                "dji_capture_gimbal_timeout",
                mapOf(
                    "pass_index" to target.passIndex,
                    "target_pitch_deg" to target.pitchDegrees,
                    "actual_pitch_deg" to actual,
                    "command_accepted_at_ms" to djiCaptureGimbalCommandAcceptedAtMillis,
                ),
            )
            pauseDjiAndPersistRecovery(reason)
        }
        return false
    }

    private fun armDjiAppCapture(current: SurveyMission, breakpoint: WaylineBreakpoint? = null) {
        val strategy = DjiCameraProfileCatalog.waylineCaptureStrategy(current.cameraProfile)
        if (strategy != DjiWaylineCaptureStrategy.ANDROID_DISTANCE_TRIGGER) {
            stopDjiAppCapture()
            recordSurveyEvent(
                "dji_capture_strategy",
                mapOf(
                    "mission_id" to current.id,
                    "camera_profile" to current.cameraProfile.id,
                    "strategy" to strategy.name,
                ),
            )
            return
        }
        val cameraIndex = cameraDiscovery.current().index
        djiCaptureCamera.bind(cameraIndex, force = true)
        djiCaptureCamera.selectPhotoMode()
        startDjiCaptureGimbal(cameraIndex)
        djiContinuousCaptureIndices = DjiWpmzRoutePolicy.continuousCaptureIndices(current)
        if (breakpoint == null) djiMissedCapturePasses.clear()
        if (breakpoint == null) {
            djiCaptureCoordinator.arm(current, djiContinuousCaptureIndices)
        } else {
            val globalWaypointIndex = djiGlobalWaypointIndex(
                current,
                breakpoint.waylineId,
                breakpoint.waypointId,
            ) ?: breakpoint.waypointId
            djiCaptureCoordinator.armFromBreakpoint(
                current,
                breakpoint.copy(waypointId = globalWaypointIndex),
                djiContinuousCaptureIndices,
            )
        }
        recordSurveyEvent(
            "dji_app_capture_armed",
            mapOf(
                "mission_id" to current.id,
                "camera_profile" to current.cameraProfile.id,
                "minimum_interval_s" to current.cameraProfile.minimumCaptureIntervalSeconds,
                "capture_mode" to current.constraints.captureTriggerMode.name,
                "strategy" to "ANDROID_DISTANCE_TRIGGER",
            ),
        )
    }

    private fun updateDjiAppCapture() {
        val aircraft = effectiveSnapshot()
        if (DjiWaylineTelemetryPolicy.confirmsExecution(
                captureArmed = djiCaptureCoordinator.active,
                phase = waylineState.phase,
                aircraft = aircraft,
            )
        ) {
            val previousPhase = waylineState.phase
            if (waylinePort.confirmExecutionFromTelemetry()) {
                recordSurveyEvent(
                    "wayline_execution_telemetry_confirmed",
                    mapOf(
                        "previous_phase" to previousPhase.name,
                        "flight_mode" to aircraft.flightMode,
                        "is_flying" to aircraft.isFlying,
                        "mission_file_name" to waylineState.missionFileName,
                    ),
                )
            }
        }
        if (!djiCaptureCoordinator.active ||
            waylineState.phase != edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING
        ) return
        val location = aircraft.aircraftLocation ?: return
        val position = GeoPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = aircraft.relativeAltitudeMeters ?: location.altitudeMeters ?: 0.0,
        )
        val globalWaypointIndex = djiGlobalWaypointIndex(
            mission ?: return,
            waylineState.waylineId ?: waylineState.breakpoint?.waylineId,
            waylineState.waypointIndex ?: waylineState.breakpoint?.waypointId,
        )
        val gimbalTarget = djiCaptureCoordinator.currentGimbalTarget(globalWaypointIndex)
        reportMissedRecapturePoints()
        val continuousTarget = gimbalTarget?.takeIf { it.waypointIndex in djiContinuousCaptureIndices }
            ?.let { mission?.waypoints?.getOrNull(it.waypointIndex) }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val velocity = aircraft.velocity
        val horizontalSpeed = if (velocity == null) 0.0 else kotlin.math.hypot(velocity.north, velocity.east)
        val stoppedTarget = gimbalTarget?.takeIf { continuousTarget == null }
            ?.let { mission?.waypoints?.getOrNull(it.waypointIndex) }
        if (djiCapturePoseWaypointIndex != gimbalTarget?.waypointIndex) {
            djiCapturePoseWaypointIndex = gimbalTarget?.waypointIndex
            djiCapturePoseStableSinceMillis = 0L
            djiCapturePoseVerificationStartedAtMillis = nowElapsedMillis
            djiCapturePoseTimeoutHandled = false
            djiCapturePoseLastGateLogAtMillis = 0L
        }
        val capturePoseReady = when {
            continuousTarget != null -> ContinuousCapturePosePolicy.ready(
                aircraft,
                continuousTarget,
                nowNanos,
            )
            gimbalTarget == null || stoppedTarget == null -> true
            else -> {
                val gimbalVerified = verifyDjiCaptureGimbal(
                    target = gimbalTarget,
                    actualPitchDegrees = aircraft.gimbalPitchDegrees,
                    nowElapsedMillis = nowElapsedMillis,
                )
                val aligned = gimbalVerified && StoppedCapturePosePolicy.aligned(
                    aircraft = aircraft,
                    target = stoppedTarget,
                    position = position,
                    horizontalSpeedMetersPerSecond = horizontalSpeed,
                    nowNanos = nowNanos,
                )
                djiCapturePoseStableSinceMillis = StoppedCapturePosePolicy.updatedStableSince(
                    aligned,
                    djiCapturePoseStableSinceMillis,
                    nowElapsedMillis,
                )
                val stable = StoppedCapturePosePolicy.stable(
                    djiCapturePoseStableSinceMillis,
                    nowElapsedMillis,
                )
                if (!stable && nowElapsedMillis - djiCapturePoseLastGateLogAtMillis >= 1_000L) {
                    djiCapturePoseLastGateLogAtMillis = nowElapsedMillis
                    recordSurveyEvent(
                        "dji_capture_blocked_pose",
                        mapOf(
                            "waypoint_index" to gimbalTarget.waypointIndex,
                            "target_heading_deg" to stoppedTarget.headingDegrees,
                            "actual_heading_deg" to aircraft.headingDegrees,
                            "target_pitch_deg" to stoppedTarget.gimbalPitchDegrees,
                            "actual_pitch_deg" to aircraft.gimbalPitchDegrees,
                            "horizontal_speed_mps" to horizontalSpeed,
                            "stable_ms" to if (djiCapturePoseStableSinceMillis > 0L) {
                                nowElapsedMillis - djiCapturePoseStableSinceMillis
                            } else 0L,
                        ),
                    )
                }
                if (!stable && !djiCapturePoseTimeoutHandled &&
                    SurveyGimbalSettlePolicy.hasTimedOut(
                        nowElapsedMillis,
                        djiCapturePoseVerificationStartedAtMillis,
                    )
                ) {
                    djiCapturePoseTimeoutHandled = true
                    pauseDjiAndPersistRecovery(activity.getString(R.string.dji_capture_pose_timeout))
                }
                stable
            }
        }
        val cameraIndex = cameraDiscovery.current().index
        if (djiCaptureCamera.currentSnapshot().cameraIndex != cameraIndex) {
            djiCaptureCamera.bind(cameraIndex, force = true)
            return
        }
        val camera = djiCaptureCamera.currentSnapshot()
        val cameraReady = camera.connected && !camera.recording && !camera.busy &&
            !camera.shootingPhoto && !camera.storingPhoto && camera.storageState == "INSERTED" &&
            capturePoseReady
        val request = djiCaptureCoordinator.tick(
            position = position,
            waypointIndex = globalWaypointIndex,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            cameraReady = cameraReady,
            horizontalSpeedMetersPerSecond = horizontalSpeed,
        ) ?: return
        recordSurveyEvent(
            "dji_app_capture_requested",
            mapOf(
                "mission_id" to mission?.id,
                "pass_index" to request.passIndex,
                "capture_view" to request.captureView.name,
                "reason" to request.reason,
                "latitude" to position.latitude,
                "longitude" to position.longitude,
                "speed_mps" to horizontalSpeed,
                "target_gimbal_pitch_deg" to gimbalTarget?.pitchDegrees,
                "actual_gimbal_pitch_deg" to aircraft.gimbalPitchDegrees,
            ),
        )
        operationMessage = activity.getString(R.string.route_capture_requested, request.captureView.name, request.reason)
        renderStatus()
        val captureWaypointIndex = request.waypointIndex
        djiCaptureCamera.takePhoto(onTriggered = triggered@{ triggeredAtNanos, triggeredAtEpochMillis ->
            if (!djiCaptureCoordinator.isPending(request)) return@triggered
            captureDjiTriggerFrame(
                SurveyPhotoTrigger(
                    missionId = mission?.id.orEmpty(),
                    backend = SurveyExecutionBackend.DJI_KMZ,
                    passIndex = request.passIndex,
                    waypointIndex = captureWaypointIndex,
                    captureView = request.captureView.name,
                    reason = request.reason,
                    triggeredAtNanos = triggeredAtNanos,
                    triggeredAtEpochMillis = triggeredAtEpochMillis,
                ),
            )
        }) { result ->
            activity.runOnUiThread {
                if (!djiCaptureCoordinator.isPending(request)) return@runOnUiThread
                val completedAircraft = effectiveSnapshot()
                val completedLocation = completedAircraft.aircraftLocation
                val completedPosition = if (completedLocation == null) position else GeoPoint(
                    latitude = completedLocation.latitude,
                    longitude = completedLocation.longitude,
                    altitudeMeters = completedAircraft.relativeAltitudeMeters
                        ?: completedLocation.altitudeMeters ?: position.altitudeMeters,
                )
                djiCaptureCoordinator.onCaptureResult(
                    completedPosition,
                    SystemClock.elapsedRealtime(),
                    result.isSuccess,
                    request,
                )
                reportMissedRecapturePoints()
                recordSurveyEvent(
                    "dji_app_capture_result",
                    mapOf(
                        "mission_id" to mission?.id,
                        "pass_index" to request.passIndex,
                        "capture_view" to request.captureView.name,
                        "reason" to request.reason,
                        "success" to result.isSuccess,
                        "error" to result.exceptionOrNull()?.message,
                        "latitude" to completedPosition.latitude,
                        "longitude" to completedPosition.longitude,
                    ),
                )
                onPhotoCaptureFeedback(result.isSuccess)
                if (result.isFailure) {
                    operationMessage = activity.getString(R.string.route_capture_failed, result.exceptionOrNull()?.message ?: activity.getString(R.string.unknown_error))
                    renderStatus()
                }
            }
        }
    }

    private fun captureDjiTriggerFrame(trigger: SurveyPhotoTrigger) {
        val cameraIndex = cameraDiscovery.current().index
        if (cameraIndex == dji.sdk.keyvalue.value.common.ComponentIndexType.UNKNOWN) {
            triggerFrameRecorder.captureProvided(trigger, null)
            return
        }
        val source = DjiV5CameraFrameSource(cameraIndex)
        synchronized(pendingTriggerFrameSources) { pendingTriggerFrameSources += source }
        source.start { frame ->
            val accepted = synchronized(pendingTriggerFrameSources) {
                pendingTriggerFrameSources.remove(source)
            }
            source.stop()
            if (accepted) {
                val snapshot = effectiveSnapshot()
                triggerFrameRecorder.captureProvided(
                    trigger,
                    frame,
                    edu.playground.djivln.survey.SurveyFrameTelemetry(
                        sampledAtNanos = SystemClock.elapsedRealtimeNanos(),
                        aircraftLocation = snapshot.aircraftLocation?.let {
                            GeoPoint(it.latitude, it.longitude)
                        },
                        aircraftLocationUpdatedAtNanos = snapshot.aircraftLocationUpdatedAtNanos,
                        altitudeAboveSeaLevelMeters = snapshot.altitudeAboveSeaLevelMeters,
                        relativeAltitudeMeters = snapshot.relativeAltitudeMeters,
                        altitudeAboveGroundMeters = snapshot.altitudeAboveGroundMeters,
                        headingDegrees = snapshot.headingDegrees,
                        headingUpdatedAtNanos = snapshot.headingUpdatedAtNanos,
                        rollDegrees = snapshot.attitude?.roll,
                        pitchDegrees = snapshot.attitude?.pitch,
                        yawDegrees = snapshot.attitude?.yaw,
                        attitudeUpdatedAtNanos = snapshot.attitudeUpdatedAtNanos,
                        velocityNorthMetersPerSecond = snapshot.velocity?.north,
                        velocityEastMetersPerSecond = snapshot.velocity?.east,
                        velocityUpMetersPerSecond = snapshot.velocity?.up,
                        velocityUpdatedAtNanos = snapshot.velocityUpdatedAtNanos,
                        gimbalPitchDegrees = snapshot.gimbalPitchDegrees,
                        gpsSatelliteCount = snapshot.gpsSatelliteCount,
                        gpsSignalLevel = snapshot.gpsSignalLevel,
                        gimbalRollDegrees = snapshot.gimbalRollDegrees,
                        gimbalYawDegrees = snapshot.gimbalYawDegrees,
                        gimbalYawRelativeToAircraftHeadingDegrees =
                            snapshot.gimbalYawRelativeToAircraftHeadingDegrees,
                        gimbalAttitudeUpdatedAtNanos = snapshot.gimbalAttitudeUpdatedAtNanos,
                        gimbalYawRelativeUpdatedAtNanos = snapshot.gimbalYawRelativeUpdatedAtNanos,
                    ),
                )
            }
        }
        source.requestFrame().onFailure { error ->
            val accepted = synchronized(pendingTriggerFrameSources) {
                pendingTriggerFrameSources.remove(source)
            }
            source.stop()
            if (accepted) {
                recordSurveyEvent(
                    "trigger_frame_source_error",
                    mapOf("camera_index" to cameraIndex.name, "error" to error.message),
                )
                triggerFrameRecorder.captureProvided(trigger, null)
            }
        }
        persistenceHandler.postDelayed({
            val timedOut = synchronized(pendingTriggerFrameSources) {
                pendingTriggerFrameSources.remove(source)
            }
            if (timedOut) {
                source.stop()
                triggerFrameRecorder.captureProvided(trigger, null)
            }
        }, TRIGGER_FRAME_REQUEST_TIMEOUT_MILLIS)
    }

    private fun stopPendingTriggerFrameSources() {
        val pending = synchronized(pendingTriggerFrameSources) {
            pendingTriggerFrameSources.toList().also { pendingTriggerFrameSources.clear() }
        }
        pending.forEach(DjiV5CameraFrameSource::stop)
    }

    private fun primaryExecutionAction(): PrimaryExecutionAction = when {
        binding.resumeWayline.isEnabled -> PrimaryExecutionAction.RESUME
        binding.pauseWayline.isEnabled -> PrimaryExecutionAction.PAUSE
        else -> PrimaryExecutionAction.EXECUTE
    }

    private fun handlePrimaryExecutionAction() {
        when (primaryExecutionAction()) {
            PrimaryExecutionAction.EXECUTE -> executeSelectedBackend()
            PrimaryExecutionAction.PAUSE -> pauseSelectedBackend()
            PrimaryExecutionAction.RESUME -> resumeSelectedBackend()
        }
    }

    private fun executeSelectedBackend() {
        var current = mission ?: return reject(activity.getString(R.string.generate_or_import_task_first))
        if (preparingDjiExecution) return reject(activity.getString(R.string.dji_mission_preparing_no_repeat))
        if (selectedBackend() == SurveyExecutionBackend.DJI_KMZ && restoredDjiCheckpoint != null) {
            resumeSelectedBackend()
            return
        }
        if (selectedBackend() == SurveyExecutionBackend.DJI_KMZ &&
            waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED
        ) {
            resumeSelectedBackend()
            return
        }
        if (selectedBackend() != SurveyExecutionBackend.DJI_KMZ &&
            customState.status.state == edu.playground.djivln.survey.SurveyExecutionState.PAUSED
        ) {
            resumeSelectedBackend()
            return
        }
        if (binding.dsmDownloadProgress.visibility == View.VISIBLE) {
            return reject(activity.getString(R.string.terrain_processing_before_execute))
        }
        current = applyActiveRecaptureRouteSpeed(current) ?: return
        if (!preflight(allowPhotoRatioPreparation = selectedBackend() == SurveyExecutionBackend.DJI_KMZ && current.activeMapping == null)) return
        when (selectedBackend()) {
            SurveyExecutionBackend.DJI_KMZ -> prepareUploadAndExecuteDji(current)
            SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK,
            SurveyExecutionBackend.UE_HIL -> customExecution.start(
                current,
                selectedBackend(),
                binding.ueEndpoint.text.toString(),
            )
        }
    }

    private fun prepareUploadAndExecuteDji(current: SurveyMission) {
        if (!snapshot().connected) return reject(activity.getString(R.string.dji_route_aircraft_disconnected))
        val generation = ++djiPreparationGeneration
        preparingDjiExecution = true
        operationMessage = activity.getString(R.string.preparing_dji_mission_package)
        renderStatus()
        applyMissionPhotoRatio(current) { ratioResult ->
            if (!isDjiPreparationCurrent(generation, current)) return@applyMissionPhotoRatio
            if (ratioResult.isFailure) {
                preparingDjiExecution = false
                reject(activity.getString(R.string.photo_ratio_set_failed, ratioResult.exceptionOrNull()?.message.orEmpty()))
                return@applyMissionPhotoRatio
            }
            prepareKmz(current) { file ->
            if (!isDjiPreparationCurrent(generation, current)) return@prepareKmz
            if (uploadedKmzSessionSignature == kmzSessionSignature(file) &&
                waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.READY &&
                WaylineMissionName.matches(waylineState.missionFileName, file.name)
            ) {
                waitForDjiWaylineReady(generation, current, file) {
                    preparingDjiExecution = false
                    executeWayline()
                }
                return@prepareKmz
            }
                operationMessage = activity.getString(R.string.uploading_then_auto_execute, file.name)
            renderStatus()
            waylinePort.upload(file.absolutePath) { result ->
                activity.runOnUiThread {
                    if (!isDjiPreparationCurrent(generation, current)) return@runOnUiThread
                    result.onSuccess {
                        uploadedKmzSessionSignature = kmzSessionSignature(file)
                        operationMessage = activity.getString(R.string.kmz_uploaded_waiting_parse)
                        renderStatus()
                        waitForDjiWaylineReady(generation, current, file) {
                            preparingDjiExecution = false
                            executeWayline()
                        }
                    }.onFailure {
                        uploadedKmzSessionSignature = null
                        preparingDjiExecution = false
                        reject(activity.getString(R.string.kmz_upload_failed, it.message.orEmpty()))
                    }
                }
            }
            }
        }
    }

    private fun waitForDjiWaylineReady(
        generation: Long,
        current: SurveyMission,
        file: File,
        attemptsRemaining: Int = DJI_WAYLINE_READY_MAX_ATTEMPTS,
        notBeforeElapsedMillis: Long = android.os.SystemClock.elapsedRealtime() + DJI_WAYLINE_SETTLE_MILLIS,
        onReady: () -> Unit,
    ) {
        if (!isDjiPreparationCurrent(generation, current)) return
        val waylineIds = waylinePort.availableWaylineIds(file.name)
        val settled = android.os.SystemClock.elapsedRealtime() >= notBeforeElapsedMillis
        if (settled && (waylineIds.isNotEmpty() || attemptsRemaining <= DJI_WAYLINE_EMPTY_ID_FALLBACK_ATTEMPT)) {
            recordSurveyEvent(
                "wayline_ready_after_upload",
                mapOf(
                    "mission_file_name" to file.name,
                    "wayline_ids" to waylineIds,
                    "used_empty_id_fallback" to waylineIds.isEmpty(),
                ),
            )
            onReady()
            return
        }
        if (attemptsRemaining <= 1) {
            onReady()
            return
        }
        persistenceHandler.postDelayed(
            {
                waitForDjiWaylineReady(
                    generation,
                    current,
                    file,
                    attemptsRemaining - 1,
                    notBeforeElapsedMillis,
                    onReady,
                )
            },
            DJI_WAYLINE_READY_POLL_MILLIS,
        )
    }

    private fun cameraGeometryMatches(
        current: SurveyMission,
        camera: DjiV5CameraDiscovery.Selection = cameraDiscovery.current(),
    ): Boolean {
        val capture = camera.captureProfile ?: return false
        if (!camera.cameraConnected || !camera.profileVerified) return false
        return if (current.activeMapping != null) {
            edu.playground.djivln.survey.SurveyCameraModePolicy.compatibleRecapture(current.cameraProfile, capture)
        } else {
            edu.playground.djivln.survey.SurveyCameraModePolicy.sameGeometry(current.cameraProfile, capture)
        }
    }

    private fun selectedPlanningCameraProfile(
        base: edu.playground.djivln.survey.CameraProfile = cameraDiscovery.current().cameraProfile,
    ): edu.playground.djivln.survey.CameraProfile {
        if (binding.photoRatio.selectedItemPosition != PHOTO_RATIO_16_BY_9) return base
        val height = (base.imageWidthPixels * 9.0 / 16.0).toInt().coerceAtLeast(1)
        val aspect = base.imageWidthPixels.toDouble() / height
        val verticalFov = Math.toDegrees(
            2.0 * kotlin.math.atan(
                kotlin.math.tan(Math.toRadians(base.horizontalFieldOfViewDegrees / 2.0)) / aspect,
            ),
        )
        return base.copy(
            imageHeightPixels = height,
            verticalFieldOfViewDegrees = verticalFov,
        )
    }

    private fun applyMissionPhotoRatio(
        current: SurveyMission,
        completion: (Result<Unit>) -> Unit,
    ) {
        val index = cameraDiscovery.current().index
        val ratio = if (current.cameraProfile.imageWidthPixels.toDouble() /
            current.cameraProfile.imageHeightPixels >= 1.5
        ) PhotoRatio.RATIO_16COLON9 else PhotoRatio.RATIO_4COLON3
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyPhotoRatio, index),
            ratio,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = activity.runOnUiThread { completion(Result.success(Unit)) }
                override fun onFailure(error: IDJIError) = activity.runOnUiThread {
                    completion(Result.failure(IllegalStateException(error.toString())))
                }
            },
        )
    }

    private fun isDjiPreparationCurrent(generation: Long, expectedMission: SurveyMission): Boolean =
        preparingDjiExecution && generation == djiPreparationGeneration && mission?.id == expectedMission.id

    private fun requiredGlobalRthHeight(current: SurveyMission): Double {
        val highestWaypoint = current.waypoints.maxOfOrNull { it.point.altitudeMeters }
            ?: current.constraints.safeTakeoffAltitudeMeters
        return maxOf(
            kotlin.math.ceil(highestWaypoint),
            snapshot().goHomeHeightMeters?.toDouble() ?: 0.0,
        )
    }

    private fun currentKmzFile(current: SurveyMission): File? {
        val cameraSelection = cameraDiscovery.current()
        val payloadIndex = DjiV5CameraDiscovery.wpmzPayloadPositionIndex(cameraSelection.index)
            ?: return null
        val payloadLens = DjiWpmzPayloadLens.fromStreamSourceName(cameraSelection.streamSource?.name)
            ?: return null
        val expectedRth = requiredGlobalRthHeight(current)
        return kmzFile?.takeIf {
            it.isFile && kmzMissionId == current.id && kmzPayloadPositionIndex == payloadIndex &&
                kmzPayloadLensName == payloadLens.name &&
                kotlin.math.abs((kmzExecutionSpeedMetersPerSecond ?: Double.NaN) - current.constraints.speedMetersPerSecond) < 0.01 &&
                kotlin.math.abs((kmzObliqueSpeedMetersPerSecond ?: Double.NaN) - current.constraints.obliqueSpeedMetersPerSecond) < 0.01 &&
                kotlin.math.abs((kmzTakeoffSpeedMetersPerSecond ?: Double.NaN) - current.constraints.takeoffSpeedMetersPerSecond) < 0.01 &&
                kotlin.math.abs((kmzRthHeightMeters ?: Double.NaN) - expectedRth) < 0.1
        }
    }

    private fun syncActiveRecaptureRouteSpeedFromField() {
        if (settingsRestoring || isMissionLockedForEditing()) return
        val current = mission?.takeIf { it.activeMapping != null } ?: return
        val speed = binding.flightSpeed.text.toString().toDoubleOrNull()?.takeIf { it in 0.5..10.0 } ?: return
        val obliqueSpeed = binding.obliqueSpeed.text.toString().toDoubleOrNull()
            ?.takeIf { it in 0.5..10.0 } ?: return
        val updated = SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(current, speed, obliqueSpeed)
        if (updated === current) return
        applyActiveRecaptureRouteSpeedUpdate(updated)
        operationMessage = activity.getString(R.string.recapture_speed_updated, speed, obliqueSpeed)
        renderStatus()
    }

    private fun applyActiveRecaptureRouteSpeed(current: SurveyMission): SurveyMission? {
        if (current.activeMapping == null) return current
        val updated = runCatching {
            SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(
                mission = current,
                speedMetersPerSecond = binding.flightSpeed.number(activity.getString(R.string.nadir_speed), 0.5..10.0),
                obliqueSpeedMetersPerSecond = binding.obliqueSpeed.number(activity.getString(R.string.oblique_speed), 0.5..10.0),
            )
        }.getOrElse {
            reject(it.message ?: activity.getString(R.string.speed_parameters_invalid))
            return null
        }
        if (updated === current) return current
        applyActiveRecaptureRouteSpeedUpdate(updated)
        operationMessage = activity.getString(R.string.recapture_speed_regenerating_kmz)
        renderStatus()
        return updated
    }

    private fun applyActiveRecaptureRouteSpeedUpdate(updated: SurveyMission) {
        activeRecaptureSourceMission = activeRecaptureSourceMission?.let { source ->
            SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(
                source,
                updated.constraints.speedMetersPerSecond,
                updated.constraints.obliqueSpeedMetersPerSecond,
            )
        }
        mission = updated
        kmzFile = null
        kmzMissionId = null
        kmzRthHeightMeters = null
        kmzPayloadPositionIndex = null
        kmzPayloadLensName = null
        kmzExecutionSpeedMetersPerSecond = null
        kmzObliqueSpeedMetersPerSecond = null
        kmzTakeoffSpeedMetersPerSecond = null
        uploadedKmzSessionSignature = null
        persistMission(resetCheckpoint = true)
        onMissionChanged(updated)
        recordSurveyEvent(
            "active_recapture_route_speed_overridden",
            mapOf(
                "speed_mps" to updated.constraints.speedMetersPerSecond,
                "oblique_speed_mps" to updated.constraints.obliqueSpeedMetersPerSecond,
            ),
        )
    }

    private fun kmzSessionSignature(file: File): String =
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    private fun cancelPreparingDjiExecution(reason: String, announce: Boolean) {
        val wasPreparing = preparingDjiExecution
        preparingDjiExecution = false
        djiPreparationGeneration += 1
        kmzPreparationGeneration += 1
        if (announce && wasPreparing) {
            operationMessage = activity.getString(R.string.pending_dji_mission_cancelled, reason)
            renderStatus()
        }
    }

    private fun pauseSelectedBackend() {
        if (selectedBackend() == SurveyExecutionBackend.DJI_KMZ) {
            if (!binding.pauseWayline.isEnabled) return reject(activity.getString(R.string.dji_mission_not_pausable))
            pauseDjiAndPersistRecovery(activity.getString(R.string.user_paused))
        } else {
            if (customState.status.state != edu.playground.djivln.survey.SurveyExecutionState.RUNNING) {
                return reject(activity.getString(R.string.custom_mission_not_pausable))
            }
            customExecution.pause()
        }
    }

    private fun pauseDjiAndPersistRecovery(reason: String, completion: () -> Unit = {}) {
        if (!snapshot().connected) {
            reject(activity.getString(R.string.dji_pause_aircraft_disconnected))
            completion()
            return
        }
        recordSurveyEvent("wayline_operation_requested", mapOf("operation" to "pause"))
        operationMessage = activity.getString(R.string.requesting_pause)
        renderStatus()
        waylinePort.pause { result ->
            activity.runOnUiThread {
                result.onFailure { error ->
                    operationMessage = activity.getString(R.string.pause_failed, error.message ?: error.javaClass.simpleName)
                    recordSurveyEvent(
                        "wayline_operation_result",
                        mapOf("operation" to "pause", "success" to false, "error" to error.message),
                    )
                    renderStatus()
                    completion()
                }.onSuccess {
                    recordSurveyEvent(
                        "wayline_operation_result",
                        mapOf("operation" to "pause", "success" to true),
                    )
                    operationMessage = activity.getString(R.string.pause_accepted_saving_resume)
                    renderStatus()
                    queryPausedBreakpointWithRetry(reason, attemptsRemaining = 4)
                    completion()
                }
            }
        }
    }

    private fun queryPausedBreakpointWithRetry(reason: String, attemptsRemaining: Int) {
        requestDjiRecoverySnapshot(reason) { result ->
            if (result.isSuccess) return@requestDjiRecoverySnapshot
            if (attemptsRemaining > 1 && snapshot().connected) {
                persistenceHandler.postDelayed({
                    queryPausedBreakpointWithRetry(reason, attemptsRemaining - 1)
                }, DJI_PAUSE_BREAKPOINT_RETRY_DELAY_MILLIS)
            } else {
                operationMessage = activity.getString(R.string.route_paused_resume_unconfirmed)
                renderStatus()
            }
        }
    }

    private fun resumeSelectedBackend() {
        mission?.let { if (!recaptureExecutionAllowed(it)) return }
        if (selectedBackend() == SurveyExecutionBackend.DJI_KMZ) {
            restoredDjiCheckpoint?.let {
                resumeImportedDjiCheckpoint(it)
                return
            }
            if (!binding.resumeWayline.isEnabled) return reject(activity.getString(R.string.dji_no_resumable_pause))
            val missionFileName = waylineState.missionFileName ?: kmzFile?.name
                ?: return reject(activity.getString(R.string.dji_mission_name_missing_for_resume))
            operationMessage = activity.getString(R.string.reading_dji_precise_pause)
            renderStatus()
            waylinePort.queryBreakpoint(missionFileName) { result ->
                activity.runOnUiThread {
                    mission?.let { if (!recaptureExecutionAllowed(it)) return@runOnUiThread }
                    result.onSuccess { queriedBreakpoint ->
                        val breakpoint = queriedBreakpoint ?: waylineState.breakpoint
                        if (breakpoint != null) {
                            operation("resume", activity.getString(R.string.action_resume)) { completion ->
                                waylinePort.resume(breakpoint, completion)
                            }
                        } else {
                            operation("resume", activity.getString(R.string.action_resume)) { completion ->
                                waylinePort.resume(completion)
                            }
                        }
                    }.onFailure { error ->
                        reject(activity.getString(R.string.read_dji_pause_failed, error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        } else {
            if (customState.status.state != edu.playground.djivln.survey.SurveyExecutionState.PAUSED) {
                return reject(activity.getString(R.string.custom_no_resumable_pause))
            }
            customExecution.resume()
        }
    }

    private fun stopSelectedBackend() {
        val djiStillActive = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        if (!djiStillActive && !preparingDjiExecution &&
            (pendingCheckpointRestore != null || restoredDjiCheckpoint != null)
        ) {
            pendingCheckpointRestore = null
            restoredDjiCheckpoint = null
            clearPersistedCheckpoint()
            operationMessage = activity.getString(R.string.pending_resume_point_cancelled)
            renderStatus()
            return
        }
        if (preparingDjiExecution) {
            cancelPreparingDjiExecution(activity.getString(R.string.user_aborted), announce = true)
            return
        }
        if (selectedBackend() == SurveyExecutionBackend.DJI_KMZ) {
            if (!binding.stopWayline.isEnabled) return reject(activity.getString(R.string.no_dji_mission_to_stop))
            stopWayline()
        } else {
            if (customState.status.state in setOf(
                    edu.playground.djivln.survey.SurveyExecutionState.IDLE,
                    edu.playground.djivln.survey.SurveyExecutionState.COMPLETED,
                    edu.playground.djivln.survey.SurveyExecutionState.ABORTED,
                )
        ) return reject(activity.getString(R.string.no_custom_mission_to_stop))
            customExecution.stop()
        }
    }

    private fun restoreCheckpoint(checkpoint: SurveyExecutionCheckpoint) {
        val current = mission ?: return reject(activity.getString(R.string.import_matching_mission_first))
        queueCheckpointRestore(current, checkpoint, activity.getString(R.string.action_import))
    }

    private fun queueCheckpointRestore(
        current: SurveyMission,
        checkpoint: SurveyExecutionCheckpoint,
        sourceLabel: String,
    ) {
        if (checkpoint.missionId != current.id) return reject(activity.getString(R.string.resume_point_mission_mismatch))
        if (checkpoint.backend == SurveyExecutionBackend.DJI_KMZ) {
            if (checkpoint.djiBreakpoint == null) {
            return reject(activity.getString(R.string.dji_resume_breakpoint_missing_unsafe))
            }
            backendSelectionChanging = true
            binding.executionBackend.setSelection(SurveyExecutionBackend.DJI_KMZ.ordinal)
            lastBackendPosition = SurveyExecutionBackend.DJI_KMZ.ordinal
            backendSelectionChanging = false
            pendingCheckpointRestore = PendingCheckpointRestore(
                current,
                checkpoint,
                SurveyExecutionBackend.DJI_KMZ,
                "",
                sourceLabel,
            )
            // The resume point is map data, not live-aircraft data. Show it immediately even when
            // the controller is disconnected; aircraft connectivity only gates the Resume action.
            activeDjiRecoveryDisplayCheckpoint = checkpoint
            restorePendingCheckpointWhenReady()
            if (pendingCheckpointRestore != null) {
                operationMessage = activity.getString(R.string.dji_resume_read_waiting_aircraft, sourceLabel)
                renderStatus()
            }
            return
        }
        // Schema v1-v3 did not record a backend. Preserve their historical
        // Custom Virtual Stick meaning instead of silently adopting the current spinner.
        val backend = checkpoint.backend ?: SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK
        val ueEndpoint = binding.ueEndpoint.text.toString().trim()
        if (backend == SurveyExecutionBackend.UE_HIL &&
            ueEndpoint.isBlank() && hilSession.status()?.peerHost.isNullOrBlank()
        ) {
                return reject(activity.getString(R.string.ue_hil_resume_peer_required))
        }
        backendSelectionChanging = true
        binding.executionBackend.setSelection(backend.ordinal)
        lastBackendPosition = backend.ordinal
        backendSelectionChanging = false
        val pending = PendingCheckpointRestore(current, checkpoint, backend, ueEndpoint, sourceLabel)
        pendingCheckpointRestore = pending
        if (!checkpointRestorePoseReady(pending)) {
            operationMessage = activity.getString(
                R.string.resume_read_waiting_position,
                sourceLabel,
                activity.getString(if (backend == SurveyExecutionBackend.UE_HIL) R.string.simulator_position else R.string.aircraft_position),
            )
            renderStatus()
            return
        }
        performCheckpointRestore(pending)
    }

    private fun checkpointRestorePoseReady(pending: PendingCheckpointRestore): Boolean {
        val aircraft = snapshot()
        if (pending.backend == SurveyExecutionBackend.DJI_KMZ) return aircraft.connected
        if (aircraft.aircraftLocation == null) return false
        return pending.backend == SurveyExecutionBackend.UE_HIL || aircraft.connected
    }

    private fun restorePendingCheckpointWhenReady() {
        val pending = pendingCheckpointRestore ?: return
        if (checkpointRestorePoseReady(pending)) performCheckpointRestore(pending)
    }

    private fun performCheckpointRestore(pending: PendingCheckpointRestore) {
        if (pendingCheckpointRestore !== pending) return
        pendingCheckpointRestore = null
        if (pending.backend == SurveyExecutionBackend.DJI_KMZ) {
            restoredDjiCheckpoint = pending
            activeDjiRecoveryDisplayCheckpoint = pending.checkpoint
            operationMessage = activity.getString(R.string.dji_resume_loaded, pending.sourceLabel, pending.checkpoint.waypointIndex + 1)
            renderStatus()
            return
        }
        customExecution.restore(
            pending.mission,
            pending.checkpoint,
            pending.backend,
            pending.ueEndpoint,
        )
            .onSuccess {
                customState = customExecution.currentSnapshot()
            operationMessage = activity.getString(
                R.string.resume_loaded_review_site,
                pending.sourceLabel,
                activity.getString(pending.backend.labelRes),
            )
                renderStatus()
            }
            .onFailure { error ->
            operationMessage = activity.getString(R.string.resume_point_unavailable_retained, error.message ?: error.javaClass.simpleName)
                renderStatus()
            }
    }

    private fun resumeImportedDjiCheckpoint(pending: PendingCheckpointRestore) {
        val breakpoint = pending.checkpoint.djiBreakpoint
            ?: return reject(activity.getString(R.string.dji_resume_breakpoint_missing))
        if (!preflight()) return
        if (preparingDjiExecution) return reject(activity.getString(R.string.dji_resume_preparing_no_repeat))
        djiPartitionContinuationGeneration += 1
        activeDjiRecoveryDisplayCheckpoint = pending.checkpoint
        continuingDjiWaylines = false
        pendingDjiContinuationWaylineId = null
        preparingDjiExecution = true
        val generation = ++djiPreparationGeneration
        operationMessage = activity.getString(R.string.preparing_dji_resume_package)
        renderStatus()
        prepareKmz(pending.mission) { file ->
            if (!isDjiPreparationCurrent(generation, pending.mission)) return@prepareKmz
            operationMessage = activity.getString(R.string.uploading_resume_from_waypoint, file.name, pending.checkpoint.waypointIndex + 1)
            renderStatus()
            waylinePort.upload(file.absolutePath) { uploadResult ->
                activity.runOnUiThread {
                    if (!isDjiPreparationCurrent(generation, pending.mission)) return@runOnUiThread
                    uploadResult.onFailure {
                        preparingDjiExecution = false
                        reject(activity.getString(R.string.dji_resume_upload_failed, it.message.orEmpty()))
                    }.onSuccess {
                        operationMessage = activity.getString(R.string.resume_route_uploaded_waiting_parse)
                        renderStatus()
                        waitForDjiWaylineReady(generation, pending.mission, file) {
                            armDjiAppCapture(pending.mission, breakpoint)
                            completedDjiMissionFileName = null
                            pendingDjiContinuationWaylineId = null
                            waylinePort.executeFromBreakpoint(file.name, breakpoint) { startResult ->
                                activity.runOnUiThread {
                                    if (!isDjiPreparationCurrent(generation, pending.mission)) return@runOnUiThread
                                    preparingDjiExecution = false
                                    startResult.onSuccess {
                                        restoredDjiCheckpoint = null
                                operationMessage = activity.getString(R.string.dji_resume_request_accepted)
                                        renderStatus()
                                    }.onFailure {
                                        stopDjiAppCapture()
                                reject(activity.getString(R.string.dji_resume_failed, it.message.orEmpty()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopWayline() {
        val missionFileName = waylineState.missionFileName ?: kmzFile?.name
            ?: return reject(activity.getString(R.string.no_route_to_stop))
        operation("stop", activity.getString(R.string.action_stop), after = ::stopDjiAppCapture) { completion ->
            waylinePort.stopMission(missionFileName, completion)
        }
    }

    private fun operation(
        eventOperation: String,
        label: String,
        after: () -> Unit = {},
        request: (edu.playground.djivln.domain.wayline.WaylineCompletion) -> Unit,
    ) {
        if (!snapshot().connected) return reject(activity.getString(R.string.dji_operation_aircraft_disconnected, label))
        recordSurveyEvent("wayline_operation_requested", mapOf("operation" to eventOperation))
        operationMessage = activity.getString(R.string.requesting_operation, label)
        renderStatus()
        request { result ->
            activity.runOnUiThread {
                operationMessage = result.fold(
                    { activity.getString(R.string.flight_action_accepted, label) },
                    { activity.getString(R.string.flight_action_failed, label, it.message.orEmpty()) },
                )
                recordSurveyEvent(
                    "wayline_operation_result",
                    mapOf(
                        "operation" to eventOperation,
                        "success" to result.isSuccess,
                        "error" to result.exceptionOrNull()?.message,
                    ),
                )
                renderStatus()
                after()
            }
        }
    }

    private fun reject(message: String) {
        operationMessage = activity.getString(R.string.safety_rejected, message)
        recordSurveyEvent("safety_rejected", mapOf("reason" to message))
        android.util.Log.w("OpenFlyV5Survey", "safety_rejected: $message")
        renderStatus()
    }

    private fun renderStatus() {
        renderLiveTelemetry()
        val camera = cameraDiscovery.current()
        val streamSource = camera.streamSource?.name?.replace("_CAMERA", "") ?: "SOURCE --"
        binding.areaCameraStatus.text = activity.getString(
            if (camera.profileVerified) R.string.current_camera_status else R.string.current_camera_status_uncalibrated,
            camera.cameraProfileLabel,
            streamSource,
        )
        binding.cameraProfileStatus.text = buildString {
            append(activity.getString(R.string.camera_profile_index, camera.cameraProfileLabel, camera.index.name))
            append(" · product=${camera.productType ?: "--"}")
            append(" · type=${camera.cameraType ?: "--"}")
            append(" · source=${camera.streamSource?.name ?: "--"}")
            if (camera.availableStreamSources.isNotEmpty()) {
                append(activity.getString(
                    R.string.available_camera_sources_suffix,
                    camera.availableStreamSources.joinToString { it.name.replace("_CAMERA", "") },
                ))
            }
            if (!camera.profileVerified) append(activity.getString(R.string.camera_uncalibrated_verify_suffix))
        }
        val editingLocked = isMissionLockedForEditing()
        val hasEditableArea = editedRoi.size >= 3
        binding.generateSurvey.isEnabled = hasEditableArea && !editingLocked
        binding.generateFiveDirection.isEnabled = hasEditableArea && selectedCaptureViews().isNotEmpty() && !editingLocked
        val current = mission ?: run {
            // Match V4: keep the three primary actions tappable so the user gets
            // an explicit safety explanation instead of a silent grey button.
            binding.previewSurvey.isEnabled = true
            binding.preflightSurvey.isEnabled = true
            binding.exportKmz.isEnabled = false
            binding.uploadWayline.isEnabled = false
            binding.executeWayline.isEnabled = true
            binding.executeWayline.setText(R.string.execution)
            binding.pauseWayline.isEnabled = false
            binding.resumeWayline.isEnabled = false
            binding.stopWayline.isEnabled = false
            binding.stopWayline.visibility = View.GONE
            binding.previewSurvey.visibility = View.VISIBLE
            binding.preflightSurvey.visibility = View.VISIBLE
            binding.surveyStatus.text = operationMessage ?: if (editedRoi.isEmpty()) {
                activity.getString(R.string.tap_map_add_roi_wgs84)
            } else activity.getString(R.string.current_roi_points_need_three, editedRoi.size)
            binding.surveyStatistics.setText(R.string.survey_statistics_empty)
            binding.surveyExecutionStatus.setText(R.string.survey_not_preflighted_no_takeoff)
            binding.surveyEtaOverlay.visibility = View.GONE
            onEtaChanged(null)
            mapEditor.renderExecutionOverlay(null)
            binding.surveyHeaderPause.visibility = View.GONE
            binding.surveyHeaderAbort.visibility = View.GONE
            renderPlanningLock(editingLocked)
            updateCaptureViewActions()
            refreshActionAlpha()
            return
        }
        binding.previewSurvey.isEnabled = true
        binding.preflightSurvey.isEnabled = true
        binding.exportKmz.isEnabled = true
        val actions = WaylineUiPolicy.actions(
            snapshot().connected,
            currentKmzFile(current) != null,
            waylineState,
        )
        val selected = selectedBackend()
        val customStatus = customState.status.state
        binding.ueEndpoint.isEnabled = selected == SurveyExecutionBackend.UE_HIL && !editingLocked
        binding.uploadWayline.isEnabled = selected == SurveyExecutionBackend.DJI_KMZ && actions.canUpload
        val canExecute = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            if (waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED) {
                actions.canResume
            } else {
                WaylineUiPolicy.unavailableReason(snapshot().connected, waylineState) == null &&
                    !editingLocked && !preparingDjiExecution && waylineState.phase !in setOf(
                    edu.playground.djivln.domain.wayline.WaylinePhase.UPLOADING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
                )
            }
        } else if (customStatus == edu.playground.djivln.survey.SurveyExecutionState.PAUSED) {
            !customState.controlAcquirePending
        } else {
            !editingLocked && customStatus !in setOf(
                edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
                edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            )
        }
        binding.pauseWayline.isEnabled = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            actions.canPause
        } else customStatus == edu.playground.djivln.survey.SurveyExecutionState.RUNNING
        binding.resumeWayline.isEnabled = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            val restoredResumeReady = restoredDjiCheckpoint != null && snapshot().connected &&
                !preparingDjiExecution && waylineState.phase !in setOf(
                    edu.playground.djivln.domain.wayline.WaylinePhase.UPLOADING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
                    edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
                ) && !isDjiReturnOrLandingActive()
            actions.canResume || restoredResumeReady
        } else customStatus == edu.playground.djivln.survey.SurveyExecutionState.PAUSED &&
            !customState.controlAcquirePending
        binding.stopWayline.isEnabled = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            actions.canStop || restoredDjiCheckpoint != null
        } else pendingCheckpointRestore != null || customStatus !in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.IDLE,
            edu.playground.djivln.survey.SurveyExecutionState.COMPLETED,
            edu.playground.djivln.survey.SurveyExecutionState.ABORTED,
        )
        val primaryAction = primaryExecutionAction()
        binding.executeWayline.isEnabled = when (primaryAction) {
            PrimaryExecutionAction.EXECUTE -> canExecute
            PrimaryExecutionAction.PAUSE -> binding.pauseWayline.isEnabled
            PrimaryExecutionAction.RESUME -> binding.resumeWayline.isEnabled
        }
        binding.executeWayline.setText(
            when (primaryAction) {
                PrimaryExecutionAction.EXECUTE -> R.string.execution
                PrimaryExecutionAction.PAUSE -> R.string.action_pause
                PrimaryExecutionAction.RESUME -> R.string.action_continue
            },
        )
        val executionControlsActive = primaryAction != PrimaryExecutionAction.EXECUTE ||
            binding.stopWayline.isEnabled || preparingDjiExecution
        binding.stopWayline.visibility = if (binding.stopWayline.isEnabled || preparingDjiExecution) {
            View.VISIBLE
        } else View.GONE
        binding.previewSurvey.visibility = if (executionControlsActive) View.GONE else View.VISIBLE
        binding.preflightSurvey.visibility = if (executionControlsActive) View.GONE else View.VISIBLE
        binding.surveyStatus.text = operationMessage
            ?: activity.getString(R.string.survey_generated_preflight_required)
        val launchPoint = (snapshot().homeLocation ?: snapshot().aircraftLocation)?.let {
            GeoPoint(it.latitude, it.longitude, 0.0)
        }
        val statistics = SurveyPlanner.statistics(current, launchPoint = launchPoint)
        val nadirPhotos = statistics.photoCountByView[SurveyCaptureView.NADIR] ?: 0
        val obliquePhotos = SurveyCaptureView.values()
            .filterNot { it == SurveyCaptureView.NADIR }
            .sumOf { statistics.photoCountByView[it] ?: 0 }
        val storageLabel = if (statistics.estimatedStorageMegabytes >= 1024.0) {
            String.format(Locale.US, "%.1f GB", statistics.estimatedStorageMegabytes / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", statistics.estimatedStorageMegabytes)
        }
        val sortieRanges = statistics.sorties.take(3).joinToString(" / ") {
            activity.getString(
                R.string.sortie_waypoint_range,
                it.sortieNumber,
                it.firstWaypointIndex + 1,
                it.lastWaypointIndex + 1,
            )
        } + if (statistics.sorties.size > 3) " / …" else ""
        val transitLabel = if (statistics.operationalReferenceAvailable) {
            activity.getString(R.string.approx_minutes, statistics.transitAndCompletionSeconds / 60.0)
        } else {
            activity.getString(R.string.waiting_home_gps_calculation)
        }
        binding.surveyStatistics.text = buildString {
            current.activeMapping?.let { active ->
                appendLine(activity.getString(R.string.active_recapture_counts, active.surveyCaptureCount, active.bridgeCaptureCount))
            }
            appendLine(activity.getString(
                R.string.survey_photo_storage_batteries,
                nadirPhotos + obliquePhotos,
                nadirPhotos,
                obliquePhotos,
                storageLabel,
                statistics.estimatedSorties,
            ))
            append(captureIntervalSummary(current))
            append('\n')
            append(activity.getString(R.string.transit_completion_time, transitLabel))
            if (sortieRanges.isNotEmpty()) {
                append(activity.getString(R.string.battery_swap_suggestion_suffix, sortieRanges))
            }
        }
        binding.surveyExecutionStatus.text = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            "${waylineState.phase} · ${activity.resolve(waylineState.message)}" +
                if (djiMissedCapturePasses.isEmpty()) "" else "\n" +
                    activity.getString(R.string.recapture_capture_missed, djiMissedCapturePasses.size)
        } else {
            "${customStatus} · ${customState.message}"
        }
        renderEta(current, selected)
        renderExecutionOverlay(current, selected)
        // The fixed action dock remains visible while the settings scroll; do not duplicate
        // pause/resume/stop in the title bar.
        binding.surveyHeaderPause.visibility = View.GONE
        binding.surveyHeaderAbort.visibility = View.GONE
        renderPlanningLock(editingLocked)
        updateCaptureViewActions()
        updateSpeedLimitHint()
        refreshActionAlpha()
    }

    private fun renderLiveTelemetry() {
        val aircraft = effectiveSnapshot()
        val velocity = aircraft.velocity.takeIf { aircraft.connected }
        val horizontalSpeed = velocity?.let { kotlin.math.hypot(it.north, it.east) }
            ?.takeIf { it.isFinite() }
        val altitude = aircraft.relativeAltitudeMeters
            ?.takeIf { aircraft.connected && it.isFinite() }
        val signal = aircraft.remoteControllerSignalPercent
            ?.takeIf { aircraft.connected && it in 0..100 }
        binding.surveyLiveTelemetry.text = activity.getString(
            R.string.survey_live_telemetry,
            horizontalSpeed?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
            altitude?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
            signal?.toString() ?: "--",
        )
    }

    private fun renderPlanningLock(locked: Boolean) = with(binding) {
        val activeRecaptureReadOnly = mission?.activeMapping != null
        areaInstruction.text = if (activeRecaptureReadOnly) {
            activity.getString(R.string.cloud_recapture_read_only_instruction)
        } else {
            activity.getString(R.string.roi_edit_instruction)
        }
        plannerTextInputs().forEach { (_, input) ->
            if (input !== ueEndpoint) input.isEnabled = !locked
        }
        listOf(
            altitudeMode, takeoffMode, startMode, completionAction, captureMode, photoRatio,
            obliqueHeadingMode, executionBackend, terrainKind,
        ).forEach { it.isEnabled = !locked }
        listOf(viewNadir, viewForward, viewBackward, viewLeft, viewRight, followTerrain)
            .forEach { it.isEnabled = !locked }
        dsmBuildingConfirm.isEnabled = !locked &&
            terrainKind.selectedItemPosition == TERRAIN_KIND_SURFACE_DSM && terrain != null
        undoSurveyVertex.isEnabled = !locked && !activeRecaptureReadOnly
        deleteSurveyVertex.isEnabled = !locked && !activeRecaptureReadOnly
        clearSurveyVertices.isEnabled = !locked
        generateSurvey.isEnabled = !locked && !activeRecaptureReadOnly && editedRoi.size >= 3
        generateFiveDirection.isEnabled = !locked && !activeRecaptureReadOnly &&
            editedRoi.size >= 3 && selectedCaptureViews().isNotEmpty()
        obliqueAngle.isEnabled = !locked
        val terrainBusy = dsmDownloadProgress.visibility == View.VISIBLE
        importDsm.isEnabled = !locked && !terrainBusy
        downloadTerrainDem.isEnabled = !locked && !terrainBusy
        downloadBuildingDsm.isEnabled = !locked && !terrainBusy
        importMission.isEnabled = !locked
        activeRecaptureGroups.visibility = if (mission?.activeMapping != null) View.VISIBLE else View.GONE
        activeRecaptureGroups.isEnabled = !locked
        continuousRecapture.visibility = if (mission?.activeMapping != null) View.VISIBLE else View.GONE
        continuousRecapture.isChecked = mission?.recaptureFlightMode == RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL
        continuousRecapture.isEnabled = !locked && selectedBackend() == SurveyExecutionBackend.DJI_KMZ
        updateActiveRecaptureGroupButton()
    }

    private fun recaptureExecutionAllowed(current: SurveyMission): Boolean {
        if (RecaptureFlightModePolicy.canExecute(
                current,
                selectedBackend() == SurveyExecutionBackend.DJI_KMZ,
            )
        ) return true
        reject(activity.getString(R.string.recapture_continuous_dji_only))
        return false
    }

    private fun confirmRecaptureFlightMode() {
        val current = mission?.takeIf { it.activeMapping != null } ?: return
        val enabled = current.recaptureFlightMode != RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL
        renderStatus()
        if (rejectEditingIfLocked() || selectedBackend() != SurveyExecutionBackend.DJI_KMZ) return
        val message = if (enabled) {
            val proposed = current.copy(recaptureFlightMode = RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL)
            activity.getString(R.string.recapture_continuous_confirm) + "\n\n" +
                activity.getString(R.string.recapture_continuous_eligible,
                    DjiWpmzRoutePolicy.continuousCaptureIndices(proposed).size,
                    current.waypoints.count { it.kind == edu.playground.djivln.survey.SurveyWaypointKind.CAPTURE_POINT })
        } else activity.getString(R.string.recapture_stop_confirm)
        AlertDialog.Builder(activity)
            .setTitle(R.string.recapture_continuous_switch)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                if (mission !== current || rejectEditingIfLocked() || selectedBackend() != SurveyExecutionBackend.DJI_KMZ) {
                    return@setPositiveButton
                }
                val mode = if (enabled) RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL else RecaptureFlightMode.STOP_AND_CAPTURE
                val changed = current.copy(id = java.util.UUID.randomUUID().toString(), recaptureFlightMode = mode)
                activeRecaptureSourceMission = (activeRecaptureSourceMission ?: current).copy(
                    id = java.util.UUID.randomUUID().toString(), recaptureFlightMode = mode)
                activateMission(changed, activity.getString(R.string.recapture_mode_changed),
                    preserveActiveRecaptureSource = true)
                recordSurveyEvent("recapture_flight_mode_changed", mapOf("mode" to mode.name))
            }.show()
    }

    private fun reportMissedRecapturePoints() {
        djiCaptureCoordinator.drainMissedPointPasses().forEach { passIndex ->
            djiMissedCapturePasses.add(passIndex)
            recordSurveyEvent("dji_recapture_missed", mapOf("pass_index" to passIndex,
                "reason" to "capture_not_confirmed_before_leaving_point"))
        }
    }

    private fun updateActiveRecaptureGroupButton() {
        val current = mission?.takeIf { it.activeMapping != null } ?: return
        val source = activeRecaptureSourceMission ?: current
        val groups = ActiveRecaptureMissionGroupCatalog.groups(source, activity)
        val selectedCount = ActiveRecaptureMissionGroupCatalog.selectedGroupIds(source, current).size
        val totalCount = groups.size
        binding.activeRecaptureGroups.text = if (selectedCount == totalCount) {
            activity.getString(R.string.recapture_groups_all, totalCount)
        } else {
            activity.getString(R.string.recapture_groups_selected, selectedCount, totalCount)
        }
    }

    private fun showActiveRecaptureGroupDialog() {
        if (isMissionLockedForEditing()) return reject(activity.getString(R.string.recapture_group_switch_locked))
        val current = mission?.takeIf { it.activeMapping != null }
            ?: return reject(activity.getString(R.string.not_active_recapture_mission))
        val source = activeRecaptureSourceMission ?: current
        val groups = ActiveRecaptureMissionGroupCatalog.groups(source, activity)
        val selectedIds = ActiveRecaptureMissionGroupCatalog.selectedGroupIds(source, current)
        val checked = BooleanArray(groups.size) { groups[it].groupId in selectedIds }
        val labels = groups.map { group ->
            activity.getString(R.string.recapture_group_row, group.order, group.label, group.suggestedSurveyPhotos)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.recapture_group_select_title)
            .setMultiChoiceItems(labels, checked) { _, which, enabled -> checked[which] = enabled }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.action_select_all, null)
            .setPositiveButton(R.string.action_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                checked.indices.forEach { index ->
                    checked[index] = true
                    dialog.listView.setItemChecked(index, true)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selection = groups.indices.filter { checked[it] }
                    .map { groups[it].groupId }
                    .toSet()
                if (selection.isEmpty()) {
            reject(activity.getString(R.string.select_one_recapture_group))
                    return@setOnClickListener
                }
                runCatching { ActiveRecaptureMissionRegionFilter.selectGroups(source, selection, activity) }
                    .onSuccess { filtered ->
                        settingsRestoring = true
                        applyMissionToFields(filtered)
                        settingsRestoring = false
                        activateMission(
                            filtered,
                            activity.getString(R.string.recapture_groups_applied_preflight, selection.size, groups.size),
                            preserveActiveRecaptureSource = true,
                        )
                        recordSurveyEvent(
                            "active_recapture_groups_selected",
                            mapOf("group_ids" to selection.sorted()),
                        )
                        dialog.dismiss()
                    }
            .onFailure { reject(activity.getString(R.string.recapture_group_apply_failed, it.message.orEmpty())) }
            }
        }
        dialog.show()
    }

    private fun renderEta(current: SurveyMission, selected: SurveyExecutionBackend) = with(binding) {
        val customActive = customState.status.state in setOf(
            edu.playground.djivln.survey.SurveyExecutionState.ARMING,
            edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
            edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
        )
        val djiActive = waylineState.phase in setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        val visible = if (selected == SurveyExecutionBackend.DJI_KMZ) djiActive else customActive
        surveyEtaOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            djiEtaSegmentFloor = 0
            val completed = etaExecutionMissionId == current.id && if (selected == SurveyExecutionBackend.DJI_KMZ) {
                waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED
            } else customState.status.state == edu.playground.djivln.survey.SurveyExecutionState.COMPLETED
            onEtaChanged(SurveyEtaSnapshot(current.id,
                if (completed) SurveyRemainingEstimate(0.0, 0.0) else SurveyDjiRemainingEstimator.planned(current),
                if (completed) SurveyEtaPhase.COMPLETED else SurveyEtaPhase.PLANNED))
            return@with
        }
        etaExecutionMissionId = current.id
        val sharedEstimate = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            val total = current.waypoints.size.coerceAtLeast(1)
            val index = (djiGlobalWaypointIndex(
                current,
                waylineState.waylineId ?: waylineState.breakpoint?.waylineId,
                waylineState.waypointIndex ?: waylineState.breakpoint?.waypointId,
            ) ?: 0).coerceIn(0, total - 1)
            val aircraft = effectiveSnapshot()
            val location = aircraft.aircraftLocation
            val position = location?.let { GeoPoint(it.latitude, it.longitude,
                aircraft.relativeAltitudeMeters ?: it.altitudeMeters ?: 0.0) }
            val approaching = waylineState.phase in setOf(
                edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
                edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING)
            if (djiEtaMissionId != current.id || approaching) {
                djiEtaMissionId = current.id
                djiEtaSegmentFloor = 0
            }
            val paused = waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED
            if (!approaching) djiEtaSegmentFloor = SurveyDjiRemainingEstimator.segmentIndex(
                current, index, position, djiEtaSegmentFloor)
            val breakpoint = waylineState.breakpoint
            val recoveryPoint = if (waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING && breakpoint != null) {
                val start = current.waypoints[index].point
                val end = current.waypoints.getOrNull(index + 1)?.point ?: start
                val fraction = breakpoint.segmentProgress.coerceIn(0.0, 1.0)
                GeoPoint(breakpoint.latitude ?: (start.latitude + (end.latitude - start.latitude) * fraction),
                    breakpoint.longitude ?: (start.longitude + (end.longitude - start.longitude) * fraction),
                    breakpoint.altitudeMeters ?: (start.altitudeMeters + (end.altitudeMeters - start.altitudeMeters) * fraction))
            } else null
            val estimate = SurveyDjiRemainingEstimator.estimate(
                mission = current,
                waypointIndex = index,
                currentPosition = position,
                currentHeadingDegrees = aircraft.headingDegrees ?: aircraft.attitude?.yaw ?: Double.NaN,
                currentHorizontalSpeedMetersPerSecond = aircraft.velocity?.takeUnless { paused }?.let {
                    kotlin.math.hypot(it.north, it.east)
                } ?: Double.NaN,
                currentVerticalSpeedMetersPerSecond = aircraft.velocity?.takeUnless { paused }?.up ?: Double.NaN,
                minimumSegmentIndex = djiEtaSegmentFloor,
                isApproaching = approaching,
                approachPoint = recoveryPoint,
            )
            surveyEtaTitle.text = activity.getString(R.string.eta_dji_title, waylineState.phase)
            surveyEtaTime.text = activity.getString(
                R.string.eta_remaining,
                formatDuration(estimate.currentSectionSeconds),
                formatDuration(estimate.totalSeconds),
            )
            val estimatedLeg = (if (approaching) index else djiEtaSegmentFloor).coerceAtMost((total - 2).coerceAtLeast(0))
            val routeSpeed = current.constraints.speedForCaptureView(current.waypoints[estimatedLeg].captureView)
            surveyEtaDetail.text = activity.getString(R.string.eta_leg_detail, routeSpeed, estimatedLeg + 1, (total - 1).coerceAtLeast(1))
            estimate
        } else {
            surveyEtaTitle.text = "${activity.getString(selected.labelRes)} · ${customState.phase ?: customState.status.state}"
            surveyEtaTime.text = activity.getString(
                R.string.eta_remaining,
                formatDuration(customState.currentSectionSeconds),
                formatDuration(customState.totalRemainingSeconds),
            )
            val routeSpeed = current.waypoints.getOrNull(customState.status.waypointIndex)
                ?.let { current.constraints.speedForCaptureView(it.captureView) }
                ?: current.constraints.speedMetersPerSecond
            surveyEtaDetail.text = activity.getString(
                R.string.eta_leg_detail,
                routeSpeed,
                customState.executionLegIndex + 1,
                customState.executionLegCount,
            )
            SurveyRemainingEstimate(customState.currentSectionSeconds, customState.totalRemainingSeconds)
        }
        val paused = if (selected == SurveyExecutionBackend.DJI_KMZ) {
            waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED
        } else customState.status.state == edu.playground.djivln.survey.SurveyExecutionState.PAUSED
        onEtaChanged(SurveyEtaSnapshot(current.id, sharedEstimate,
            if (paused) SurveyEtaPhase.PAUSED else SurveyEtaPhase.REMAINING))
    }

    private fun renderExecutionOverlay(current: SurveyMission, selected: SurveyExecutionBackend) {
        val aircraft = effectiveSnapshot().aircraftLocation?.let {
            GeoPoint(it.latitude, it.longitude, it.altitudeMeters ?: 0.0)
        }
        val displayCheckpoint = activeDjiRecoveryDisplayCheckpoint
            ?: restoredDjiCheckpoint?.checkpoint
        val djiOverlayVisible = waylineState.phase in setOf(
                edu.playground.djivln.domain.wayline.WaylinePhase.PREPARING,
                edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
                edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
                edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
            ) || displayCheckpoint != null
        val overlay = if (selected == SurveyExecutionBackend.DJI_KMZ && djiOverlayVisible) {
            val liveIndex = djiGlobalWaypointIndex(
                current,
                waylineState.waylineId ?: waylineState.breakpoint?.waylineId,
                waylineState.waypointIndex ?: waylineState.breakpoint?.waypointId,
            )
            val captureProgress = if (displayCheckpoint == null) {
                djiCaptureCoordinator.progress(liveIndex)
            } else null
            // DJI briefly reports local waypoint 0 after executeFromBreakpoint. Keep the durable
            // checkpoint authoritative until the aircraft actually reaches it.
            val progressIndex = captureProgress?.let { progress ->
                liveIndex?.takeIf { it in progress.firstWaypointIndex..progress.lastWaypointIndex }
                    ?: progress.firstWaypointIndex
            }
            val index = (displayCheckpoint?.waypointIndex ?: progressIndex ?: liveIndex ?: 0)
                .coerceIn(0, current.waypoints.lastIndex)
            val recoveryPoint = displayCheckpoint?.let { djiCheckpointPoint(current, it) }
            val paused = displayCheckpoint != null ||
                waylineState.phase == edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED
            val transit = recoveryPoint != null || captureProgress?.let {
                it.recovering || it.transitOnly || !it.captureActive
            } == true
            val passNumber = current.waypoints[index].passIndex + 1
            SurveyExecutionOverlay(
                aircraftPoint = aircraft,
                targetPoint = recoveryPoint ?: current.waypoints[index].point,
                activeRoutePoints = activeSurveyPassPoints(current, index),
                recoveryPoint = recoveryPoint,
                recoveryTitle = recoveryPoint?.let {
                    activity.getString(R.string.dji_resume_breakpoint_marker, index + 1, passNumber)
                },
                title = if (recoveryPoint != null) {
                    activity.getString(R.string.dji_resume_breakpoint_marker, index + 1, passNumber)
                } else if (transit) {
                    activity.getString(R.string.survey_transit_to_pass, passNumber)
                } else {
                    activity.getString(R.string.dji_current_waypoint, index + 1, current.waypoints.size)
                },
                paused = paused,
                transit = transit,
                activeRegionId = SurveyRouteStyle.regionId(current, index),
            )
        } else if (selected != SurveyExecutionBackend.DJI_KMZ && customState.currentTarget != null &&
            customState.status.state in setOf(
                edu.playground.djivln.survey.SurveyExecutionState.ARMING,
                edu.playground.djivln.survey.SurveyExecutionState.RUNNING,
                edu.playground.djivln.survey.SurveyExecutionState.PAUSED,
            )
        ) {
            val paused = customState.status.state == edu.playground.djivln.survey.SurveyExecutionState.PAUSED
            SurveyExecutionOverlay(
                aircraftPoint = aircraft,
                targetPoint = checkNotNull(customState.currentTarget),
                activeRoutePoints = activeSurveyPassPoints(
                    current,
                    customState.status.waypointIndex.coerceIn(current.waypoints.indices),
                ),
                recoveryPoint = customState.recoveryPoint,
            title = activity.getString(R.string.current_execution_leg, customState.executionLegIndex + 1, customState.executionLegCount),
                paused = paused,
                activeRegionId = SurveyRouteStyle.regionId(
                    current,
                    customState.status.waypointIndex.coerceIn(current.waypoints.indices),
                ),
            )
        } else {
            null
        }
        mapEditor.renderExecutionOverlay(overlay)
    }

    private fun activeSurveyPassPoints(current: SurveyMission, waypointIndex: Int): List<GeoPoint> {
        return SurveyRouteStyle.activeRoutePoints(current, waypointIndex)
    }

    private fun refreshActionAlpha() = with(binding) {
        listOf(
            previewSurvey, preflightSurvey, exportKmz, uploadWayline,
            pauseWayline, resumeWayline, stopWayline, activeRecaptureGroups,
        ).forEach { it.alpha = if (it.isEnabled) 1f else 0.38f }
        // V4 keeps the two generation actions and the primary execute action visually
        // prominent even before their safety prerequisites are satisfied.
        generateSurvey.alpha = 1f
        generateFiveDirection.alpha = 1f
        executeWayline.alpha = 1f
    }

    private fun effectiveSnapshot(): AircraftSnapshot =
        DjiV5SimulatorAuthority.apply(snapshot(), simulatorManager.isSimulatorEnabled())

    private fun selectedBackend(): SurveyExecutionBackend =
        SurveyExecutionBackend.values().getOrElse(binding.executionBackend.selectedItemPosition) {
            SurveyExecutionBackend.DJI_KMZ
        }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun cachedV86TakeoffAltitude(reference: edu.playground.djivln.domain.telemetry.GeoPoint?): Double? {
        reference ?: return null
        val altitude = preferences.getString(KEY_V86_TAKEOFF_ASL, null)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: return null
        val latitude = preferences.getString(KEY_V86_TAKEOFF_LATITUDE, null)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: return null
        val longitude = preferences.getString(KEY_V86_TAKEOFF_LONGITUDE, null)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: return null
        return altitude.takeIf {
            distanceMeters(reference.latitude, reference.longitude, latitude, longitude) <=
                V86_TAKEOFF_CACHE_MAX_DISTANCE_METERS
        }
    }

    private fun rememberV86TakeoffAltitude(
        altitudeMeters: Double,
        reference: edu.playground.djivln.domain.telemetry.GeoPoint?,
    ) {
        if (!altitudeMeters.isFinite() || reference == null) return
        preferences.edit()
            .putString(KEY_V86_TAKEOFF_ASL, altitudeMeters.toString())
            .putString(KEY_V86_TAKEOFF_LATITUDE, reference.latitude.toString())
            .putString(KEY_V86_TAKEOFF_LONGITUDE, reference.longitude.toString())
            .apply()
    }

    private fun distanceMeters(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(latA, lonA, latB, lonB, result)
        return result[0].toDouble()
    }

    private fun summary(value: SurveyMission): String = buildString {
        appendLine(value.name)
        appendLine(activity.getString(
            R.string.mission_summary_counts,
            value.waypoints.size,
            value.surveyPasses().size,
            value.estimatedPhotoCount,
        ))
        appendLine(activity.getString(
            R.string.mission_summary_distance_time,
            value.estimatedPathMeters,
            formatDuration(value.estimatedFlightSeconds),
        ))
        appendLine(activity.getString(
            R.string.mission_summary_speeds_altitude,
            value.constraints.speedMetersPerSecond,
            value.constraints.obliqueSpeedMetersPerSecond,
            value.constraints.altitudeMetersAgl,
        ))
        append(captureIntervalSummary(value))
    }

    private fun captureIntervalSummary(value: SurveyMission): String {
        if (value.constraints.captureTriggerMode == SurveyCaptureTriggerMode.TIME) {
            return activity.getString(R.string.capture_period_seconds, value.constraints.timedCaptureIntervalSeconds)
        }
        val passes = value.surveyPasses().filterNot { it.isPointCapture }
        val nadirIntervals = passes
            .filter { it.start.captureView == SurveyCaptureView.NADIR }
            .mapNotNull { it.start.captureIntervalMeters }
        val obliqueIntervals = passes
            .filter { it.start.captureView != SurveyCaptureView.NADIR }
            .mapNotNull { it.start.captureIntervalMeters }
        fun format(label: String, values: List<Double>): String? {
            if (values.isEmpty()) return null
            val minimum = values.minOrNull() ?: return null
            val maximum = values.maxOrNull() ?: return null
            return if (kotlin.math.abs(maximum - minimum) < 0.05) {
                String.format(Locale.US, "%s %.2f m", label, minimum)
            } else {
                String.format(Locale.US, "%s %.2f–%.2f m", label, minimum, maximum)
            }
        }
        return listOfNotNull(
            format(activity.getString(R.string.nadir_spacing), nadirIntervals),
            format(activity.getString(R.string.oblique_spacing), obliqueIntervals),
        ).joinToString(" · ").ifEmpty { activity.getString(R.string.capture_spacing_unavailable) }
    }

    private fun openDocument(type: String) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        this.type = type
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    private fun openJsonDocument() = openDocument("application/json").apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, lastJsonDocumentUri())
        }
    }

    private fun openV86SfmFolder() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                preferences.getString(KEY_LAST_V86_SFM_TREE_URI, null)?.let(Uri::parse)
                    ?: Uri.parse(
                        "content://com.android.externalstorage.documents/document/" +
                            "primary%3ADownload%2FDJI-VLN%2Fsfm_test",
                    ),
            )
        }
    }

    private fun lastJsonDocumentUri(): Uri = preferences.getString(KEY_LAST_JSON_DOCUMENT_URI, null)
        ?.let(Uri::parse)
        ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")

    private fun rememberJsonDocumentLocation(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.edit().putString(KEY_LAST_JSON_DOCUMENT_URI, uri.toString()).apply()
    }

    private fun openTerrainDocument() = openDocument("image/tiff").apply {
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("image/tiff", "image/geotiff", "application/geotiff", "application/octet-stream"),
        )
    }

    private fun android.widget.EditText.number(label: String, range: ClosedFloatingPointRange<Double>): Double {
        val value = text.toString().toDoubleOrNull()
            ?: error(activity.getString(R.string.field_not_valid_number, label))
        require(value in range) {
            activity.getString(R.string.field_must_be_in_range, label, range.start, range.endInclusive)
        }
        return value
    }

    private fun djiGlobalWaypointIndex(
        current: SurveyMission,
        waylineId: Int?,
        localWaypointIndex: Int?,
    ): Int? = DjiWaylinePartition.globalWaypointIndex(current, waylineId, localWaypointIndex, activity)

    companion object {
        const val REPLAY_TICK_MILLIS = 120L
        const val SETTINGS_SAVE_DELAY_MILLIS = 300L
        const val LIVE_REFRESH_INTERVAL_NANOS = 250_000_000L
        const val COMMAND_LOG_INTERVAL_NANOS = 200_000_000L
        const val SETTINGS_SCHEMA_VERSION = 3
        const val V86_REFRESH_INTERVAL_MILLIS = 2_000L
        const val V86_BACKGROUND_PROCESSING_REFRESH_MILLIS = 5_000L
        const val V86_BACKGROUND_STREAMING_REFRESH_MILLIS = 15_000L
        const val V86_BACKGROUND_COMPLETE_REFRESH_MILLIS = 60_000L
        const val GLOBAL_TERRAIN_ZOOM = 14
        const val TERRAIN_KIND_SURFACE_DSM = 0
        const val TERRAIN_KIND_BARE_EARTH = 1
        const val PHOTO_RATIO_16_BY_9 = 0
        const val PHOTO_RATIO_4_BY_3 = 1
        const val REQUEST_IMPORT_DSM = 7_401
        const val REQUEST_IMPORT_MISSION = 7_402
        const val REQUEST_IMPORT_CHECKPOINT = 7_403
        const val REQUEST_IMPORT_BUILDING_HEIGHT = 7_404
        const val REQUEST_IMPORT_V86_SFM_TEST = 7_405
        const val REQUEST_V86_MEDIA_LOCATION = 7_406
        const val PREFERENCES = "survey_feature"
        const val CHECKPOINT_PREFERENCES = "survey_checkpoint"
        const val LIBRARY_PREFERENCES = "survey_mission_library"
        const val KEY_BUILDING_COG_TEMPLATE = "building_cog_template"
        const val KEY_SETTINGS = "planner_settings_v1"
        const val KEY_MISSION = "active_mission"
        const val KEY_ACTIVE_RECAPTURE_SOURCE_MISSION = "active_recapture_source_mission"
        const val KEY_CHECKPOINT = "active_checkpoint"
        const val KEY_LIBRARY = "mission_library"
        const val KEY_LAST_JSON_DOCUMENT_URI = "last_json_document_uri"
        const val KEY_LAST_V86_SFM_TREE_URI = "last_v86_sfm_tree_uri"
        // v2 intentionally resets the old opt-in default: live acquisition now
        // enables rolling SfM/Scal3R unless the operator explicitly disables it.
        const val KEY_V86_AUTO_PREVIEW = "v86_auto_preview_v2"
        const val KEY_V86_TAKEOFF_ASL = "v86_takeoff_asl_m"
        const val KEY_V86_TAKEOFF_LATITUDE = "v86_takeoff_latitude"
        const val KEY_V86_TAKEOFF_LONGITUDE = "v86_takeoff_longitude"
        const val V86_TAKEOFF_CACHE_MAX_DISTANCE_METERS = 30.0
        const val V86_OFFLINE_MAX_PENDING = 12
        const val V86_OFFLINE_MAX_REPORTED_ISSUES = 12
        const val V86_OFFLINE_STREAM_INTERVAL_MILLIS = 200L
        const val V86_OFFLINE_QUEUE_POLL_MILLIS = 250L
        const val V86_OFFLINE_QUEUE_WAIT_TIMEOUT_MILLIS = 120_000L
        const val DJI_PAUSE_BREAKPOINT_RETRY_DELAY_MILLIS = 600L
        const val DJI_BREAKPOINT_QUERY_TIMEOUT_MILLIS = 3_000L
        const val DJI_RECOVERY_DISPLAY_REACHED_METERS = 8.0
        const val DJI_WAYLINE_SETTLE_MILLIS = 2_000L
        const val DJI_WAYLINE_READY_POLL_MILLIS = 400L
        const val DJI_WAYLINE_READY_MAX_ATTEMPTS = 25
        const val DJI_WAYLINE_EMPTY_ID_FALLBACK_ATTEMPT = 20
        const val DJI_PARTITION_CONTINUATION_SETTLE_MILLIS = 800L
        const val TRIGGER_FRAME_REQUEST_TIMEOUT_MILLIS = 1_500L
        private val DJI_RECOVERABLE_ACTIVE_PHASES = setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.EXECUTING,
            edu.playground.djivln.domain.wayline.WaylinePhase.PAUSED,
            edu.playground.djivln.domain.wayline.WaylinePhase.RECOVERING,
        )
        private val DJI_UNEXPECTED_STOP_PHASES = setOf(
            edu.playground.djivln.domain.wayline.WaylinePhase.IDLE,
            edu.playground.djivln.domain.wayline.WaylinePhase.READY,
            edu.playground.djivln.domain.wayline.WaylinePhase.FINISHED,
            edu.playground.djivln.domain.wayline.WaylinePhase.ERROR,
            edu.playground.djivln.domain.wayline.WaylinePhase.DISCONNECTED,
        )
    }

    private enum class PrimaryExecutionAction {
        EXECUTE,
        PAUSE,
        RESUME,
    }

    private enum class SurveyTab {
        AREA,
        FLIGHT,
        CAPTURE,
        TERRAIN,
    }
}
