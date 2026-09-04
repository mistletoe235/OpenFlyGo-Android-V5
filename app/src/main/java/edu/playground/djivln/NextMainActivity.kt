package edu.playground.djivln

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dji.v5.manager.aircraft.simulator.SimulatorManager
import edu.playground.djivln.adapter.dji.DjiV5TelemetrySource
import edu.playground.djivln.adapter.dji.DjiV5CameraDiscovery
import edu.playground.djivln.databinding.ActivityNextMainBinding
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.ui.AppUiState
import edu.playground.djivln.ui.AppUiStateReducer
import edu.playground.djivln.ui.AppChromePolicy
import edu.playground.djivln.ui.AircraftStatusController
import edu.playground.djivln.ui.DjiFlightCheckController
import edu.playground.djivln.ui.FlightFeatureController
import edu.playground.djivln.ui.HilFeatureController
import edu.playground.djivln.ui.ModelMonitorController
import edu.playground.djivln.ui.MoreControlController
import edu.playground.djivln.ui.SurveyFeatureController
import edu.playground.djivln.ui.DeferredFeatureController
import edu.playground.djivln.hil.HilRuntimeSession
import edu.playground.djivln.hil.DjiV5SimulatorPoseSource
import edu.playground.djivln.hil.DjiV5HilSimulatorBackend
import edu.playground.djivln.hil.HilSimulatorLifecycleCoordinator
import edu.playground.djivln.hil.HilOfflineRegressionRunner
import edu.playground.djivln.hil.AndroidHilController
import edu.playground.djivln.hil.HilProtocol
import edu.playground.djivln.hil.DjiV5SimulatorAuthority
import edu.playground.djivln.hil.SimulatorOriginStore
import edu.playground.djivln.logging.FlightTelemetryLogPayload
import edu.playground.djivln.logging.AppDiagnosticLogger
import edu.playground.djivln.localization.AppLanguageController
import edu.playground.djivln.localization.resolve
import edu.playground.djivln.control.SimulatorFlightTestController
import edu.playground.djivln.account.DjiAccountFlightPolicy
import edu.playground.djivln.account.DjiAccountSnapshot
import edu.playground.djivln.account.DjiUserAccountController

class NextMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNextMainBinding
    private val cameraDiscovery by lazy { DjiV5CameraDiscovery(context = this) }
    private val telemetrySource = DjiV5TelemetrySource(cameraDiscovery = cameraDiscovery)
    private var sdkState = DjiSdkBootstrap.snapshot()
    @Volatile private var aircraftSnapshot = AircraftSnapshot()
    private var flightController: FlightFeatureController? = null
    private var surveyController: SurveyFeatureController? = null
    private var vlnController: DeferredFeatureController? = null
    private var hilController: HilFeatureController? = null
    private var aircraftStatusController: AircraftStatusController? = null
    private lateinit var flightCheckController: DjiFlightCheckController
    private var moreControlController: MoreControlController? = null
    private var modelMonitorController: ModelMonitorController? = null
    private lateinit var accountController: DjiUserAccountController
    private var accountSnapshot = DjiAccountSnapshot()
    private var accountStartupPromptHandled = false
    private var accountStartupPromptShowing = false
    private var hilOfflineRegression: HilOfflineRegressionRunner? = null
    private var simulatorMotionRegression: SimulatorFlightTestController? = null
    private var simulatorMotionRegressionGeneration = 0L
    private var hilRawRegressionGeneration = 0L
    private val modelLogLines = ArrayDeque<String>()
    private var vlnVisible = false
    private var mapFullscreen = false
    private var activeOverlay: Feature? = null
    private val hideSurveyPhotoFeedback = Runnable {
        if (!::binding.isInitialized) return@Runnable
        binding.surveyPhotoCaptureFeedback.animate().cancel()
        binding.surveyPhotoCaptureFeedback.visibility = View.GONE
        binding.surveyPhotoCaptureFeedback.alpha = 0f
    }
    private val hilSession by lazy(LazyThreadSafetyMode.NONE) {
        HilRuntimeSession(context = applicationContext)
    }
    private val simulatorManager by lazy { SimulatorManager.getInstance() }
    private val simulatorPoseSource by lazy { DjiV5SimulatorPoseSource() }
    private val simulatorOriginStore by lazy { SimulatorOriginStore(applicationContext) }
    private val hilLifecycleHandler = Handler(Looper.getMainLooper())
    private val hilSimulatorLifecycle by lazy {
        HilSimulatorLifecycleCoordinator(
            backend = DjiV5HilSimulatorBackend(context = this),
            scheduler = HilSimulatorLifecycleCoordinator.Scheduler { delayMillis, action ->
                hilLifecycleHandler.postDelayed(action, delayMillis)
            },
            rawFresh = hilSession::simulatorSourceFresh,
            refreshRawListener = simulatorPoseSource::refreshListener,
        )
    }
    private var hilRuntimeEnabled = true
    private var hilRuntimeListenersAttached = false
    private val hilSurveySafetyListener = object : HilRuntimeSession.Listener {
        override fun onHilEvent(event: HilProtocol.Event) {
            eventRecorder.record("hil_event", mapOf(
                "kind" to event.kind,
                "stop_score" to event.stopScore,
                "reason" to event.reason,
            ))
            val safetyEvent = event.kind == HilProtocol.EVENT_EMERGENCY ||
                event.kind == HilProtocol.EVENT_COLLISION ||
                (event.kind == HilProtocol.EVENT_STOP && event.stopScore.isFinite() && event.stopScore >= 0.7)
            if (safetyEvent && surveyController?.isHilExecutionActive() == true) {
                surveyController?.pauseForExternalIntervention(
                    getString(
                        R.string.ue_hil_safety_event,
                        event.kind,
                        event.reason.ifBlank { getString(R.string.reason_unavailable) },
                    ),
                )
            }
        }

        override fun onHilLinkStale() {
            eventRecorder.record("hil_link_stale")
            if (surveyController?.isHilExecutionActive() == true) {
                surveyController?.pauseForExternalIntervention(getString(R.string.ue_hil_heartbeat_timeout))
            }
        }

        override fun onHilError(message: String) {
            eventRecorder.record("hil_error", mapOf("message" to message))
        }

        override fun onHilStatus(status: AndroidHilController.Status) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastHilStatusLogNanos < 1_000_000_000L) return
            lastHilStatusLogNanos = now
            val source = hilSession.simulatorSourceStatus()
            eventRecorder.record("hil_status", mapOf(
                "running" to status.running,
                "mode" to status.mode.name,
                "peer_host" to status.peerHost,
                "peer_fresh" to status.peerFresh,
                "pose_send_hz" to status.measuredPoseSendHz,
                "rtt_ms" to status.roundTripMillis,
                "received_packets" to status.receivedPacketCount,
                "frame_connected" to status.frameConnected,
                "frame_id" to status.frame?.frameId,
                "simulator_source_hz" to source.measuredRateHz,
                "simulator_source_age_ms" to source.ageMillis,
            ))
        }
    }
    private val eventRecorder by lazy { AppDiagnosticLogger.recorder(applicationContext) }
    private var lastTelemetryLogNanos = 0L
    private var lastMemoryLogElapsedMillis = 0L
    private var lastFlightStateLogSignature: String? = null
    private var lastHilStatusLogNanos = 0L
    private val simulatorLifecycleLogListener = HilSimulatorLifecycleCoordinator.Listener { message ->
        val diagnostics = simulatorPoseSource.diagnostics()
        eventRecorder.record("simulator_lifecycle", mapOf(
            "message" to resolve(message),
            "enabled" to simulatorManager.isSimulatorEnabled(),
            "raw_callbacks" to diagnostics.rawCallbackCount,
            "raw_value_changes" to diagnostics.rawValueChangeCount,
            "telemetry_changes" to diagnostics.telemetryChangeCount,
            "location_callbacks" to diagnostics.locationCallbackCount,
            "attitude_callbacks" to diagnostics.attitudeCallbackCount,
            "velocity_callbacks" to diagnostics.velocityCallbackCount,
            "heading_callbacks" to diagnostics.headingCallbackCount,
            "poll_samples" to diagnostics.pollSampleCount,
            "output_hz" to diagnostics.outputRateHz,
            "last_source" to diagnostics.lastSource,
        ))
    }
    private var surveyStickTakeoverLatched = false
    private var surveyFailsafeModeLatched = false
    private var hilSimulatorObservedActive = false
    private var hilSimulatorSourceObserved = false
    private val hilSafetyHandler = Handler(Looper.getMainLooper())
    private var activityStarted = false
    private val hilSafetyTick = object : Runnable {
        override fun run() {
            if (!activityStarted) return
            monitorHilAuthoritySource()
            hilSafetyHandler.postDelayed(this, 250L)
        }
    }
    private val sdkListener: (DjiSdkBootstrap.State) -> Unit = { state ->
        sdkState = state
        if (state.registered && ::accountController.isInitialized) accountController.refresh()
        renderCurrentState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountStartupPromptHandled = savedInstanceState?.getBoolean(ACCOUNT_PROMPT_HANDLED_KEY, false) == true
        binding = ActivityNextMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        accountController = DjiUserAccountController(
            activity = this,
            recordEvent = eventRecorder::record,
            onChanged = { account ->
                accountSnapshot = account
                renderCurrentState()
                moreControlController?.renderStatus()
                scheduleAccountStartupPrompt()
            },
        )
        flightCheckController = DjiFlightCheckController(
            activity = this,
            summaryView = binding.flightSafetyTop,
            aircraftConnected = { aircraftSnapshot.connected },
            recordEvent = eventRecorder::record,
        )
        enterImmersiveMode()
        binding.sdkStatus.setOnClickListener { showOverlay(Feature.STATUS) }
        binding.sdkStatus.setOnLongClickListener {
            binding.aircraftStatusDetail.visibility = if (binding.aircraftStatusDetail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        binding.overflowMenu.setOnClickListener { showOverlay(Feature.MORE) }
        binding.navFlight.setOnClickListener { closeOverlay() }
        binding.navSurvey.setOnClickListener { showOverlay(Feature.SURVEY) }
        binding.navVln.visibility = View.GONE
        binding.navHil.setOnClickListener { showOverlay(Feature.HIL) }
        binding.overlayClose.setOnClickListener { closeOverlay() }
        binding.overlayScrim.setOnClickListener { closeOverlay() }
        flightController = FlightFeatureController(
            this,
            binding.featureContent,
            eventRecorder::exportToDownloads,
            cameraDiscovery,
            accountIssue = ::accountFlightIssue,
            recordEvent = eventRecorder::record,
            onOpenSurvey = { showOverlay(Feature.SURVEY) },
            onExternalIntervention = { reason, completion ->
                surveyController?.pauseForExternalIntervention(reason, completion) ?: completion()
            },
            onMapPrivacyAccepted = ::ensureLocationPermission,
            initialMapState = savedInstanceState,
            onCloseSurveyPip = ::closeOverlay,
            onMapFullscreenChanged = { fullscreen ->
                mapFullscreen = fullscreen
                applyPrimaryChromeVisibility()
            },
        )
        if (hilRuntimeEnabled) ensureHilRuntime()
        eventRecorder.record("app_session_start", mapOf("package" to packageName))
        renderCurrentState()
        handleDebugIntent(intent)
        binding.root.post { AppLanguageController.maybeShowFirstLaunch(this) }
    }

    private fun ensureVlnController(): DeferredFeatureController {
        return vlnController ?: DeferredFeatureController(
            this,
            binding.vlnPanelHost,
            ::aircraftSnapshot,
            hilSession,
            cameraDiscovery,
            accountIssue = ::accountFlightIssue,
            onOpenMore = { showOverlay(Feature.MORE) },
            onOpenLog = { showOverlay(Feature.LOG) },
            onFramePreviewReady = ::closeOverlay,
            onStatusChanged = ::appendModelLog,
            onToggleSimulator = ::setSimulatorManually,
        ).also { controller ->
            vlnController = controller
            controller.renderAircraftConnection(aircraftSnapshot.connected)
        }
    }

    private fun ensureHilRuntime() {
        hilRuntimeEnabled = true
        if (!hilRuntimeListenersAttached) {
            hilSession.addListener(hilSurveySafetyListener)
            hilSimulatorLifecycle.addListener(simulatorLifecycleLogListener)
            hilRuntimeListenersAttached = true
        }
        if (activityStarted) {
            hilSafetyHandler.removeCallbacks(hilSafetyTick)
            hilSafetyHandler.post(hilSafetyTick)
            simulatorPoseSource.start(hilSession::submitSimulatorPose)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        flightController?.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onPause() {
        flightController?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(ACCOUNT_PROMPT_HANDLED_KEY, accountStartupPromptHandled)
        flightController?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        flightController?.onLowMemory()
        super.onLowMemory()
    }

    @Deprecated("Uses the platform back callback for the current Activity base class")
    override fun onBackPressed() {
        when {
            binding.overlayPanel.visibility == View.VISIBLE -> closeOverlay()
            flightController?.exitFullscreenMapIfNeeded() == true -> Unit
            else -> super.onBackPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::accountController.isInitialized) accountController.refresh()
        scheduleAccountStartupPrompt()
        activityStarted = true
        if (hilRuntimeEnabled) {
            hilSafetyHandler.removeCallbacks(hilSafetyTick)
            hilSafetyHandler.post(hilSafetyTick)
            simulatorPoseSource.start(hilSession::submitSimulatorPose)
        }
        DjiSdkBootstrap.addListener(sdkListener)
        flightCheckController.start()
        telemetrySource.start { snapshot ->
            aircraftSnapshot = snapshot
            runOnUiThread(::renderCurrentState)
        }
    }

    override fun onStop() {
        activityStarted = false
        hilSafetyHandler.removeCallbacks(hilSafetyTick)
        surveyController?.pauseForLifecycle(getString(R.string.app_entered_background))
        if (hilSession.status()?.running == true) {
            // Match V4: opening Android hotspot/network settings must not tear down HIL.
            // HIL is observation-only; flight/survey control is still paused above.
            super.onStop()
            return
        }
        if (hilRuntimeEnabled) simulatorPoseSource.stop()
        telemetrySource.stop()
        flightCheckController.stop()
        DjiSdkBootstrap.removeListener(sdkListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (hilRuntimeListenersAttached) {
            hilSession.removeListener(hilSurveySafetyListener)
            hilSimulatorLifecycle.removeListener(simulatorLifecycleLogListener)
        }
        eventRecorder.record("app_session_end")
        if (::flightCheckController.isInitialized) flightCheckController.stop()
        if (::accountController.isInitialized) accountController.close()
        // Survey owns replay markers and map-backed overlays. Tear it down while
        // the map is still alive so it can remove them deterministically.
        surveyController?.close()
        flightController?.close()
        vlnController?.close()
        hilController?.close()
        flightController = null
        surveyController = null
        vlnController = null
        hilController = null
        aircraftStatusController = null
        moreControlController = null
        modelMonitorController = null
        hilOfflineRegression?.close()
        hilOfflineRegression = null
        simulatorMotionRegressionGeneration += 1L
        simulatorMotionRegression?.destroy()
        simulatorMotionRegression = null
        hilRawRegressionGeneration += 1L
        hilSession.close()
        if (hilRuntimeEnabled) {
            hilSimulatorLifecycle.close()
            simulatorPoseSource.close()
        }
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker for compatibility with the current Activity base class")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DeferredFeatureController.REQUEST_IMPORT_MODEL && resultCode == RESULT_OK) {
            data?.data?.let { vlnController?.importModel(it) }
        }
        if (resultCode == RESULT_OK && requestCode in setOf(
                SurveyFeatureController.REQUEST_IMPORT_DSM,
                SurveyFeatureController.REQUEST_IMPORT_BUILDING_HEIGHT,
                SurveyFeatureController.REQUEST_IMPORT_MISSION,
                SurveyFeatureController.REQUEST_IMPORT_CHECKPOINT,
                SurveyFeatureController.REQUEST_IMPORT_V86_SFM_TEST,
            )
        ) {
            data?.data?.let { surveyController?.importDocument(requestCode, it) }
        }
    }

    private fun render(state: AppUiState) = with(binding) {
        sdkStatus.text = getString(if (aircraftSnapshot.connected) R.string.state_connected else R.string.state_disconnected)
        aircraftStatus.text = "${aircraftSnapshot.flightMode ?: "--"} · ${getString(
            if (aircraftSnapshot.isFlying) R.string.flight_state_flying else R.string.flight_state_ground_standby,
        )}"
        gpsStatusText.text = "GPS ${aircraftSnapshot.gpsSatelliteCount ?: 0}"
        linkSignalIcon.setLevel(aircraftSnapshot.remoteControllerSignalPercent ?: -1)
        linkSignalText.text = aircraftSnapshot.remoteControllerSignalPercent?.let { "$it%" } ?: "--"
        aircraftBatteryRing.setLevel(aircraftSnapshot.aircraftBatteryPercent ?: -1)
        rcBatteryText.setLevel(aircraftSnapshot.remoteControllerBatteryPercent ?: -1)
        flightEnduranceBar.render(aircraftSnapshot)
        aircraftStatusDetail.text = buildString {
            val sdkPhase = when {
                sdkState.registered -> "registered"
                sdkState.lastError != null -> "error"
                else -> sdkState.initEvent.lowercase()
            }
            append("MSDK 5 | $sdkPhase\n")
            append("Product: ${aircraftSnapshot.productId ?: sdkState.productId ?: "none"} | connected: ${if (aircraftSnapshot.connected) "yes" else "no"}\n")
            append("Video feed: ${if (aircraftSnapshot.connected) "ready" else "none"}")
            append("\n${resolve(accountSnapshot.label)}")
        }
        errorStatus.visibility = View.GONE
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_LOCATION_PERMISSION,
        )
    }

    private fun handleDebugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (intent?.action) {
            ACTION_HIL_OFFLINE_REGRESSION -> startHilOfflineRegression(intent.getStringExtra("host"))
            ACTION_HIL_RAW_SOURCE_REGRESSION -> startHilRawSourceRegression(
                rebind = false,
                requestedHz = intent.getIntExtra("hz", 100),
            )
            ACTION_HIL_RAW_REBIND_REGRESSION -> startHilRawSourceRegression(
                rebind = true,
                requestedHz = intent.getIntExtra("hz", 100),
            )
            ACTION_HIL_SIMULATOR_MOTION_REGRESSION -> startHilSimulatorMotionRegression(
                requestedHz = intent.getIntExtra("hz", 100),
                controlHz = intent.getIntExtra("control_hz", 20),
            )
            ACTION_HIL_NATIVE_RATE_RESTART -> restartHilNativeRateExperiment(
                requestedOutputHz = intent.getIntExtra("hz", BuildConfig.HIL_NATIVE_RATE_EXPERIMENT_HZ),
            )
            ACTION_CAMERA_CADENCE_TEST -> flightController?.startCameraCadenceTest(
                requestedShotsPerStage = intent.getIntExtra("shots", 6),
                requestedPeriodsMillis = intent.getStringExtra("periods_ms"),
                lowResolution = intent.getBooleanExtra("low_resolution", false),
                nativeInterval = intent.getBooleanExtra("native_interval", false),
                controlOrSurveyActive = surveyController?.isExecutionActive() == true ||
                    vlnController?.isControlActive() == true,
            )
        }
    }

    private fun startHilSimulatorMotionRegression(requestedHz: Int, controlHz: Int) {
        ensureHilRuntime()
        val safeControlHz = controlHz.coerceIn(5, 100)
        val regressionGeneration = ++simulatorMotionRegressionGeneration
        val previousController = simulatorMotionRegression
        simulatorMotionRegression = null
        previousController?.destroy()
        if (!simulatorManager.isSimulatorEnabled()) {
            AppDiagnosticLogger.error(REGRESSION_TAG, "HIL_MOTION RESULT=FAIL reason=simulator-not-enabled")
            eventRecorder.record("hil_motion_result", mapOf(
                "result" to "FAIL",
                "reason" to "simulator-not-enabled",
            ))
            return
        }
        startHilRawSourceRegression(
            rebind = false,
            requestedHz = requestedHz,
            manageSimulatorLifecycle = false,
        )
        var takeoffRequested = false
        var virtualStickRequested = false
        var motionRequested = false
        var landingRequested = false
        var terminalReported = false
        var lastLoggedState: String? = null
        AppDiagnosticLogger.info(
            REGRESSION_TAG,
            "HIL_MOTION phase=start poseHz=$requestedHz controlHz=$safeControlHz",
        )
        val controller = SimulatorFlightTestController(context = this, onSnapshot = motionSnapshot@{ state ->
            if (regressionGeneration != simulatorMotionRegressionGeneration || terminalReported) {
                return@motionSnapshot
            }
            val stateSignature = "${state.phase}|${state.simulatorEnabled}|${state.motorsOn}|" +
                "${state.flying}|${state.virtualStickEnabled}|${state.advancedModeEnabled}|${state.message}"
            if (stateSignature != lastLoggedState) {
                lastLoggedState = stateSignature
                Log.i(
                    REGRESSION_TAG,
                    "HIL_MOTION phase=${state.phase} simulator=${state.simulatorEnabled} " +
                        "motors=${state.motorsOn} flying=${state.flying} vs=${state.virtualStickEnabled} " +
                        "advanced=${state.advancedModeEnabled} x=${state.positionX} y=${state.positionY} " +
                    "z=${state.positionZ} message=${state.message}",
                )
                eventRecorder.record("hil_motion_state", mapOf(
                    "phase" to state.phase.name,
                    "simulator" to state.simulatorEnabled,
                    "motors" to state.motorsOn,
                    "flying" to state.flying,
                    "virtual_stick" to state.virtualStickEnabled,
                    "advanced" to state.advancedModeEnabled,
                    "x" to state.positionX,
                    "y" to state.positionY,
                    "z" to state.positionZ,
                    "message" to state.message,
                ))
            }
            when {
                !takeoffRequested && state.simulatorEnabled && !state.flying -> {
                    takeoffRequested = true
                    hilLifecycleHandler.postDelayed({ simulatorMotionRegression?.takeOff() }, 500L)
                }
                !virtualStickRequested && state.simulatorEnabled && state.flying &&
                    !aircraftSnapshot.flightMode.orEmpty().contains("TAKEOFF", ignoreCase = true) &&
                    state.positionZ?.let { kotlin.math.abs(it) >= MOTION_REGRESSION_MIN_TAKEOFF_METERS } == true -> {
                    virtualStickRequested = true
                    hilLifecycleHandler.postDelayed(
                        { simulatorMotionRegression?.enableVirtualStick() },
                        MOTION_REGRESSION_CONTROL_SETTLE_MILLIS,
                    )
                }
                virtualStickRequested && !motionRequested && state.flying &&
                    state.virtualStickEnabled && state.advancedModeEnabled -> {
                    motionRequested = true
                    hilLifecycleHandler.postDelayed({ simulatorMotionRegression?.moveForwardTwoSeconds() }, 500L)
                }
                motionRequested && !landingRequested &&
                    state.phase == SimulatorFlightTestController.Phase.VIRTUAL_STICK_READY -> {
                    landingRequested = true
                    hilLifecycleHandler.postDelayed({ simulatorMotionRegression?.land() }, 500L)
                }
                landingRequested && !state.flying && !state.motorsOn -> {
                    terminalReported = true
                    Log.i(REGRESSION_TAG, "HIL_MOTION RESULT=PASS reason=takeoff-move-land-complete")
                    eventRecorder.record("hil_motion_result", mapOf(
                        "result" to "PASS",
                        "reason" to "takeoff-move-land-complete",
                    ))
                    val finishedController = simulatorMotionRegression
                    simulatorMotionRegression = null
                    finishedController?.destroy()
                }
                state.phase == SimulatorFlightTestController.Phase.ERROR -> {
                    terminalReported = true
                    AppDiagnosticLogger.error(REGRESSION_TAG, "HIL_MOTION RESULT=FAIL reason=${state.message}")
                    eventRecorder.record("hil_motion_result", mapOf(
                        "result" to "FAIL",
                        "reason" to state.message,
                    ))
                    val failedController = simulatorMotionRegression
                    simulatorMotionRegression = null
                    failedController?.destroy()
                }
            }
        }, sendFrequencyHz = safeControlHz)
        simulatorMotionRegression = controller
        controller.refresh()
    }

    private fun restartHilNativeRateExperiment(requestedOutputHz: Int) {
        ensureHilRuntime()
        val nativeHz = BuildConfig.HIL_NATIVE_RATE_EXPERIMENT_HZ
        if (nativeHz <= 0) {
            Log.e(REGRESSION_TAG, "HIL_RATE_RESTART RESULT=FAIL reason=not-experiment-build")
            return
        }
        if (!aircraftSnapshot.connected || !aircraftSnapshot.simulatorActive || !simulatorManager.isSimulatorEnabled()) {
            Log.e(
                REGRESSION_TAG,
                "HIL_RATE_RESTART RESULT=FAIL reason=simulator-not-active " +
                    "connected=${aircraftSnapshot.connected} reported=${aircraftSnapshot.simulatorActive} " +
                    "manager=${simulatorManager.isSimulatorEnabled()}",
            )
            return
        }
        val backend = DjiV5HilSimulatorBackend(context = this)
        val origin = savedSimulatorOrigin()
        ++hilRawRegressionGeneration
        simulatorPoseSource.stop()
        hilSimulatorLifecycle.stopPreservingSimulator()
        Log.i(REGRESSION_TAG, "HIL_RATE_RESTART phase=disable nativeRequest=${nativeHz}Hz")
        backend.disable { disableSuccess, disableDetail ->
            hilLifecycleHandler.post {
                if (!disableSuccess && backend.isEnabled()) {
                    Log.e(
                        REGRESSION_TAG,
                        "HIL_RATE_RESTART RESULT=FAIL phase=disable detail=$disableDetail",
                    )
                    return@post
                }
                Log.i(REGRESSION_TAG, "HIL_RATE_RESTART phase=disabled detail=$disableDetail")
                hilLifecycleHandler.postDelayed({
                    backend.enable(origin) { enableSuccess, enableDetail ->
                        hilLifecycleHandler.post {
                            if (!enableSuccess && !backend.isEnabled()) {
                                Log.e(
                                    REGRESSION_TAG,
                                    "HIL_RATE_RESTART RESULT=FAIL phase=enable detail=$enableDetail",
                                )
                                return@post
                            }
                            Log.i(
                                REGRESSION_TAG,
                                "HIL_RATE_RESTART RESULT=PASS nativeRequest=${nativeHz}Hz detail=$enableDetail",
                            )
                            hilLifecycleHandler.postDelayed({
                                startHilRawSourceRegression(
                                    rebind = false,
                                    requestedHz = requestedOutputHz.coerceIn(2, 150),
                                    manageSimulatorLifecycle = false,
                                )
                            }, RATE_EXPERIMENT_RAW_SETTLE_MILLIS)
                        }
                    }
                }, RATE_EXPERIMENT_RESTART_SETTLE_MILLIS)
            }
        }
    }

    private fun startHilOfflineRegression(host: String?) {
        hilOfflineRegression?.close()
        hilOfflineRegression = HilOfflineRegressionRunner(object : HilOfflineRegressionRunner.Listener {
            override fun onLog(message: String) {
                Log.i(REGRESSION_TAG, message)
            }

            override fun onFinished(result: HilOfflineRegressionRunner.Result) {
                Log.i(
                    REGRESSION_TAG,
                    "HIL_OFFLINE RESULT=${if (result.passed) "PASS" else "FAIL"} " +
                        "source=MOCK synthetic=true sent=${result.sentPoseCount} received=${result.receivedPoseCount} " +
                        "poseHz=${"%.1f".format(result.measuredPoseHz)} reason=${result.reason}",
                )
            }
        }).also { it.start(host?.takeIf(String::isNotBlank) ?: "127.0.0.1") }
    }

    private fun startHilRawSourceRegression(
        rebind: Boolean,
        requestedHz: Int,
        manageSimulatorLifecycle: Boolean = true,
    ) {
        ensureHilRuntime()
        val generation = ++hilRawRegressionGeneration
        val startedAt = SystemClock.elapsedRealtime()
        var firstSequence = simulatorPoseSource.latest()?.sequence ?: -1L
        var samples = 0
        val simulatorOrigin = savedSimulatorOrigin()
        Log.i(
            REGRESSION_TAG,
            "HIL_POSE START source=DJI_FC_TELEMETRY+SimulatorState synthetic=false " +
            "rebind=$rebind requestedHz=$requestedHz",
        )
        eventRecorder.record("hil_pose_regression_start", mapOf(
            "rebind" to rebind,
            "requested_hz" to requestedHz,
            "manage_simulator_lifecycle" to manageSimulatorLifecycle,
            "origin_latitude" to simulatorOrigin.latitude,
            "origin_longitude" to simulatorOrigin.longitude,
        ))
        simulatorPoseSource.setOutputFrequencyHz(requestedHz)
        simulatorPoseSource.start(hilSession::submitSimulatorPose)
        val baselineDiagnostics = simulatorPoseSource.diagnostics()
        if (manageSimulatorLifecycle) {
            hilSimulatorLifecycle.start(
                simulatorOrigin,
                HilSimulatorLifecycleCoordinator.Observation(
                    connected = aircraftSnapshot.connected,
                    simulatorReportedActive = aircraftSnapshot.simulatorActive,
                    simulatorAirborne = aircraftSnapshot.simulatorActive &&
                        (aircraftSnapshot.motorsOn || aircraftSnapshot.isFlying),
                ),
            )
        } else {
            hilSimulatorLifecycle.stopPreservingSimulator()
        }
        if (rebind) hilLifecycleHandler.postDelayed({ simulatorPoseSource.refreshListener() }, 2_000L)
        fun poll() {
            if (generation != hilRawRegressionGeneration) return
            val sample = simulatorPoseSource.latest()
            if (sample != null && sample.sequence != firstSequence) {
                firstSequence = sample.sequence
                samples += 1
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < RAW_REGRESSION_DURATION_MILLIS) {
                hilLifecycleHandler.postDelayed(::poll, RAW_REGRESSION_POLL_MILLIS)
                return
            }
            val fresh = hilSession.simulatorSourceFresh()
            val diagnostics = simulatorPoseSource.diagnostics()
            val rawCallbacks = diagnostics.rawCallbackCount - baselineDiagnostics.rawCallbackCount
            val rawValueChanges = diagnostics.rawValueChangeCount - baselineDiagnostics.rawValueChangeCount
            val telemetryChanges = diagnostics.telemetryChangeCount - baselineDiagnostics.telemetryChangeCount
            val locationCallbacks = diagnostics.locationCallbackCount - baselineDiagnostics.locationCallbackCount
            val attitudeCallbacks = diagnostics.attitudeCallbackCount - baselineDiagnostics.attitudeCallbackCount
            val velocityCallbacks = diagnostics.velocityCallbackCount - baselineDiagnostics.velocityCallbackCount
            val headingCallbacks = diagnostics.headingCallbackCount - baselineDiagnostics.headingCallbackCount
            val pollSamples = diagnostics.pollSampleCount - baselineDiagnostics.pollSampleCount
            val elapsedSeconds = elapsed.coerceAtLeast(1L) / 1_000.0
            val passed = aircraftSnapshot.connected && samples >= RAW_REGRESSION_MIN_SAMPLES && fresh
            Log.i(
                REGRESSION_TAG,
                "HIL_POSE RESULT=${if (passed) "PASS" else "FAIL"} " +
                    "nativeRequest=${BuildConfig.HIL_NATIVE_RATE_EXPERIMENT_HZ}Hz " +
                    "source=DJI_FC_TELEMETRY+SimulatorState synthetic=false rebind=$rebind samples=$samples " +
                    "fresh=$fresh rate=${"%.1f".format(sample?.measuredRateHz ?: 0.0)} " +
                    "raw=${"%.1f".format(rawCallbacks / elapsedSeconds)}Hz/${"%.1f".format(rawValueChanges / elapsedSeconds)}HzChanged " +
                    "location=${"%.1f".format(locationCallbacks / elapsedSeconds)}Hz " +
                    "attitude=${"%.1f".format(attitudeCallbacks / elapsedSeconds)}Hz " +
                    "velocity=${"%.1f".format(velocityCallbacks / elapsedSeconds)}Hz " +
                    "heading=${"%.1f".format(headingCallbacks / elapsedSeconds)}Hz " +
                    "telemetryChanges=$telemetryChanges pollSamples=$pollSamples " +
                    "lastSource=${diagnostics.lastSource} connected=${aircraftSnapshot.connected} " +
                    "lifecycle=${resolve(hilSimulatorLifecycle.currentMessage())}",
            )
            eventRecorder.record("hil_pose_regression_result", mapOf(
                "result" to if (passed) "PASS" else "FAIL",
                "rebind" to rebind,
                "samples" to samples,
                "fresh" to fresh,
                "reported_output_hz" to (sample?.measuredRateHz ?: 0.0),
                "raw_callbacks" to rawCallbacks,
                "raw_value_changes" to rawValueChanges,
                "raw_callback_hz" to rawCallbacks / elapsedSeconds,
                "raw_value_change_hz" to rawValueChanges / elapsedSeconds,
                "telemetry_changes" to telemetryChanges,
                "location_callbacks" to locationCallbacks,
                "location_callback_hz" to locationCallbacks / elapsedSeconds,
                "attitude_callbacks" to attitudeCallbacks,
                "attitude_callback_hz" to attitudeCallbacks / elapsedSeconds,
                "velocity_callbacks" to velocityCallbacks,
                "velocity_callback_hz" to velocityCallbacks / elapsedSeconds,
                "heading_callbacks" to headingCallbacks,
                "heading_callback_hz" to headingCallbacks / elapsedSeconds,
                "poll_samples" to pollSamples,
                "last_source" to diagnostics.lastSource,
                "connected" to aircraftSnapshot.connected,
                "lifecycle" to resolve(hilSimulatorLifecycle.currentMessage()),
            ))
        }
        hilLifecycleHandler.post(::poll)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        surveyController?.onMediaLocationPermissionResult(requestCode, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            flightController?.refreshPhoneLocationPermission()
        }
    }

    private fun savedSimulatorOrigin(): HilSimulatorLifecycleCoordinator.Origin {
        val saved = simulatorOriginStore.load()
        simulatorPoseSource.setFallbackOrigin(saved.latitude, saved.longitude)
        return HilSimulatorLifecycleCoordinator.Origin(saved.latitude, saved.longitude)
    }

    private fun setSimulatorManually(
        origin: edu.playground.djivln.hil.SimulatorOrigin,
        enabled: Boolean,
        callback: (Boolean, String) -> Unit,
    ) {
        ensureHilRuntime()
        simulatorPoseSource.setFallbackOrigin(origin.latitude, origin.longitude)
        hilSimulatorLifecycle.setManualEnabled(
            enabled = enabled,
            origin = HilSimulatorLifecycleCoordinator.Origin(origin.latitude, origin.longitude),
            observation = simulatorObservation(),
            callback = callback,
        )
    }

    private fun setSimulatorOriginFromSurveyMap(
        point: edu.playground.djivln.survey.GeoPoint,
        callback: (Boolean, String) -> Unit,
    ) {
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0
        ) return callback(false, getString(R.string.simulator_origin_invalid_simple))
        if (aircraftSnapshot.isFlying || aircraftSnapshot.motorsOn) {
            return callback(false, getString(R.string.simulator_origin_switch_blocked_airborne))
        }
        val origin = edu.playground.djivln.hil.SimulatorOrigin(point.latitude, point.longitude)
        simulatorOriginStore.save(origin)
        simulatorPoseSource.setFallbackOrigin(origin.latitude, origin.longitude)
        eventRecorder.record(
            "simulator_origin_map_selected",
            mapOf("latitude" to origin.latitude, "longitude" to origin.longitude),
        )
        val active = simulatorManager.isSimulatorEnabled() || aircraftSnapshot.simulatorActive
        if (!active) {
            callback(true, getString(R.string.simulator_origin_saved, origin.label()))
            return
        }
        val lifecycleOrigin = HilSimulatorLifecycleCoordinator.Origin(origin.latitude, origin.longitude)
        hilSimulatorLifecycle.setManualEnabled(
            enabled = false,
            origin = lifecycleOrigin,
            observation = simulatorObservation(),
        ) { stopped, detail ->
            if (!stopped) {
                callback(false, getString(R.string.simulator_origin_switch_stop_failed, detail))
                return@setManualEnabled
            }
            hilLifecycleHandler.postDelayed({
                hilSimulatorLifecycle.setManualEnabled(
                    enabled = true,
                    origin = lifecycleOrigin,
                    observation = simulatorObservation(),
                ) { started, startDetail ->
                    callback(
                        started,
                        if (started) getString(R.string.simulator_origin_switched_and_restarted, origin.label())
                        else getString(R.string.simulator_origin_saved_restart_failed, startDetail),
                    )
                }
            }, 600L)
        }
    }

    private fun simulatorObservation(): HilSimulatorLifecycleCoordinator.Observation {
        val sample = simulatorPoseSource.latest()
        return HilSimulatorLifecycleCoordinator.Observation(
            connected = aircraftSnapshot.connected,
            simulatorReportedActive = aircraftSnapshot.simulatorActive,
            simulatorAirborne = sample?.let { it.motorsOn || it.flying }
                ?: (aircraftSnapshot.simulatorActive && (aircraftSnapshot.motorsOn || aircraftSnapshot.isFlying)),
        )
    }

    private fun renderCurrentState() {
        if (!::binding.isInitialized) return
        if (hilRuntimeEnabled) monitorHilAuthoritySource()
        if (!aircraftSnapshot.sticksActive) {
            surveyStickTakeoverLatched = false
        } else if (!surveyStickTakeoverLatched && surveyController?.isExecutionActive() == true) {
            surveyStickTakeoverLatched = true
            eventRecorder.record(
                "survey_manual_takeover",
                mapOf(
                    "left_horizontal" to aircraftSnapshot.leftStickHorizontal,
                    "left_vertical" to aircraftSnapshot.leftStickVertical,
                    "right_horizontal" to aircraftSnapshot.rightStickHorizontal,
                    "right_vertical" to aircraftSnapshot.rightStickVertical,
                    "flight_mode" to aircraftSnapshot.flightMode,
                    "simulator" to aircraftSnapshot.simulatorActive,
                ),
            )
            surveyController?.pauseForExternalIntervention(getString(R.string.survey_rc_stick_intervention))
        }
        val externalInterventionReason = edu.playground.djivln.survey.SurveyExternalInterventionPolicy.reason(
            aircraftSnapshot.flightMode,
            aircraftSnapshot.goHomeState,
            aircraftSnapshot.lowBatteryRthState,
        )
        if (externalInterventionReason == null) {
            surveyFailsafeModeLatched = false
        } else if (!surveyFailsafeModeLatched && surveyController?.isExecutionActive() == true) {
            surveyFailsafeModeLatched = true
            surveyController?.pauseForExternalIntervention(resolve(externalInterventionReason))
        }
        render(AppUiStateReducer.fromSdk(sdkState, aircraftSnapshot))
        if (hilRuntimeEnabled) {
            hilSession.submitTelemetry(aircraftSnapshot)
            simulatorPoseSource.submitTelemetry(aircraftSnapshot)
        }
        val flightStateLogSignature = FlightTelemetryLogPayload.transitionSignature(aircraftSnapshot)
        if (flightStateLogSignature != lastFlightStateLogSignature) {
            lastFlightStateLogSignature = flightStateLogSignature
            eventRecorder.record("flight_state_changed", FlightTelemetryLogPayload.fields(aircraftSnapshot))
        }
        if (hilRuntimeEnabled) {
            hilSimulatorLifecycle.observe(
                savedSimulatorOrigin(),
                simulatorObservation(),
            )
            hilController?.onAircraftSnapshot(aircraftSnapshot)
        }
        if (aircraftSnapshot.updatedAtNanos - lastTelemetryLogNanos >= 500_000_000L) {
            lastTelemetryLogNanos = aircraftSnapshot.updatedAtNanos
            eventRecorder.record("telemetry", FlightTelemetryLogPayload.fields(aircraftSnapshot))
        }
        recordMemorySnapshotIfDue()
        flightController?.render(
            aircraftSnapshot,
            resolve(AppUiStateReducer.fromSdk(sdkState, aircraftSnapshot).flight.diagnostics),
        )
        vlnController?.renderAircraftConnection(aircraftSnapshot.connected)
        surveyController?.refreshLiveState()
        aircraftStatusController?.render()
        if (::flightCheckController.isInitialized) flightCheckController.refreshConnectionState()
        moreControlController?.renderStatus()
    }

    private fun showOverlay(feature: Feature) {
        activeOverlay = feature
        hilController?.close()
        moreControlController?.close()
        hilController = null
        aircraftStatusController = null
        moreControlController = null
        modelMonitorController = null
        binding.overlayContent.removeAllViews()
        binding.quickMenu.visibility = View.GONE
        binding.overlayScrim.visibility = if (feature == Feature.SURVEY || feature == Feature.LOG) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.overlayPanel.visibility = View.VISIBLE
        configureOverlayLayout(feature)
        when (feature) {
            Feature.STATUS -> {
                binding.overlayTitle.setText(R.string.aircraft_status_title)
                AircraftStatusController(
                    this,
                    binding.overlayContent,
                    ::aircraftSnapshot,
                    onQualification = { flightController?.showQualification() },
                ).also { aircraftStatusController = it }
            }
            Feature.MORE -> {
                ensureHilRuntime()
                binding.overlayTitle.setText(R.string.more_controls)
                MoreControlController(
                    this,
                    binding.overlayContent,
                    ensureVlnController(),
                    onQualification = { flightController?.showQualification() },
                    onOpenLog = { showOverlay(Feature.LOG) },
                    exportDiagnosticLog = eventRecorder::exportToDownloads,
                    accountStatus = { resolve(accountSnapshot.label) },
                    accountLoggedIn = { accountSnapshot.loggedIn },
                    loginDjiAccount = { accountController.login() },
                    canChangeLanguage = {
                        !aircraftSnapshot.isFlying && !aircraftSnapshot.motorsOn &&
                            surveyController?.isExecutionActive() != true &&
                            vlnController?.isControlActive() != true
                    },
                    hilSession = hilSession,
                    simulatorPoseSource = simulatorPoseSource,
                    simulatorLifecycle = hilSimulatorLifecycle,
                    snapshot = ::aircraftSnapshot,
                    onClose = ::closeOverlay,
                ).also { moreControlController = it }
            }
            Feature.LOG -> {
                binding.overlayTitle.setText(R.string.vln_runtime_log)
                ModelMonitorController(
                    this,
                    binding.overlayContent,
                    modelLogLines.toList(),
                    onClear = ::clearModelLog,
                    onClose = ::closeOverlay,
                ).also {
                    modelMonitorController = it
                }
            }
            Feature.SURVEY -> {
                binding.overlayTitle.setText(R.string.survey_planning)
                val controller = surveyController ?: SurveyFeatureController(
                    this,
                    binding.overlayContent,
                    ::aircraftSnapshot,
                    hilSession,
                    cameraDiscovery,
                    mapEditor = requireNotNull(flightController),
                    recordEvent = eventRecorder::record,
                    accountIssue = ::accountFlightIssue,
                    onPhotoCaptureFeedback = ::showSurveyPhotoCaptureFeedback,
                    onMissionChanged = { mission ->
                        if (mission == null) flightController?.clearSurveyMission()
                        else flightController?.renderSurveyMission(mission)
                    },
                    onEtaChanged = { eta -> flightController?.renderSurveyEta(eta) },
                    onCenterMap = { flightController?.centerMapOnLiveLocation() },
                    isSimulatorActive = {
                        simulatorManager.isSimulatorEnabled() || aircraftSnapshot.simulatorActive
                    },
                    simulatorOriginPoint = {
                        simulatorOriginStore.load().let {
                            edu.playground.djivln.survey.GeoPoint(it.latitude, it.longitude)
                        }
                    },
                    onSetSimulatorOrigin = ::setSimulatorOriginFromSurveyMap,
                    onClose = ::closeOverlay,
                ).also { surveyController = it }
                controller.attachTo(binding.overlayContent)
                controller.setMapEditingEnabled(true)
                flightController?.attachMapToSurvey(controller.mapHost())
            }
            Feature.HIL -> {
                ensureHilRuntime()
                binding.overlayTitle.setText(R.string.hil_simulation_settings)
                HilFeatureController(
                    this,
                    binding.overlayContent,
                    hilSession,
                    ::aircraftSnapshot,
                    simulatorPoseSource,
                    hilSimulatorLifecycle,
                    cameraSourceLabels = { vlnController?.cameraSourceLabels().orEmpty() },
                    selectedCameraSourceIndex = { vlnController?.selectedCameraSourceIndex() ?: 0 },
                    selectCameraSource = { vlnController?.selectCameraSource(it) },
                ).also {
                    hilController = it
                }
            }
            Feature.FLIGHT, Feature.VLN -> closeOverlay()
        }
    }

    private fun closeOverlay() {
        activeOverlay = null
        surveyController?.setMapEditingEnabled(false)
        flightController?.setSurveyPlanningMode(false)
        hilController?.close()
        moreControlController?.close()
        hilController = null
        aircraftStatusController = null
        moreControlController = null
        modelMonitorController = null
        binding.overlayContent.removeAllViews()
        binding.overlayPanel.visibility = View.GONE
        binding.overlayScrim.visibility = View.GONE
        binding.quickMenu.visibility = View.GONE
        applyPrimaryChromeVisibility()
    }

    private fun appendModelLog(message: String) {
        if (message.isBlank()) return
        modelLogLines.addLast(message)
        while (modelLogLines.size > 160) modelLogLines.removeFirst()
        modelMonitorController?.append(message)
        moreControlController?.renderStatus()
    }

    private fun recordMemorySnapshotIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMemoryLogElapsedMillis < MEMORY_LOG_INTERVAL_MILLIS) return
        lastMemoryLogElapsedMillis = now
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        eventRecorder.record(
            "process_memory",
            mapOf(
                "java_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
                "java_heap_bytes" to runtime.totalMemory(),
                "java_heap_max_bytes" to runtime.maxMemory(),
                "native_heap_allocated_bytes" to Debug.getNativeHeapAllocatedSize(),
                "total_pss_kb" to memoryInfo.totalPss,
                "total_private_dirty_kb" to memoryInfo.totalPrivateDirty,
            ),
        )
    }

    private fun clearModelLog() {
        modelLogLines.clear()
    }

    private fun monitorHilAuthoritySource() {
        if (hilSession.status()?.running != true) {
            hilSimulatorObservedActive = false
            hilSimulatorSourceObserved = false
            return
        }
        val simulatorActuallyActive = DjiV5SimulatorAuthority.isActive(
            managerEnabled = simulatorManager.isSimulatorEnabled(),
            keyReportedActive = aircraftSnapshot.simulatorActive,
        )
        if (simulatorActuallyActive) hilSimulatorObservedActive = true
        val source = hilSession.simulatorSourceStatus()
        if (source.received) hilSimulatorSourceObserved = true
        val unsafeReason = when {
            !aircraftSnapshot.connected -> getString(R.string.aircraft_connection_lost)
            hilSimulatorObservedActive && !simulatorActuallyActive -> getString(R.string.dji_simulator_exited)
            hilSimulatorObservedActive && hilSimulatorSourceObserved && !hilSession.simulatorSourceFresh() ->
                getString(R.string.dji_simulator_raw_timeout)
            else -> null
        }
        if (unsafeReason != null && surveyController?.isHilExecutionActive() == true) {
            surveyController?.pauseForExternalIntervention(getString(R.string.hil_paused_for_reason, unsafeReason))
        }
        // Match V4: missing/unsupported Simulator RAW is a control gate, not a reason to tear
        // down an independently healthy UDP/TCP session. This also lets a later DJI Assistant
        // or firmware-side Simulator activation recover without restarting the network link.
    }

    private fun configureOverlayLayout(feature: Feature) {
        binding.overlayPanel.setBackgroundResource(
            when (feature) {
                Feature.SURVEY -> android.R.color.transparent
                Feature.MORE -> R.drawable.cockpit_panel_strong
                else -> R.drawable.cockpit_panel
            },
        )
        applyPrimaryChromeVisibility()
        val usesOwnChrome = feature == Feature.LOG || feature == Feature.MORE || feature == Feature.SURVEY
        binding.overlayTitle.visibility = if (usesOwnChrome) View.GONE else View.VISIBLE
        binding.overlayClose.visibility = if (usesOwnChrome) View.GONE else View.VISIBLE
        (binding.overlayContent.layoutParams as FrameLayout.LayoutParams).topMargin = if (usesOwnChrome) 0 else dp(48)
        val params = FrameLayout.LayoutParams(
            when (feature) {
                Feature.LOG -> dp(320)
                Feature.MORE -> dp(420)
                else -> FrameLayout.LayoutParams.MATCH_PARENT
            },
            when (feature) {
                Feature.LOG -> dp(95)
                else -> FrameLayout.LayoutParams.MATCH_PARENT
            },
            when (feature) {
                Feature.LOG -> Gravity.TOP or Gravity.START
                Feature.MORE -> Gravity.TOP or Gravity.END
                else -> Gravity.CENTER
            },
        ).apply {
            when (feature) {
                Feature.LOG -> setMargins(dp(8), dp(142), 0, 0)
                Feature.MORE -> setMargins(0, dp(8), dp(8), dp(8))
                else -> setMargins(0, 0, 0, 0)
            }
        }
        binding.overlayPanel.layoutParams = params
    }

    private fun applyPrimaryChromeVisibility() {
        if (!::binding.isInitialized) return
        val visibility = AppChromePolicy.resolve(
            mapFullscreen = mapFullscreen,
            surveyVisible = activeOverlay == Feature.SURVEY,
            vlnRequested = vlnVisible,
        )
        binding.topStatusBar.visibility = if (visibility.topStatusVisible) View.VISIBLE else View.GONE
        binding.vlnPanelHost.visibility = if (visibility.vlnVisible) View.VISIBLE else View.GONE
        if (!visibility.topStatusVisible) binding.aircraftStatusDetail.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun inferenceRuntimeStatus(): String = getString(R.string.public_inference_not_included)

    private fun accountFlightIssue(): String? = DjiAccountFlightPolicy.blockingReason(accountSnapshot)?.let(::resolve)

    private fun showSurveyPhotoCaptureFeedback(success: Boolean) {
        binding.root.removeCallbacks(hideSurveyPhotoFeedback)
        binding.surveyPhotoCaptureFeedbackLabel.apply {
            setText(if (success) R.string.captured_indicator else R.string.capture_failed_indicator)
            setTextColor(if (success) 0xFFFFFFFF.toInt() else 0xFFFF8A80.toInt())
        }
        binding.surveyPhotoCaptureFeedback.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
            animate()
                .alpha(1f)
                .setDuration(80L)
                .withEndAction {
                    animate()
                        .alpha(0f)
                        .setStartDelay(if (success) 260L else 1_200L)
                        .setDuration(180L)
                        .withEndAction(hideSurveyPhotoFeedback)
                        .start()
                }
                .start()
        }
    }

    private fun scheduleAccountStartupPrompt() {
        binding.root.post {
            if (accountStartupPromptHandled || accountStartupPromptShowing || isFinishing || isDestroyed) return@post
            if (!DjiAccountFlightPolicy.shouldPromptAtStartup(accountSnapshot)) return@post
            accountStartupPromptShowing = true
            eventRecorder.record(
                "dji_account_startup_prompt_shown",
                mapOf("state" to accountSnapshot.state.name),
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.login_dji_account)
                .setMessage(
                    if (accountSnapshot.state == edu.playground.djivln.account.DjiAccountState.TOKEN_OUT_OF_DATE) {
                        getString(R.string.dji_login_expired_message)
                    } else {
                        getString(R.string.dji_login_optional_message)
                    },
                )
                .setPositiveButton(R.string.login_dji_account) { _, _ ->
                    accountStartupPromptHandled = true
                    eventRecorder.record("dji_account_startup_prompt_choice", mapOf("choice" to "LOGIN"))
                    accountController.login()
                }
                .setNegativeButton(R.string.action_not_now) { _, _ ->
                    accountStartupPromptHandled = true
                    eventRecorder.record("dji_account_startup_prompt_choice", mapOf("choice" to "SKIP"))
                }
                .setCancelable(false)
                .setOnDismissListener { accountStartupPromptShowing = false }
                .show()
        }
    }

    private enum class Feature {
        FLIGHT,
        STATUS,
        MORE,
        LOG,
        SURVEY,
        VLN,
        HIL,
    }

    private companion object {
        const val MEMORY_LOG_INTERVAL_MILLIS = 30_000L
        const val ACCOUNT_PROMPT_HANDLED_KEY = "dji_account_prompt_handled"
        const val REQUEST_LOCATION_PERMISSION = 8_201
        const val ACTION_HIL_OFFLINE_REGRESSION = "com.openfly.go.v5.action.HIL_OFFLINE_REGRESSION"
        const val ACTION_HIL_RAW_SOURCE_REGRESSION = "com.openfly.go.v5.action.HIL_RAW_SOURCE_REGRESSION"
        const val ACTION_HIL_RAW_REBIND_REGRESSION = "com.openfly.go.v5.action.HIL_RAW_REBIND_REGRESSION"
        const val ACTION_HIL_SIMULATOR_MOTION_REGRESSION = "com.openfly.go.v5.action.HIL_SIMULATOR_MOTION_REGRESSION"
        const val ACTION_HIL_NATIVE_RATE_RESTART = "com.openfly.go.v5.action.HIL_NATIVE_RATE_RESTART"
        const val ACTION_CAMERA_CADENCE_TEST = "com.openfly.go.v5.action.CAMERA_CADENCE_TEST"
        const val RAW_REGRESSION_DURATION_MILLIS = 10_000L
        const val RAW_REGRESSION_POLL_MILLIS = 100L
        // MSDK V5 does not expose a SimulatorState frequency setter. Some aircraft/firmware
        // combinations publish grounded RAW state at roughly 1 Hz, so freshness and continuity
        // are authoritative; this floor only rejects one-shot/stuck callbacks.
        const val RAW_REGRESSION_MIN_SAMPLES = 20
        const val RATE_EXPERIMENT_RESTART_SETTLE_MILLIS = 2_000L
        const val RATE_EXPERIMENT_RAW_SETTLE_MILLIS = 1_500L
        const val MOTION_REGRESSION_MIN_TAKEOFF_METERS = 0.8f
        const val MOTION_REGRESSION_CONTROL_SETTLE_MILLIS = 1_000L
        const val REGRESSION_TAG = "DjiVln"
    }
}
