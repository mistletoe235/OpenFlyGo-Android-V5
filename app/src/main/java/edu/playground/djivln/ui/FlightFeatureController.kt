package edu.playground.djivln.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.ux.map.MapWidget
import dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory
import dji.v5.ux.mapkit.core.maps.DJIMap
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory
import dji.v5.ux.mapkit.core.models.DJICameraPosition
import dji.v5.ux.mapkit.core.models.DJILatLng
import dji.v5.ux.mapkit.core.models.DJILatLngBounds
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygon
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygonOptions
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions
import edu.playground.djivln.adapter.dji.DjiV5CameraPreviewPort
import edu.playground.djivln.adapter.dji.DjiV5CameraDiscovery
import edu.playground.djivln.adapter.dji.DjiV5FlightControlPort
import edu.playground.djivln.R
import edu.playground.djivln.camera.CameraActionRailView
import edu.playground.djivln.camera.CameraCaptureController
import edu.playground.djivln.camera.MediaGalleryOverlay
import edu.playground.djivln.databinding.ViewFlightFeatureBinding
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.camera.VideoPreviewGeometry
import edu.playground.djivln.localization.resolve
import edu.playground.djivln.survey.ActiveRecaptureMissionGroupCatalog
import edu.playground.djivln.survey.SurveyCaptureView
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyEtaSnapshot
import edu.playground.djivln.survey.SurveyEtaPhase
import edu.playground.djivln.survey.SurveyDjiRemainingEstimator
import edu.playground.djivln.survey.TerrainAltitudeLegendView
import edu.playground.djivln.survey.surveyPasses
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FlightFeatureController(
    private val activity: Activity,
    container: FrameLayout,
    private val exportFlightLog: ((Result<String>) -> Unit) -> Unit,
    private val cameraDiscovery: DjiV5CameraDiscovery,
    private val accountIssue: () -> String? = { null },
    private val recordEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val onOpenSurvey: () -> Unit = {},
    private val onExternalIntervention: (reason: String, completion: () -> Unit) -> Unit = { _, completion ->
        completion()
    },
    private val onMapPrivacyAccepted: () -> Unit = {},
    private val initialMapState: Bundle? = null,
    private val onCloseSurveyPip: () -> Unit = {},
    private val onMapFullscreenChanged: (Boolean) -> Unit = {},
) : AutoCloseable, SurveyMapEditor {
    private val binding = ViewFlightFeatureBinding.inflate(LayoutInflater.from(activity), container, false)
    private val previewPort = DjiV5CameraPreviewPort()
    private val flightPort = DjiV5FlightControlPort()
    private lateinit var rail: CameraActionRailView
    private var gallery: MediaGalleryOverlay? = null
    private val activityOverlayHost: FrameLayout = activity.findViewById(android.R.id.content)
    private lateinit var qualification: DeviceQualificationController
    private var latestSnapshot = AircraftSnapshot()
    private var mapFullscreen = false
    private var mapAttachedToSurvey = false
    private var mapAttachGeneration = 0
    private var map: DJIMap? = null
    private var mapWidget: MapWidget? = null
    private var mapInitializationRequested = false
    private var mapLifecycleCreated = false
    private var satelliteMap = false
    private var safetyPanelCollapsed = processSafetyPanelCollapsed
    private var mapCenteredOnLiveLocation = false
    private var mapFramedForSurveyMission = false
    private var lastCenteredLocationSource: MapLocationSource? = null
    private var phoneLocation: edu.playground.djivln.domain.telemetry.GeoPoint? = null
    private var phoneLocationUpdatesStarted = false
    private val hasRestorableMapCameraState = initialMapState?.getBoolean(MAP_CAMERA_STATE_SAVED, false) == true
    private var pendingSurveyMission: SurveyMission? = null
    private var surveyEtaSnapshot: SurveyEtaSnapshot? = null
    private var activeCameraIndex = cameraDiscovery.current().index
    private var lastCameraRebindElapsedMillis = 0L
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewStreamSize: VideoPreviewGeometry.Size? = null
    private var lastPreviewFrameElapsedMillis = 0L
    private var videoPowerSaving = false
    private var cameraCadenceTestActive = false
    private var cameraCadenceTestGeneration = 0
    private var cameraCadenceUseNativeInterval = false
    private var cameraCadencePeriodsMillis = CAMERA_CADENCE_DEFAULT_PERIODS_MILLIS
    private var cameraCadenceShotsPerStage = 0
    private var cameraCadenceStageIndex = 0
    private var cameraCadenceShotIndex = 0
    private var cameraCadenceOk = 0
    private var cameraCadenceFail = 0
    private var cameraCadenceTimeout = 0
    private var cameraCadenceCapturedCount = 0
    private var cameraCadenceLatencyMillis = 0L
    private var cameraCadenceFirstRequestMillis = 0L
    private var cameraCadenceLastRequestMillis = 0L
    private var cameraCadenceNextRequestMillis = 0L
    private var cameraCadenceShotInFlight = false
    private var cameraCadenceLastReportedCount: Int? = null
    private var cameraCadenceInitialAvailablePhotoCount: Int? = null
    private var cameraCadenceLastAvailablePhotoCount: Int? = null
    private var cameraCadenceStageStartedMillis = 0L
    private var cameraCadenceTimeoutRunnable: Runnable? = null
    private var photoFeedbackCameraIndex = ComponentIndexType.UNKNOWN
    private var lastShootingPhoto = false
    private var lastStoringPhoto = false
    private var lastCaptureShootCount: Int? = null
    private var photoFeedbackCycleArmed = true
    private var lastCameraCaptureDiagnosticSignature: String? = null
    private var lastPhotoFeedbackElapsedMillis = 0L
    private val hidePhotoFeedbackRunnable = Runnable {
        binding.photoCaptureFeedback.animate().cancel()
        binding.photoCaptureFeedback.visibility = View.GONE
        binding.photoCaptureFeedback.alpha = 0f
    }
    private val cameraCadenceTickRunnable = Runnable { runCameraCadenceTestTick() }
    private val surveyLines = mutableListOf<DJIPolyline>()
    private val surveyPolygons = mutableListOf<DJIPolygon>()
    private val surveyCaptureMarkers = mutableListOf<DJIMarker>()
    private val surveyVertexMarkers = mutableListOf<DJIMarker>()
    private var editingPolygon: DJIPolygon? = null
    private var simulatorOriginMarker: DJIMarker? = null
    private var replayMarker: DJIMarker? = null
    private var executionRoute: DJIPolyline? = null
    private var executionActiveRouteHalo: DJIPolyline? = null
    private var executionActiveRoute: DJIPolyline? = null
    private var executionTargetMarker: DJIMarker? = null
    private var executionRecoveryMarker: DJIMarker? = null
    private var remoteControllerDirectionMarker: DJIMarker? = null
    private var remoteControllerDirectionVisible = false
    private var lastRemoteControllerMarkerLocation: edu.playground.djivln.domain.telemetry.GeoPoint? = null
    private var lastRemoteControllerMarkerHeadingDegrees: Double? = null
    private var lastRemoteControllerMarkerUpdateNanos = 0L
    private var pendingExecutionOverlay: SurveyExecutionOverlay? = null
    private var focusedSurveyRegionId: String? = null
    private var mapProbeAttempts = 0
    private var onSurveyMapTap: ((edu.playground.djivln.survey.GeoPoint) -> Unit)? = null
    private var onSurveyVertexTap: ((Int) -> Unit)? = null
    private val locationManager by lazy {
        activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private var phoneHeading: PhoneHeadingSource.Heading? = null
    private val phoneHeadingSourceDelegate = lazy {
        PhoneHeadingSource(
            context = activity,
            displayRotation = {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.rotation
            },
            onHeadingChanged = { heading ->
                activity.runOnUiThread {
                    phoneHeading = heading
                    renderRemoteControllerDirection()
                }
            },
        )
    }
    private val phoneHeadingSource by phoneHeadingSourceDelegate
    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = updatePhoneLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    private val cameraController = CameraCaptureController(activity) { snapshot ->
        activity.runOnUiThread {
            if (::rail.isInitialized) rail.render(snapshot)
            renderPhotoCaptureFeedback(snapshot)
        }
    }
    private val mapReadyProbe = object : Runnable {
        override fun run() {
            val widget = mapWidget ?: return
            val readyMap = widget.map
            if (readyMap != null) {
                configureMap(readyMap)
                return
            }
            mapProbeAttempts += 1
            if (mapProbeAttempts < MAP_READY_MAX_ATTEMPTS) {
                widget.postDelayed(this, MAP_READY_RETRY_MILLIS)
            } else {
                binding.mapLocationStatus.text = activity.getString(R.string.map_controller_timeout)
                binding.mapLocationStatus.visibility = android.view.View.VISIBLE
            }
        }
    }

    init {
        container.addView(binding.root)
        previewPort.setPowerSaving(videoPowerSaving)
        previewPort.startAvailabilityUpdates {
            activity.runOnUiThread {
                if (previewPort.needsRebind(activeCameraIndex)) bindPreview()
            }
        }
        binding.hsiWidgetStub.inflate()
        requestMapInitialization()
        mapWidget?.postDelayed({
            if (!hasRestorableMapCameraState && !mapCenteredOnLiveLocation &&
                pendingSurveyMission == null && bestMapLocation() == null
            ) {
                moveMapTo(DEFAULT_LATITUDE, DEFAULT_LONGITUDE, 16f)
            }
        }, DEFAULT_MAP_CAMERA_DELAY_MS)
        binding.mapToggle.setOnClickListener {
            if (!mapLifecycleCreated) requestMapInitialization()
            else setMapFullscreen(!mapFullscreen)
        }
        binding.safetyMinimize.setOnClickListener {
            safetyPanelCollapsed = !safetyPanelCollapsed
            processSafetyPanelCollapsed = safetyPanelCollapsed
            applySafetyPanelCollapsedState()
        }
        applySafetyPanelCollapsedState()
        binding.mapLayer.setOnClickListener {
            satelliteMap = !satelliteMap
            map?.setMapType(if (satelliteMap) DJIMap.MapType.SATELLITE else DJIMap.MapType.NORMAL)
            binding.mapLayer.text = activity.getString(
                if (satelliteMap) R.string.map_street_compact else R.string.map_satellite_compact
            )
        }
        binding.surveyOpen.setOnClickListener { onOpenSurvey() }
        flightPort.start { state ->
            activity.runOnUiThread {
                binding.safetyStatus.text = activity.getString(R.string.safety_owner_status,
                    state.owner, state.lastChangeReason ?: activity.getString(R.string.state_standby))
            }
        }
        rail = CameraActionRailView(
            context = activity,
            controller = cameraController,
            openGallery = { ensureGallery().show() },
            cycleLens = ::selectNextLens,
        )
        binding.cameraRailHost.addView(rail)
        cameraController.bind(activeCameraIndex)
        cameraDiscovery.current().let { camera ->
            rail.renderLens(camera.streamSource?.name, camera.availableStreamSources.size)
        }
        qualification = DeviceQualificationController(
            activity = activity,
            aircraftSnapshot = { latestSnapshot },
            cameraSnapshot = cameraController::currentSnapshot,
        )
        binding.deviceQualification.setOnClickListener { qualification.show() }
        binding.takeoff.setOnClickListener { binding.safetyStatus.text = activity.getString(R.string.hold_to_takeoff_safety) }
        binding.takeoff.setOnLongClickListener {
            confirmFlightAction(
                activity.getString(R.string.confirm_takeoff_title),
                activity.getString(R.string.confirm_takeoff_message),
                activity.getString(R.string.action_takeoff),
                requiresAccount = !latestSnapshot.simulatorActive,
            ) {
                flightPort.takeoff(it)
            }
            true
        }
        binding.returnHome.setOnClickListener {
            confirmFlightAction(activity.getString(R.string.confirm_rth_title), activity.getString(R.string.confirm_rth_message), activity.getString(R.string.action_return_home)) {
                flightPort.returnHome(it)
            }
        }
        binding.cancelReturnHome.setOnClickListener { flightAction(activity.getString(R.string.action_cancel_return_home)) { flightPort.cancelReturnHome(it) } }
        binding.land.setOnClickListener {
            confirmFlightAction(activity.getString(R.string.confirm_landing_title), activity.getString(R.string.confirm_landing_message), activity.getString(R.string.action_land)) {
                flightPort.land(it)
            }
        }
        binding.cancelLanding.setOnClickListener { flightAction(activity.getString(R.string.action_cancel_landing)) { flightPort.cancelLanding(it) } }
        binding.confirmLanding.setOnClickListener {
            confirmFlightAction(activity.getString(R.string.confirm_continue_landing_title), activity.getString(R.string.confirm_continue_landing_message), activity.getString(R.string.action_confirm_landing)) {
                flightPort.confirmLanding(it)
            }
        }
        binding.exportFlightLog.setOnClickListener {
            exportFlightLog { result -> activity.runOnUiThread {
                binding.telemetry.text = result.fold(
                    { activity.getString(R.string.flight_log_saved, it) },
                    { activity.getString(R.string.flight_log_export_failed, it.message) },
                )
            } }
        }
        binding.cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                previewSurface = Surface(texture)
                previewWidth = width
                previewHeight = height
                bindPreview()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                previewWidth = width
                previewHeight = height
                val resized = previewSurface?.let { surface ->
                    runCatching { previewPort.resizeSurface(surface, width, height) }.getOrDefault(false)
                } == true
                if (!resized) bindPreview()
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                previewPort.unbind()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (!previewPort.hasReceivedStreamData()) return
                updatePreviewAspectIfNeeded()
                val now = SystemClock.elapsedRealtime()
                lastPreviewFrameElapsedMillis = now
                binding.previewStatus.visibility = View.GONE
                if (mapAttachedToSurvey || mapFullscreen) updateLiveCameraPipAvailability(now)
            }
        }
    }

    private fun requestMapInitialization() {
        if (mapInitializationRequested) return
        mapInitializationRequested = true
        ensureMapWidget()
        startPhoneLocation()
        phoneHeadingSource.start()
        requestBaiduMapConsentAndInit()
    }

    private fun ensureGallery(): MediaGalleryOverlay {
        return gallery ?: MediaGalleryOverlay(
            activity = activity,
            cameraIndex = { activeCameraIndex },
            availableStorage = { cameraController.currentSnapshot().supportedStorage.toSet() },
            preferredStorage = { cameraController.currentSnapshot().storageLocation },
            canOpen = {
                cameraController.currentSnapshot().let { snapshot ->
                    when {
                        !snapshot.connected -> activity.getString(R.string.camera_not_connected)
                        snapshot.recording -> activity.getString(R.string.stop_recording_first)
                        latestSnapshot.isFlying -> activity.getString(R.string.media_mode_blocked_flying)
                        else -> null
                    }
                }
            },
            onClosed = {},
            log = { message -> binding.telemetry.text = "${binding.telemetry.text}\n$message" },
        ).also { overlay ->
            gallery = overlay
            activityOverlayHost.addView(
                overlay.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun ensureMapWidget(): MapWidget {
        return mapWidget ?: (binding.mapWidgetStub.inflate() as MapWidget).also {
            mapWidget = it
        }
    }

    private fun bindPreview() {
        val surface = previewSurface ?: return
        if (previewWidth <= 0 || previewHeight <= 0) return
        runCatching { previewPort.bind(activeCameraIndex, surface, previewWidth, previewHeight) }
            .onFailure { showPreviewStatus(activity.getString(R.string.dji_video_wait_failed, it.message.orEmpty())) }
    }

    private fun showPreviewStatus(message: String) {
        binding.previewStatus.text = message
        binding.previewStatus.visibility = View.VISIBLE
    }

    private fun renderVideoPowerSaving() {
        binding.videoPowerSaving.text = activity.getString(
            if (videoPowerSaving) R.string.video_power_saving_on else R.string.video_power_saving_off,
        )
        binding.videoPowerSaving.contentDescription = if (videoPowerSaving) {
            activity.getString(R.string.video_power_saving_on_description)
        } else {
            activity.getString(R.string.video_power_saving_off_description)
        }
    }

    private fun requestBaiduMapConsentAndInit() {
        val preferences = activity.getSharedPreferences(MAP_PRIVACY_PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)) {
            initializeBaiduMap()
            onMapPrivacyAccepted()
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.baidu_map_privacy_title)
            .setMessage(R.string.baidu_map_privacy_message)
            .setPositiveButton(R.string.consent_enable, null)
            .setNegativeButton(R.string.exit_app, null)
            .setNeutralButton(R.string.view_policy, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                preferences.edit().putBoolean(MAP_PRIVACY_ACCEPTED, true).apply()
                dialog.dismiss()
                initializeBaiduMap()
                onMapPrivacyAccepted()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                activity.finish()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BAIDU_MAP_PRIVACY_URL)))
            }
        }
        dialog.show()
    }

    private fun initializeBaiduMap() {
        val widget = ensureMapWidget()
        widget.post {
            runCatching {
                widget.initBaiduMap(::configureMap)
                widget.onCreate(initialMapState)
                mapLifecycleCreated = true
                widget.postDelayed(mapReadyProbe, MAP_READY_INITIAL_DELAY_MILLIS)
            }.onFailure {
                binding.mapToggle.text = activity.getString(R.string.map_unavailable_compact)
                binding.mapLocationStatus.text = activity.getString(R.string.map_init_failed,
                    it.message ?: it.javaClass.simpleName)
                binding.mapLocationStatus.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun configureMap(readyMap: DJIMap) {
        if (map === readyMap) return
        val widget = mapWidget ?: return
        map = readyMap
        Log.i(TAG, "map control attached camera=${readyMap.cameraPosition}")
        widget.removeCallbacks(mapReadyProbe)
        widget.setMapCenterLock(MapWidget.MapCenterLock.NONE)
        readyMap.uiSettings.setZoomControlsEnabled(false)
        readyMap.uiSettings.setCompassEnabled(false)
        readyMap.uiSettings.setTiltGesturesEnabled(true)
        readyMap.setOnMapClickListener { point ->
            onSurveyMapTap?.invoke(
                edu.playground.djivln.survey.GeoPoint(point.latitude, point.longitude),
            )
        }
        widget.setOnMarkerClickListener { marker ->
            val index = surveyVertexMarkers.indexOf(marker)
            if (index >= 0) {
                onSurveyVertexTap?.invoke(index)
                true
            } else {
                // A capture/target/aircraft marker is not a planning-area tap.
                // Forwarding it to onSurveyMapTap used to append a new ROI vertex
                // and invalidate an imported mission when the pilot merely tried
                // to inspect a marker. Consume the event explicitly; returning
                // false lets some providers dispatch a second map-click event.
                marker.showInfoWindow()
                true
            }
        }
        val currentMission = pendingSurveyMission
        currentMission?.let(::renderSurveyMission)
        renderExecutionOverlay(pendingExecutionOverlay)
        if (currentMission == null) {
            centerMapOnLiveLocation(force = true)
        }
        renderRemoteControllerDirection()
        updateMapLocationStatus()
    }

    fun render(snapshot: AircraftSnapshot, diagnostics: String) {
        val aircraftJustConnected = snapshot.connected && !latestSnapshot.connected
        latestSnapshot = snapshot
        if (aircraftJustConnected) bindPreview()
        refreshActiveCamera()
        binding.telemetry.text = buildString {
            appendLine("ALT ${snapshot.relativeAltitudeMeters?.let { "%.1f m".format(it) } ?: "-"}  AGL ${snapshot.altitudeAboveGroundMeters?.let { "%.1f".format(it) } ?: "-"}")
            appendLine("SPD ${snapshot.velocity?.let { "%.1f / %.1f / %.1f".format(it.north, it.east, it.up) } ?: "-"}")
            val headingNowNanos = SystemClock.elapsedRealtimeNanos()
            val displayDeviceHeading = phoneHeading.takeIf {
                PhoneHeadingSource.isDisplayable(it, headingNowNanos)
            }
            val deviceHeadingText = displayDeviceHeading?.let {
                "%.0f°%s".format(it.degrees, if (PhoneHeadingSource.isUsable(it, headingNowNanos)) "" else "~")
            } ?: "-"
            appendLine("HDG ${snapshot.headingDegrees?.let { "%.0f°".format(it) } ?: "-"}  DEV $deviceHeadingText  GIMBAL ${snapshot.gimbalPitchDegrees?.let { "%.0f°".format(it) } ?: "-"}")
            append(activity.getString(R.string.telemetry_battery_summary, snapshot.aircraftBatteryPercent ?: "-", snapshot.remoteControllerBatteryPercent ?: "-"))
        }
        binding.safetyStatus.text = when {
            !snapshot.connected -> activity.getString(R.string.safety_disconnected)
            snapshot.simulatorActive -> activity.getString(R.string.simulator_online_mode, snapshot.flightMode ?: activity.getString(R.string.state_standby))
            !snapshot.isFlying && accountIssue() != null -> activity.getString(R.string.real_flight_locked_reason, requireNotNull(accountIssue()))
            else -> activity.getString(R.string.aircraft_online_mode, snapshot.flightMode ?: activity.getString(R.string.flight_state_ground_standby))
        }
        if ((mapAttachedToSurvey || mapFullscreen) &&
            SystemClock.elapsedRealtime() - lastPreviewFrameElapsedMillis > CAMERA_PIP_MAX_AGE_MILLIS
        ) {
            updateLiveCameraPipAvailability()
        }
        centerMapOnLiveLocationIfNeeded()
        renderRemoteControllerDirection()
        renderHomeDirection(snapshot)
        updateMapLocationStatus()
        binding.confirmLanding.visibility = if (snapshot.landingConfirmationNeeded) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val normalizedMode = snapshot.flightMode.orEmpty().uppercase()
        val goingHome = normalizedMode.contains("GO_HOME") || normalizedMode.contains("GOHOME") ||
            normalizedMode.contains("GO HOME")
        val landing = normalizedMode.contains("LAND")
        val stableFlightActionState = !goingHome && !landing
        val accountAllowsTakeoff = snapshot.simulatorActive || accountIssue() == null
        setControlAvailability(
            binding.takeoff,
            snapshot.connected && !snapshot.isFlying && stableFlightActionState && accountAllowsTakeoff,
        )
        setControlAvailability(binding.returnHome, snapshot.connected && snapshot.isFlying && stableFlightActionState)
        setControlAvailability(binding.cancelReturnHome, snapshot.connected && goingHome)
        setControlAvailability(binding.land, snapshot.connected && snapshot.isFlying && !landing)
        setControlAvailability(binding.cancelLanding, snapshot.connected && landing)
        setControlAvailability(binding.confirmLanding, snapshot.connected && snapshot.landingConfirmationNeeded)
        if (cameraController.currentSnapshot().cameraIndex == ComponentIndexType.UNKNOWN && snapshot.connected) {
            cameraController.bind(activeCameraIndex, force = true)
        }
    }

    fun onResume() {
        if (mapLifecycleCreated) mapWidget?.let { runCatching { it.onResume() } }
        if (mapInitializationRequested) startPhoneLocation()
        phoneHeadingSource.start()
    }

    fun onPause() {
        if (phoneHeadingSourceDelegate.isInitialized()) phoneHeadingSource.stop()
        phoneHeading = null
        renderRemoteControllerDirection()
        stopPhoneLocation()
        if (mapLifecycleCreated) mapWidget?.let { runCatching { it.onPause() } }
    }

    fun onSaveInstanceState(outState: Bundle) {
        if (mapLifecycleCreated) mapWidget?.let { runCatching { it.onSaveInstanceState(outState) } }
        if (map != null) outState.putBoolean(MAP_CAMERA_STATE_SAVED, true)
    }

    fun onLowMemory() = if (mapLifecycleCreated) {
        mapWidget?.let { runCatching { it.onLowMemory() }.getOrNull() }
    } else null

    fun refreshPhoneLocationPermission() {
        if (mapInitializationRequested) startPhoneLocation()
    }

    fun showQualification() {
        if (::qualification.isInitialized) qualification.show()
    }

    fun setSurveyPlanningMode(enabled: Boolean) {
        if (!enabled) {
            endEditing()
            restoreMapToCockpit()
        }
    }

    override fun beginEditing(
        onMapTap: (edu.playground.djivln.survey.GeoPoint) -> Unit,
        onVertexTap: (Int) -> Unit,
    ) {
        onSurveyMapTap = onMapTap
        onSurveyVertexTap = onVertexTap
    }

    override fun renderVertices(roi: List<edu.playground.djivln.survey.GeoPoint>, selectedIndex: Int?) {
        surveyVertexMarkers.forEach { runCatching { it.remove() } }
        surveyVertexMarkers.clear()
        editingPolygon?.remove()
        editingPolygon = null
        if (roi.size >= 3) {
            editingPolygon = map?.addPolygon(
                DJIPolygonOptions()
                    .addAll(roi.map { DJILatLng(it.latitude, it.longitude) })
                    .strokeColor(0xFFE94B76.toInt())
                    .strokeWidth(4f)
                    .fillColor(0x1FE94B76)
                    .zIndex(18f),
            )
        }
        roi.forEachIndexed { index, point ->
            map?.addMarker(
                DJIMarkerOptions()
                    .position(DJILatLng(point.latitude, point.longitude))
                    .title(activity.getString(R.string.boundary_point_number, index + 1))
                    .icon(circleMarkerIcon(if (index == selectedIndex) 0xFFFFC107.toInt() else 0xFFE94B76.toInt()))
                    .anchor(0.5f, 1f)
                    .zIndex(30),
            )?.let(surveyVertexMarkers::add)
        }
    }

    override fun renderSimulatorOrigin(point: edu.playground.djivln.survey.GeoPoint?) {
        simulatorOriginMarker?.remove()
        simulatorOriginMarker = point?.let {
            map?.addMarker(
                DJIMarkerOptions()
                    .position(DJILatLng(it.latitude, it.longitude))
                    .title(activity.getString(R.string.simulator_start_point))
                    .icon(circleMarkerIcon(0xFF18A7E0.toInt()))
                    .anchor(0.5f, 0.5f)
                    .zIndex(46),
            )
        }
    }

    override fun renderReplayPoint(
        point: edu.playground.djivln.survey.GeoPoint?,
        headingDegrees: Double,
        captureActive: Boolean,
    ) {
        if (point == null) {
            replayMarker?.remove()
            replayMarker = null
            return
        }
        val position = DJILatLng(point.latitude, point.longitude)
        val marker = replayMarker ?: map?.addMarker(
            DJIMarkerOptions()
                .position(position)
                .title(activity.getString(R.string.route_preview))
                .icon(arrowMarkerIcon(if (captureActive) 0xFF00BFA5.toInt() else 0xFF1976D2.toInt()))
                .anchor(0.5f, 0.5f)
                .zIndex(40),
        )?.also { replayMarker = it }
        marker?.setPosition(position)
        marker?.setRotation(headingDegrees.toFloat())
        marker?.setIcon(arrowMarkerIcon(if (captureActive) 0xFF00BFA5.toInt() else 0xFF1976D2.toInt()))
        marker?.isVisible = true
    }

    override fun renderExecutionOverlay(overlay: SurveyExecutionOverlay?) {
        pendingExecutionOverlay = overlay
        if (focusedSurveyRegionId != overlay?.activeRegionId) {
            focusedSurveyRegionId = overlay?.activeRegionId
            pendingSurveyMission?.takeIf { it.activeMapping != null }
                ?.let { renderSurveyMission(it, frameRoute = false) }
        }
        executionRoute?.remove()
        executionRoute = null
        executionActiveRouteHalo?.remove()
        executionActiveRouteHalo = null
        executionActiveRoute?.remove()
        executionActiveRoute = null
        executionTargetMarker?.remove()
        executionTargetMarker = null
        executionRecoveryMarker?.remove()
        executionRecoveryMarker = null
        val activeMap = map ?: return
        if (overlay == null) return

        if (overlay.activeRoutePoints.size >= 2) {
            val points = overlay.activeRoutePoints.map { point ->
                DJILatLng(point.latitude, point.longitude)
            }
            executionActiveRouteHalo = activeMap.addPolyline(
                DJIPolylineOptions()
                    .addAll(points)
                    .color(0xFFFFFFFF.toInt())
                    .width(15f)
                    .zIndex(38f),
            )
            executionActiveRoute = activeMap.addPolyline(
                DJIPolylineOptions()
                    .addAll(points)
                    .color(if (overlay.paused) 0xFFFFA726.toInt() else 0xFF00B8D4.toInt())
                    .width(10f)
                    .zIndex(39f),
            )
        }

        overlay.aircraftPoint?.let { aircraft ->
            executionRoute = activeMap.addPolyline(
                DJIPolylineOptions()
                    .addAll(
                        listOf(
                            DJILatLng(aircraft.latitude, aircraft.longitude),
                            DJILatLng(overlay.targetPoint.latitude, overlay.targetPoint.longitude),
                        ),
                    )
                    .color(if (overlay.transit) 0xFF87919C.toInt() else 0xFF00E5FF.toInt())
                    .width(if (overlay.transit) 5f else 8f)
                    .setDashed(overlay.transit)
                    .setDashLength(if (overlay.transit) 9f else 3f)
                    .zIndex(40f),
            )
        }
        executionTargetMarker = activeMap.addMarker(
            DJIMarkerOptions()
                .position(DJILatLng(overlay.targetPoint.latitude, overlay.targetPoint.longitude))
                .title(overlay.title)
                .icon(circleMarkerIcon(when {
                    overlay.paused -> 0xFFF29A2E.toInt()
                    overlay.transit -> 0xFF87919C.toInt()
                    else -> 0xFF00A8C6.toInt()
                }))
                .anchor(0.5f, 0.5f)
                .zIndex(42),
        )
        overlay.recoveryPoint?.let { recovery ->
            executionRecoveryMarker = activeMap.addMarker(
                DJIMarkerOptions()
                    .position(DJILatLng(recovery.latitude, recovery.longitude))
                    .title(overlay.recoveryTitle ?: activity.getString(R.string.actual_pause_position))
                    .icon(circleMarkerIcon(0xFFFF9800.toInt()))
                    .anchor(0.5f, 0.5f)
                    .zIndex(43),
            )
        }
    }

    override fun setThreeDimensional(enabled: Boolean): Boolean {
        val activeMap = map ?: return false
        val current = activeMap.cameraPosition
        activeMap.animateCamera(
            DJICameraUpdateFactory.newCameraPosition(
                DJICameraPosition.Builder(current)
                    .zoom(if (enabled) maxOf(17.3f, current.zoom) else current.zoom)
                    .tilt(if (enabled) 55f else 0f)
                    .build(),
            ),
        )
        return true
    }

    override fun endEditing() {
        onSurveyMapTap = null
        onSurveyVertexTap = null
        surveyVertexMarkers.forEach { runCatching { it.remove() } }
        surveyVertexMarkers.clear()
        editingPolygon?.remove()
        editingPolygon = null
        simulatorOriginMarker?.remove()
        simulatorOriginMarker = null
    }

    fun centerMapOnLiveLocation() {
        mapFramedForSurveyMission = false
        centerMapOnLiveLocation(force = true)
    }

    fun attachMapToSurvey(host: FrameLayout) {
        mapAttachedToSurvey = true
        val attachGeneration = ++mapAttachGeneration
        if (mapFullscreen) {
            mapFullscreen = false
            onMapFullscreenChanged(false)
        }
        (binding.mapPane.parent as? ViewGroup)?.removeView(binding.mapPane)
        host.addView(
            binding.mapPane,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        attachLiveCameraPip(host)
        binding.mapToggle.visibility = android.view.View.GONE
        // V4 keeps the active “航线” affordance visible on the expanded map.
        binding.surveyOpen.visibility = android.view.View.VISIBLE
        (binding.surveyOpen.layoutParams as? FrameLayout.LayoutParams)?.apply {
            // V4 keeps this control centred against the full display even while
            // the planner occupies the right side of the expanded map.
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(4)
            rightMargin = 0
            leftMargin = (activity.resources.displayMetrics.widthPixels - dp(48)) / 2
            binding.surveyOpen.layoutParams = this
        }
        binding.safetyPanel.visibility = android.view.View.GONE
        binding.cameraRailHost.visibility = android.view.View.GONE
        binding.instrumentPanel.visibility = android.view.View.GONE
        mapWidget?.post {
            updateMapLocationStatus()
            mapWidget?.requestLayout()
            mapWidget?.invalidate()
            var cameraFramed = false
            MAP_REATTACH_CAMERA_DELAYS_MS.forEach { delay ->
                mapWidget?.postDelayed({
                    if (attachGeneration != mapAttachGeneration || !mapAttachedToSurvey || cameraFramed) {
                        return@postDelayed
                    }
                    val currentMission = pendingSurveyMission
                    if (currentMission != null) {
                        renderSurveyMission(currentMission, frameRoute = true)
                        cameraFramed = true
                    } else {
                        cameraFramed = centerMapOnLiveLocation(force = true)
                    }
                }, delay)
            }
        }
    }

    private fun restoreMapToCockpit() {
        mapAttachedToSurvey = false
        mapAttachGeneration++
        restoreLiveCameraPreview()
        if (binding.mapPane.parent === binding.root) return
        (binding.mapPane.parent as? ViewGroup)?.removeView(binding.mapPane)
        binding.root.addView(binding.mapPane)
        binding.mapPane.layoutParams = FrameLayout.LayoutParams(dp(170), dp(96), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(8)
            bottomMargin = dp(8)
        }
        binding.mapToggle.visibility = android.view.View.VISIBLE
        binding.surveyOpen.visibility = android.view.View.VISIBLE
        (binding.surveyOpen.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
            rightMargin = 0
            leftMargin = 0
            binding.surveyOpen.layoutParams = this
        }
        binding.safetyPanel.visibility = android.view.View.VISIBLE
        binding.cameraRailHost.visibility = android.view.View.VISIBLE
        binding.instrumentPanel.visibility = android.view.View.VISIBLE
        mapFullscreen = false
        binding.mapToggle.text = activity.getString(R.string.fullscreen_compact)
        onMapFullscreenChanged(false)
        updateMapLocationStatus()
        mapWidget?.post { centerMapOnLiveLocation(force = true) }
    }

    fun renderSurveyMission(mission: SurveyMission) {
        if (pendingSurveyMission?.id != mission.id) focusedSurveyRegionId = null
        renderSurveyMission(mission, frameRoute = true)
    }

    private fun renderSurveyMission(mission: SurveyMission, frameRoute: Boolean) {
        pendingSurveyMission = mission
        if (frameRoute) mapFramedForSurveyMission = true
        val activeMap = map ?: return
        binding.surveyEta.visibility = android.view.View.VISIBLE
        renderSurveyEtaBadge()
        val roiPoints = mission.roi.map { point ->
            DJILatLng(point.latitude, point.longitude)
        }
        val newLines = mutableListOf<DJIPolyline>()
        val newPolygons = mutableListOf<DJIPolygon>()
        val newCaptureMarkers = mutableListOf<DJIMarker>()
        if (roiPoints.size >= 3) activeMap.addPolygon(
            DJIPolygonOptions()
                .addAll(roiPoints)
                .strokeColor(0xFFFFFFFF.toInt())
                .strokeWidth(if (mission.activeMapping == null) 5f else 3f)
                .fillColor(if (mission.activeMapping == null) 0x283478C6 else 0x103478C6)
                .zIndex(19f),
        )?.let(newPolygons::add)
        val terrainPlan = mission.terrainPlan
        val activePassMetadata = mission.activeMapping?.passes?.associateBy { it.passIndex }.orEmpty()
        val activeRegionsById = mission.activeMapping?.regions?.associateBy { it.regionId }.orEmpty()
        ActiveRecaptureMissionGroupCatalog.groups(mission, activity).forEach { group ->
            val target = group.targetWgs84 ?: return@forEach
            val label = "G${group.order}"
            val dimmed = focusedSurveyRegionId != null && focusedSurveyRegionId !in group.regionIds
            activeMap.addMarker(
                DJIMarkerOptions()
                    .position(DJILatLng(target.latitude, target.longitude))
                    .title(activity.getString(R.string.route_group_title, group.order, group.label))
                    .icon(targetMarkerIcon(label, dimmed))
                    .anchor(0.5f, 0.5f)
                    .zIndex(26),
            )?.let(newCaptureMarkers::add)
        }
        // Active-recapture schema 13 missions can legitimately encode each safety
        // transit waypoint as its own one-point pass. Draw the complete ordered
        // mission path once so those units remain connected and visible, while the
        // pass-specific overlays below continue to show capture headings/targets.
        if (mission.activeMapping != null) {
            val missionPath = mission.waypoints.map { waypoint ->
                DJILatLng(waypoint.point.latitude, waypoint.point.longitude)
            }
            if (missionPath.size >= 2) {
                newLines += activeMap.addPolyline(
                    DJIPolylineOptions()
                        .addAll(missionPath)
                        .color(0xDD42A5F5.toInt())
                        .width(4.5f)
                        .zIndex(20f),
                )
            }
        }
        val surveyPasses = mission.surveyPasses()
        if (mission.activeMapping == null) {
            surveyPasses.zipWithNext().forEach { (from, to) ->
                if (from.end.point == to.start.point) return@forEach
                newLines += activeMap.addPolyline(
                    DJIPolylineOptions()
                        .addAll(listOf(
                            DJILatLng(from.end.point.latitude, from.end.point.longitude),
                            DJILatLng(to.start.point.latitude, to.start.point.longitude),
                        ))
                        .color(0xCC87919C.toInt())
                        .width(2.5f)
                        .setDashed(true)
                        .setDashLength(9f)
                        .zIndex(20f),
                )
            }
        }
        var pointCaptureNumber = 0
        surveyPasses.forEach { pass ->
            val route = pass.waypoints
            val captureView = pass.start.captureView
            val regionId = activePassMetadata[pass.start.passIndex]?.regionId
            val regionDimmed = isRegionDimmed(regionId)
            if (pass.isPointCapture) {
                pointCaptureNumber += 1
                activeMap.addMarker(
                    DJIMarkerOptions()
                        .position(DJILatLng(pass.start.point.latitude, pass.start.point.longitude))
                        .title(activity.getString(R.string.precise_recapture_point, pointCaptureNumber))
                        .icon(numberedMarkerIcon(
                            pointCaptureNumber,
                            focusedRouteColor(SurveyRouteStyle.color(captureView), regionDimmed),
                        ))
                        .anchor(0.5f, 0.5f)
                        .zIndex(24),
                )?.let(newCaptureMarkers::add)
                val directionEnd = directionEndpoint(pass.start.point, pass.start.headingDegrees, 10.0)
                newLines += activeMap.addPolyline(
                    DJIPolylineOptions()
                        .addAll(listOf(
                            DJILatLng(pass.start.point.latitude, pass.start.point.longitude),
                            DJILatLng(directionEnd.latitude, directionEnd.longitude),
                        ))
                        .color(focusedRouteColor(SurveyRouteStyle.color(captureView), regionDimmed))
                        .width(if (regionDimmed) 2f else 3f)
                        .zIndex(23f),
                )
                val target = regionId?.let(activeRegionsById::get)?.targetWgs84
                if (target != null) {
                    newLines += activeMap.addPolyline(
                        DJIPolylineOptions()
                            .addAll(listOf(
                                DJILatLng(pass.start.point.latitude, pass.start.point.longitude),
                                DJILatLng(target.latitude, target.longitude),
                            ))
                            .color(focusedRouteColor(0xCCFFD54F.toInt(), regionDimmed))
                            .width(if (regionDimmed) 1.5f else 2.5f)
                            .setDashed(true)
                            .setDashLength(6f)
                            .zIndex(22f),
                    )
                }
                return@forEach
            }
            val bridge = pass.isTransitOnly ||
                SurveyRouteStyle.isPureBridge(activePassMetadata[pass.start.passIndex])
            val routeColor = focusedRouteColor(
                if (bridge) 0xFF87919C.toInt() else SurveyRouteStyle.color(captureView),
                regionDimmed,
            )
            if (terrainPlan == null) {
                val points = route.map { waypoint ->
                    DJILatLng(waypoint.point.latitude, waypoint.point.longitude)
                }
                if (points.size >= 2) {
                    newLines += activeMap.addPolyline(
                        DJIPolylineOptions()
                            .addAll(points)
                            .color(routeColor)
                            .width(if (bridge) 3.5f else SurveyRouteStyle.width(captureView, terrainColored = false))
                            .setDashed(bridge)
                            .setDashLength(if (bridge) 8f else 3f)
                            .zIndex(21f),
                    )
                }
            } else {
                val minimum = terrainPlan.minimumWaypointAltitudeMeters
                val span = (terrainPlan.maximumWaypointAltitudeMeters - minimum).coerceAtLeast(1e-6)
                route.zipWithNext().forEach { (from, to) ->
                    val midpointAltitude = (from.point.altitudeMeters + to.point.altitudeMeters) * 0.5
                    newLines += activeMap.addPolyline(
                        DJIPolylineOptions()
                            .addAll(listOf(
                                DJILatLng(from.point.latitude, from.point.longitude),
                                DJILatLng(to.point.latitude, to.point.longitude),
                            ))
                            .color(if (bridge) routeColor else {
                                TerrainAltitudeLegendView.altitudeColor((midpointAltitude - minimum) / span)
                            })
                            .width(if (bridge) 3.5f else SurveyRouteStyle.width(captureView, terrainColored = true))
                            .setDashed(bridge)
                            .setDashLength(if (bridge) 8f else 3f)
                            .zIndex(21f),
                    )
                }
            }
        }
        val oldLines = surveyLines.toList()
        val oldPolygons = surveyPolygons.toList()
        val oldCaptureMarkers = surveyCaptureMarkers.toList()
        surveyLines.clear()
        surveyLines.addAll(newLines)
        surveyPolygons.clear()
        surveyPolygons.addAll(newPolygons)
        surveyCaptureMarkers.clear()
        surveyCaptureMarkers.addAll(newCaptureMarkers)
        oldLines.forEach { runCatching { it.remove() } }
        oldPolygons.forEach { runCatching { it.remove() } }
        oldCaptureMarkers.forEach { runCatching { it.remove() } }
        if (frameRoute && roiPoints.size >= 3) {
            Log.i(
                TAG,
                "map camera -> survey bounds mission=${mission.id} points=${roiPoints.size}",
            )
            activeMap.animateCamera(
                DJICameraUpdateFactory.newLatLngBounds(
                    DJILatLngBounds.fromLatLngs(roiPoints),
                    18,
                    dp(48),
                ),
            )
        }
    }

    fun clearSurveyMission() {
        pendingSurveyMission = null
        surveyEtaSnapshot = null
        focusedSurveyRegionId = null
        mapFramedForSurveyMission = false
        clearSurveyLines()
    }

    fun startCameraCadenceTest(
        requestedShotsPerStage: Int,
        requestedPeriodsMillis: String?,
        lowResolution: Boolean,
        nativeInterval: Boolean,
        controlOrSurveyActive: Boolean,
    ) {
        if (cameraCadenceTestActive) return logCameraCadence("CAMERA_CADENCE blocked: test already active")
        val camera = cameraController.currentSnapshot()
        when {
            !latestSnapshot.connected || !camera.connected ->
                return logCameraCadence("CAMERA_CADENCE RESULT=BLOCKED reason=camera_not_connected")
            latestSnapshot.isFlying || latestSnapshot.motorsOn ->
                return logCameraCadence("CAMERA_CADENCE RESULT=BLOCKED reason=aircraft_must_be_grounded")
            camera.recording ->
                return logCameraCadence("CAMERA_CADENCE RESULT=BLOCKED reason=video_recording_active")
            controlOrSurveyActive ->
                return logCameraCadence("CAMERA_CADENCE RESULT=BLOCKED reason=control_or_survey_active")
        }
        cameraCadenceShotsPerStage = requestedShotsPerStage.coerceIn(3, 20)
        cameraCadencePeriodsMillis = parseCameraCadencePeriods(requestedPeriodsMillis)
        cameraCadenceUseNativeInterval = nativeInterval
        cameraCadenceTestActive = true
        cameraCadenceTestGeneration += 1
        cameraCadenceStageIndex = 0
        val generation = cameraCadenceTestGeneration
        logCameraCadence(
            "CAMERA_CADENCE START periods_ms=${cameraCadencePeriodsMillis.joinToString(",")}" +
                " shots_per_stage=$cameraCadenceShotsPerStage" +
                " mode=${if (nativeInterval) "native_photo_interval" else "fixed_period_single"}" +
                " grounded=true metric=action_result_and_capture_edge" +
                " low_resolution=$lowResolution before=[${cameraController.cadenceSettingsSummary()}]",
        )
        if (nativeInterval) {
            startCameraCadenceStage(generation)
        } else {
            cameraController.prepareCadencePhotoMode(lowResolution) { result ->
                if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration) return@prepareCadencePhotoMode
                if (result.isFailure) cancelCameraCadenceTest("camera_settings_failed:${result.exceptionOrNull()?.message}")
                else startCameraCadenceStage(generation)
            }
        }
    }

    private fun parseCameraCadencePeriods(value: String?): LongArray {
        val periods = value.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
            .filter { it in 500L..10_000L }
        return periods.takeIf(List<Long>::isNotEmpty)?.toLongArray()
            ?: CAMERA_CADENCE_DEFAULT_PERIODS_MILLIS
    }

    private fun startCameraCadenceStage(generation: Int) {
        if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration) return
        if (cameraCadenceStageIndex >= cameraCadencePeriodsMillis.size) {
            cameraCadenceTestActive = false
            logCameraCadence("CAMERA_CADENCE COMPLETE")
            return
        }
        cameraCadenceShotIndex = 0
        cameraCadenceOk = 0
        cameraCadenceFail = 0
        cameraCadenceTimeout = 0
        cameraCadenceCapturedCount = 0
        cameraCadenceLatencyMillis = 0L
        cameraCadenceFirstRequestMillis = 0L
        cameraCadenceLastRequestMillis = 0L
        cameraCadenceShotInFlight = true
        val initialProgress = cameraController.intervalProgress()
        cameraCadenceLastReportedCount = initialProgress.shootCount
        cameraCadenceInitialAvailablePhotoCount = initialProgress.availablePhotoCount
        cameraCadenceLastAvailablePhotoCount = initialProgress.availablePhotoCount
        cameraCadenceStageStartedMillis = SystemClock.uptimeMillis()
        val period = cameraCadencePeriodsMillis[cameraCadenceStageIndex]
        logCameraCadence(
            "CAMERA_CADENCE STAGE period_ms=$period before=[${cameraController.cadenceSettingsSummary()}]",
        )
        binding.root.removeCallbacks(cameraCadenceTickRunnable)
        if (!cameraCadenceUseNativeInterval) {
            cameraCadenceShotInFlight = false
            cameraCadenceNextRequestMillis = SystemClock.uptimeMillis() + CAMERA_CADENCE_STAGE_WARMUP_MILLIS
            binding.root.postDelayed(cameraCadenceTickRunnable, CAMERA_CADENCE_STAGE_WARMUP_MILLIS)
            return
        }
        val requestedAt = SystemClock.uptimeMillis()
        cameraController.startIntervalCapture(period, cameraCadenceShotsPerStage) { result ->
            if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration) return@startIntervalCapture
            val latency = (SystemClock.uptimeMillis() - requestedAt).coerceAtLeast(0L)
            if (result.isFailure) {
                logCameraCadence(
                    "CAMERA_CADENCE START_INTERVAL period_ms=$period result=FAIL latency_ms=$latency" +
                        " detail=${result.exceptionOrNull()?.message.orEmpty()} state=[${cameraController.cadenceSettingsSummary()}]",
                )
                finishCameraCadenceStage(generation, failed = true)
            } else {
                logCameraCadence(
                    "CAMERA_CADENCE START_INTERVAL period_ms=$period result=OK latency_ms=$latency" +
                        " after=[${cameraController.cadenceSettingsSummary()}]",
                )
                binding.root.post(cameraCadenceTickRunnable)
            }
        }
    }

    private fun runCameraCadenceTestTick() {
        if (!cameraCadenceTestActive) return
        if (!cameraCadenceUseNativeInterval) {
            runDirectSingleCadenceTick()
            return
        }
        if (!cameraCadenceShotInFlight) return
        val generation = cameraCadenceTestGeneration
        if (latestSnapshot.isFlying || latestSnapshot.motorsOn) return cancelCameraCadenceTest("safety_state_changed")
        val now = SystemClock.uptimeMillis()
        val period = cameraCadencePeriodsMillis[cameraCadenceStageIndex]
        val progress = cameraController.intervalProgress()
        val previous = cameraCadenceLastReportedCount
        val current = progress.shootCount
        val previousAvailable = cameraCadenceLastAvailablePhotoCount
        val currentAvailable = progress.availablePhotoCount
        val shootCountDelta = if (current != null && current != previous) {
            when {
                previous == null -> 1
                current > previous -> current - previous
                current > 0 -> current
                else -> 1
            }
        } else 0
        val storageCountDelta = if (previousAvailable != null && currentAvailable != null && currentAvailable < previousAvailable) {
            previousAvailable - currentAvailable
        } else 0
        val delta = maxOf(shootCountDelta, storageCountDelta)
            .coerceAtMost(cameraCadenceShotsPerStage - cameraCadenceShotIndex)
        if (delta > 0) {
            repeat(delta) {
                cameraCadenceShotIndex += 1
                if (cameraCadenceFirstRequestMillis == 0L) cameraCadenceFirstRequestMillis = now
                cameraCadenceLastRequestMillis = now
                logCameraCadence(
                    "CAMERA_CADENCE SHOT period_ms=$period shot=$cameraCadenceShotIndex" +
                        " sdk_count=$current available=$currentAvailable shooting=${progress.shootingPhoto}" +
                        " storing=${progress.storing} not_allowed=${progress.shootNotAllowed}",
                )
            }
        }
        cameraCadenceLastReportedCount = current
        cameraCadenceLastAvailablePhotoCount = currentAvailable
        if (cameraCadenceShotIndex >= cameraCadenceShotsPerStage) {
            finishCameraCadenceStage(generation)
            return
        }
        val timeout = period * cameraCadenceShotsPerStage + CAMERA_CADENCE_TIMEOUT_MILLIS
        if (now - cameraCadenceStageStartedMillis > timeout) {
            cameraCadenceTimeout = 1
            finishCameraCadenceStage(generation, failed = true)
            return
        }
        binding.root.postDelayed(cameraCadenceTickRunnable, CAMERA_CADENCE_POLL_MILLIS)
    }

    private fun runDirectSingleCadenceTick() {
        if (!cameraCadenceTestActive || cameraCadenceShotInFlight) return
        val generation = cameraCadenceTestGeneration
        if (latestSnapshot.isFlying || latestSnapshot.motorsOn) return cancelCameraCadenceTest("safety_state_changed")
        if (cameraCadenceShotIndex >= cameraCadenceShotsPerStage) return finishCameraCadenceStage(generation)
        val now = SystemClock.uptimeMillis()
        if (now < cameraCadenceNextRequestMillis) {
            binding.root.postDelayed(cameraCadenceTickRunnable, CAMERA_CADENCE_POLL_MILLIS)
            return
        }
        val period = cameraCadencePeriodsMillis[cameraCadenceStageIndex]
        if (cameraCadenceFirstRequestMillis == 0L) cameraCadenceFirstRequestMillis = now
        cameraCadenceLastRequestMillis = now
        cameraCadenceShotIndex += 1
        cameraCadenceShotInFlight = true
        cameraCadenceNextRequestMillis += period
        val shot = cameraCadenceShotIndex
        cameraCadenceTimeoutRunnable = Runnable {
            if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration || !cameraCadenceShotInFlight) return@Runnable
            cameraCadenceShotInFlight = false
            cameraCadenceTimeout += 1
            logCameraCadence("CAMERA_CADENCE SHOT period_ms=$period shot=$shot result=TIMEOUT")
            binding.root.post(cameraCadenceTickRunnable)
        }.also { binding.root.postDelayed(it, CAMERA_CADENCE_TIMEOUT_MILLIS) }
        cameraController.takePhotoDirectForCadenceTest { result ->
            if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration || !cameraCadenceShotInFlight) return@takePhotoDirectForCadenceTest
            cameraCadenceTimeoutRunnable?.let(binding.root::removeCallbacks)
            cameraCadenceShotInFlight = false
            val latency = (SystemClock.uptimeMillis() - now).coerceAtLeast(0L)
            cameraCadenceLatencyMillis += latency
            if (result.isSuccess) cameraCadenceOk += 1 else cameraCadenceFail += 1
            logCameraCadence(
                "CAMERA_CADENCE SHOT period_ms=$period shot=$shot" +
                    " result=${if (result.isSuccess) "OK" else "FAIL"} latency_ms=$latency" +
                    " detail=${result.exceptionOrNull()?.message.orEmpty()} state=[${cameraController.cadenceSettingsSummary()}]",
            )
            binding.root.post(cameraCadenceTickRunnable)
        }
    }

    private fun finishCameraCadenceStage(generation: Int, failed: Boolean = false) {
        if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration) return
        val span = if (cameraCadenceShotIndex <= 1) 0L else cameraCadenceLastRequestMillis - cameraCadenceFirstRequestMillis
        val observedPeriod = if (cameraCadenceShotIndex <= 1) 0.0 else span.toDouble() / (cameraCadenceShotIndex - 1)
        cameraCadenceShotInFlight = false
        val completeStage = completeStage@{
            if (!cameraCadenceTestActive || generation != cameraCadenceTestGeneration) return@completeStage
            val total = cameraCadenceOk + cameraCadenceFail + cameraCadenceTimeout
            val averageLatency = if (total == 0) 0.0 else cameraCadenceLatencyMillis.toDouble() / total
            val finalAvailable = cameraController.intervalProgress().availablePhotoCount
            val storageCaptured = if (cameraCadenceInitialAvailablePhotoCount != null && finalAvailable != null) {
                (cameraCadenceInitialAvailablePhotoCount!! - finalAvailable).coerceAtLeast(0)
            } else 0
            val actualCaptured = if (cameraCadenceInitialAvailablePhotoCount != null && finalAvailable != null) {
                storageCaptured
            } else {
                cameraCadenceCapturedCount
            }
            val missingCaptures = (cameraCadenceShotsPerStage - actualCaptured).coerceAtLeast(0)
            logCameraCadence(
                "CAMERA_CADENCE RESULT period_ms=${cameraCadencePeriodsMillis[cameraCadenceStageIndex]}" +
                    " observed_period_ms=${"%.0f".format(observedPeriod)} shots=$cameraCadenceShotIndex" +
                    " ok=$cameraCadenceOk fail=$cameraCadenceFail timeout=$cameraCadenceTimeout" +
                    " captured=$actualCaptured missing=$missingCaptures" +
                    " avg_latency_ms=${"%.0f".format(averageLatency)}" +
                    " result=${if (failed || cameraCadenceFail > 0 || cameraCadenceTimeout > 0 || missingCaptures > 0) "FAIL" else "OK"}" +
                    " final=[${cameraController.cadenceSettingsSummary()}]",
            )
            cameraCadenceStageIndex += 1
            binding.root.postDelayed({ startCameraCadenceStage(generation) }, CAMERA_CADENCE_STAGE_PAUSE_MILLIS)
        }
        if (cameraCadenceUseNativeInterval) cameraController.stopIntervalCapture { completeStage() }
        else completeStage()
    }

    private fun cancelCameraCadenceTest(reason: String) {
        if (!cameraCadenceTestActive) return
        cameraCadenceTestActive = false
        cameraCadenceTestGeneration += 1
        cameraCadenceShotInFlight = false
        binding.root.removeCallbacks(cameraCadenceTickRunnable)
        cameraCadenceTimeoutRunnable?.let(binding.root::removeCallbacks)
        if (cameraCadenceUseNativeInterval) cameraController.stopIntervalCapture()
        logCameraCadence("CAMERA_CADENCE RESULT=ABORTED reason=$reason")
    }

    private fun logCameraCadence(message: String) {
        Log.i(CAMERA_CADENCE_LOG_TAG, message)
        recordEvent("camera_cadence", mapOf("message" to message))
    }

    private fun clearSurveyLines() {
        surveyLines.forEach { runCatching { it.remove() } }
        surveyLines.clear()
        surveyPolygons.forEach { runCatching { it.remove() } }
        surveyPolygons.clear()
        surveyCaptureMarkers.forEach { runCatching { it.remove() } }
        surveyCaptureMarkers.clear()
        binding.surveyEta.visibility = android.view.View.GONE
    }

    fun renderSurveyEta(snapshot: SurveyEtaSnapshot?) {
        surveyEtaSnapshot = snapshot
        renderSurveyEtaBadge()
    }

    private fun renderSurveyEtaBadge() {
        val mission = pendingSurveyMission ?: return
        val state = surveyEtaSnapshot?.takeIf { it.missionId == mission.id }
            ?: SurveyEtaSnapshot(mission.id, SurveyDjiRemainingEstimator.planned(mission), SurveyEtaPhase.PLANNED)
        binding.surveyEta.text = if (state.phase == SurveyEtaPhase.COMPLETED) {
            activity.getString(R.string.survey_eta_completed_short)
        } else activity.getString(when (state.phase) {
            SurveyEtaPhase.PLANNED -> R.string.survey_eta_planned_short
            SurveyEtaPhase.PAUSED -> R.string.survey_eta_paused_short
            else -> R.string.survey_eta_remaining_short
        }, formatDuration(state.estimate.totalSeconds))
    }

    override fun close() {
        cancelCameraCadenceTest("controller_closed")
        binding.root.removeCallbacks(hidePhotoFeedbackRunnable)
        mapWidget?.removeCallbacks(mapReadyProbe)
        if (phoneHeadingSourceDelegate.isInitialized()) phoneHeadingSource.close()
        stopPhoneLocation()
        remoteControllerDirectionMarker?.remove()
        remoteControllerDirectionMarker = null
        replayMarker?.remove()
        replayMarker = null
        previewPort.close()
        previewSurface?.release()
        previewSurface = null
        flightPort.stop()
        if (mapLifecycleCreated) mapWidget?.let { runCatching { it.onDestroy() } }
        mapWidget = null
        cameraController.destroy()
        gallery?.let { overlay ->
            overlay.destroy()
            activityOverlayHost.removeView(overlay.view)
        }
        gallery = null
        if (::qualification.isInitialized) qualification.close()
    }

    private fun renderPhotoCaptureFeedback(snapshot: CameraCaptureController.Snapshot) {
        val diagnosticSignature = listOf(
            snapshot.cameraIndex,
            snapshot.shootingPhoto,
            snapshot.storingPhoto,
            snapshot.captureShootCount,
            snapshot.shootPhotoMode,
            snapshot.photoFileFormat,
            snapshot.photoRatioAndSize,
            snapshot.captureMinimumInterval,
            snapshot.photoProcessTimeSeconds,
        ).joinToString("|")
        if (diagnosticSignature != lastCameraCaptureDiagnosticSignature) {
            lastCameraCaptureDiagnosticSignature = diagnosticSignature
            recordEvent(
                "camera_capture_state",
                mapOf(
                    "camera_index" to snapshot.cameraIndex.name,
                    "shooting" to snapshot.shootingPhoto,
                    "storing" to snapshot.storingPhoto,
                    "capture_count" to snapshot.captureShootCount,
                    "shoot_mode" to snapshot.shootPhotoMode,
                    "file_format" to snapshot.photoFileFormat,
                    "ratio_and_size" to snapshot.photoRatioAndSize,
                    "minimum_interval" to snapshot.captureMinimumInterval,
                    "photo_process_time_s" to snapshot.photoProcessTimeSeconds,
                ),
            )
        }
        if (snapshot.cameraIndex != photoFeedbackCameraIndex) {
            photoFeedbackCameraIndex = snapshot.cameraIndex
            lastShootingPhoto = snapshot.shootingPhoto
            lastStoringPhoto = snapshot.storingPhoto
            lastCaptureShootCount = snapshot.captureShootCount
            photoFeedbackCycleArmed = !snapshot.shootingPhoto && !snapshot.storingPhoto
            return
        }
        val countAdvanced = snapshot.captureShootCount?.let { current ->
            lastCaptureShootCount?.let { previous -> current > previous } == true
        } == true
        val shootingStarted = snapshot.shootingPhoto && !lastShootingPhoto
        val storingStarted = snapshot.storingPhoto && !lastStoringPhoto
        val countAvailable = snapshot.captureShootCount != null || lastCaptureShootCount != null
        if (countAdvanced) {
            if (cameraCadenceTestActive) cameraCadenceCapturedCount += 1
            showPhotoCaptureFeedback()
            recordEvent(
                "camera_photo_captured",
                mapOf(
                    "source" to "capture_count",
                    "capture_count" to snapshot.captureShootCount,
                    "shooting" to snapshot.shootingPhoto,
                    "storing" to snapshot.storingPhoto,
                ),
            )
            photoFeedbackCycleArmed = false
        } else if (!countAvailable && photoFeedbackCycleArmed && (shootingStarted || storingStarted)) {
            if (cameraCadenceTestActive) cameraCadenceCapturedCount += 1
            showPhotoCaptureFeedback()
            recordEvent(
                "camera_photo_captured",
                mapOf(
                    "source" to if (shootingStarted) "shooting_edge" else "storing_edge",
                    "capture_count" to snapshot.captureShootCount,
                    "shooting" to snapshot.shootingPhoto,
                    "storing" to snapshot.storingPhoto,
                ),
            )
            photoFeedbackCycleArmed = false
        }
        if (!snapshot.shootingPhoto && !snapshot.storingPhoto) photoFeedbackCycleArmed = true
        lastShootingPhoto = snapshot.shootingPhoto
        lastStoringPhoto = snapshot.storingPhoto
        snapshot.captureShootCount?.let { lastCaptureShootCount = it }
    }

    private fun showPhotoCaptureFeedback() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhotoFeedbackElapsedMillis < PHOTO_FEEDBACK_DEBOUNCE_MILLIS) return
        lastPhotoFeedbackElapsedMillis = now
        binding.root.removeCallbacks(hidePhotoFeedbackRunnable)
        binding.photoCaptureFeedback.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(PHOTO_FEEDBACK_FADE_IN_MILLIS)
                .withEndAction {
                    animate()
                        .alpha(0f)
                        .setStartDelay(PHOTO_FEEDBACK_HOLD_MILLIS)
                        .setDuration(PHOTO_FEEDBACK_FADE_OUT_MILLIS)
                        .withEndAction(hidePhotoFeedbackRunnable)
                        .start()
                }
                .start()
        }
    }

    private fun flightAction(
        label: String,
        requiresAccount: Boolean = false,
        action: (edu.playground.djivln.domain.flight.FlightControlCompletion) -> Unit,
    ) {
        if (requiresAccount) accountIssue()?.let {
            recordFlightAction(label, "BLOCKED", it)
            binding.safetyStatus.text = activity.getString(R.string.flight_action_blocked_reason, label, it)
            return
        }
        if (!latestSnapshot.connected) {
            recordFlightAction(label, "BLOCKED", activity.getString(R.string.aircraft_disconnected))
            binding.safetyStatus.text = activity.getString(R.string.flight_action_blocked_disconnected, label)
            return
        }
        recordFlightAction(label, "REQUESTED")
        binding.safetyStatus.text = activity.getString(R.string.flight_action_requesting, label)
        action { result ->
            recordFlightAction(label, if (result.isSuccess) "ACCEPTED" else "FAILED", result.exceptionOrNull()?.message)
            activity.runOnUiThread {
                binding.safetyStatus.text = result.fold(
                    { activity.getString(R.string.flight_action_accepted, label) },
                    { activity.getString(R.string.flight_action_failed, label, it.message.orEmpty()) },
                )
            }
        }
    }

    private fun recordFlightAction(label: String, result: String, error: String? = null) {
        recordEvent(
            "flight_action",
            mapOf(
                "action" to label,
                "result" to result,
                "error" to error,
                "flight_mode" to latestSnapshot.flightMode,
                "go_home_state" to latestSnapshot.goHomeState,
                "low_battery_rth_state" to latestSnapshot.lowBatteryRthState,
                "battery_pct" to latestSnapshot.aircraftBatteryPercent,
                "simulator" to latestSnapshot.simulatorActive,
            ),
        )
    }

    private fun confirmFlightAction(
        title: String,
        message: String,
        label: String,
        requiresAccount: Boolean = false,
        action: (edu.playground.djivln.domain.flight.FlightControlCompletion) -> Unit,
    ) {
        if (!latestSnapshot.connected) return flightAction(label, requiresAccount, action)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                binding.safetyStatus.text = activity.getString(R.string.pausing_route_release_control)
                var actionStarted = false
                val proceed = {
                    if (!actionStarted) {
                        actionStarted = true
                        flightAction(label, requiresAccount, action)
                    }
                }
                onExternalIntervention(label, proceed)
                binding.root.postDelayed(proceed, EXTERNAL_INTERVENTION_TIMEOUT_MILLIS)
            }
            .show()
    }

    private fun setControlAvailability(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun applySafetyPanelCollapsedState() {
        binding.safetyControls.visibility = if (safetyPanelCollapsed) View.GONE else View.VISIBLE
        binding.safetyMinimize.text = if (safetyPanelCollapsed) "+" else "−"
        binding.safetyMinimize.contentDescription =
            activity.getString(if (safetyPanelCollapsed) R.string.expand_flight_controls else R.string.collapse_flight_controls)
    }

    private fun refreshActiveCamera() {
        val camera = cameraDiscovery.current()
        rail.renderLens(camera.streamSource?.name, camera.availableStreamSources.size)
        if (camera.index == activeCameraIndex) {
            val now = SystemClock.elapsedRealtime()
            if (camera.cameraConnected && !cameraController.currentSnapshot().connected &&
                now - lastCameraRebindElapsedMillis >= CAMERA_REBIND_RETRY_MILLIS
            ) {
                lastCameraRebindElapsedMillis = now
                cameraController.bind(camera.index, force = true)
            }
            return
        }
        activeCameraIndex = camera.index
        lastCameraRebindElapsedMillis = SystemClock.elapsedRealtime()
        cameraController.bind(camera.index, force = true)
        val surface = previewSurface
        if (surface != null && previewWidth > 0 && previewHeight > 0) {
            runCatching { previewPort.bind(camera.index, surface, previewWidth, previewHeight) }
                .onFailure { binding.telemetry.text = activity.getString(R.string.camera_switch_failed, it.message) }
        }
    }

    private fun selectNextLens() {
        cameraController.showMessage(activity.getString(R.string.camera_switching_lens))
        cameraDiscovery.selectNextStreamSource { result ->
            activity.runOnUiThread {
                result.fold(
                    onSuccess = { source ->
                        cameraController.bind(activeCameraIndex, force = true)
                        val surface = previewSurface
                        if (surface != null && previewWidth > 0 && previewHeight > 0) {
                            runCatching { previewPort.bind(activeCameraIndex, surface, previewWidth, previewHeight) }
                        }
                        rail.renderLens(source.name, cameraDiscovery.current().availableStreamSources.size)
                        cameraController.showMessage(
                            activity.getString(R.string.camera_lens_switched, source.name.toCameraSourceLabel()),
                        )
                        binding.root.postDelayed(::refreshActiveCamera, CAMERA_SOURCE_REFRESH_DELAY_MILLIS)
                    },
                    onFailure = { error ->
                        cameraController.showError(
                            error.message ?: activity.getString(R.string.camera_lens_switch_failed),
                        )
                    },
                )
            }
        }
    }

    private fun String.toCameraSourceLabel(): String = when (uppercase()) {
        "WIDE_CAMERA" -> activity.getString(R.string.camera_lens_wide)
        "ZOOM_CAMERA" -> activity.getString(R.string.camera_lens_zoom)
        "INFRARED_CAMERA" -> activity.getString(R.string.camera_lens_thermal)
        else -> this
    }

    private fun setMapFullscreen(fullscreen: Boolean) {
        if (mapAttachedToSurvey || mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        binding.mapPane.layoutParams = if (fullscreen) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            )
        } else {
            FrameLayout.LayoutParams(dp(170), dp(96), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        binding.mapToggle.layoutParams = (binding.mapToggle.layoutParams as FrameLayout.LayoutParams).apply {
            width = dp(if (fullscreen) 72 else 44)
        }
        binding.mapToggle.text = activity.getString(
            if (fullscreen) R.string.return_to_video_compact else R.string.fullscreen_compact
        )
        binding.mapPane.bringToFront()
        if (fullscreen) attachLiveCameraPip(binding.root) else restoreLiveCameraPreview()
        onMapFullscreenChanged(fullscreen)
        updateMapLocationStatus()
        if (!fullscreen) {
            mapWidget?.post { centerMapOnLiveLocation(force = true) }
        }
    }

    fun exitFullscreenMapIfNeeded(): Boolean {
        if (!mapFullscreen || mapAttachedToSurvey) return false
        setMapFullscreen(false)
        return true
    }

    /**
     * Reuse the decoder-backed TextureView for map picture-in-picture. Copying it through
     * TextureView.getBitmap() caps the preview cadence and forces a GPU-to-CPU readback on
     * every refresh. Reparenting can recreate the SurfaceTexture, which is intentional: the
     * existing listener immediately rebinds the DJI decoder to the new surface and dimensions.
     */
    private fun attachLiveCameraPip(host: FrameLayout) {
        val preview = binding.cameraPreview
        val size = cameraPipSize()
        val params = FrameLayout.LayoutParams(size.width, size.height, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(if (host === binding.root) 12 else 16)
            bottomMargin = dp(if (host === binding.root) 12 else 16)
        }
        if (preview.parent !== host) {
            (preview.parent as? ViewGroup)?.removeView(preview)
            host.addView(preview, params)
        } else {
            preview.layoutParams = params
        }
        preview.elevation = dp(20).toFloat()
        preview.contentDescription = activity.getString(R.string.live_camera_return_flight)
        preview.setOnClickListener {
            if (mapAttachedToSurvey) onCloseSurveyPip() else setMapFullscreen(false)
        }
        updateLiveCameraPipAvailability()
    }

    private fun cameraPipSize(): VideoPreviewGeometry.Size = VideoPreviewGeometry.fitInside(
        previewStreamSize ?: VideoPreviewGeometry.Size(16, 9), dp(240), dp(135),
    )

    private fun updatePreviewAspectIfNeeded() {
        // Encoded stream metadata is enough; no RGBA subscription or bitmap readback.
        // A photo-ratio setting is not necessarily the active video/lens stream ratio.
        val size = previewPort.streamSize ?: return
        if (size == previewStreamSize) return
        previewStreamSize = size
        recordEvent("camera_preview_size_changed", mapOf(
            "stream_width" to size.width,
            "stream_height" to size.height,
            "scale_type" to "CENTER_INSIDE",
        ))
        if (mapAttachedToSurvey || mapFullscreen) {
            val params = binding.cameraPreview.layoutParams as? FrameLayout.LayoutParams ?: return
            val fitted = cameraPipSize()
            if (params.width != fitted.width || params.height != fitted.height) {
                params.width = fitted.width
                params.height = fitted.height
                binding.cameraPreview.layoutParams = params
            }
        }
    }

    private fun restoreLiveCameraPreview() {
        val preview = binding.cameraPreview
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START,
        )
        if (preview.parent !== binding.root) {
            (preview.parent as? ViewGroup)?.removeView(preview)
            binding.root.addView(preview, 0, params)
        } else {
            preview.layoutParams = params
        }
        preview.elevation = 0f
        preview.alpha = 1f
        preview.visibility = View.VISIBLE
        preview.isClickable = false
        preview.setOnClickListener(null)
    }

    private fun updateLiveCameraPipAvailability(now: Long = SystemClock.elapsedRealtime()) {
        if (!mapAttachedToSurvey && !mapFullscreen) return
        val hasFreshFrame = lastPreviewFrameElapsedMillis > 0L &&
            now - lastPreviewFrameElapsedMillis <= CAMERA_PIP_MAX_AGE_MILLIS
        binding.cameraPreview.alpha = if (hasFreshFrame) 1f else 0f
        binding.cameraPreview.isClickable = hasFreshFrame
    }

    private fun updateMapLocationStatus() {
        if (bestMapLocation() != null) {
            binding.mapLocationStatus.visibility = View.GONE
            binding.mapLocationStatus.contentDescription = null
            return
        }
        val expanded = mapFullscreen || mapAttachedToSurvey
        val detailed = MapLocationPolicy.unavailableStatus(
            aircraftConnected = latestSnapshot.connected,
            aircraftSatellites = latestSnapshot.gpsSatelliteCount,
            remoteControllerGpsValid = latestSnapshot.remoteControllerGpsValid,
            phoneLocationPermissionGranted = hasPhoneLocationPermission(),
            phoneLocationListening = phoneLocationUpdatesStarted,
            compact = false,
        )
        val detailedText = activity.resolve(detailed)
        binding.mapLocationStatus.text = if (expanded) detailedText else activity.getString(R.string.no_live_location)
        binding.mapLocationStatus.contentDescription = detailedText
        binding.mapLocationStatus.maxWidth = dp(if (expanded) 360 else 150)
        binding.mapLocationStatus.visibility = View.VISIBLE
    }

    private fun hasPhoneLocationPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun centerMapOnLiveLocationIfNeeded() {
        centerMapOnLiveLocation(force = false)
    }

    private fun centerMapOnLiveLocation(force: Boolean): Boolean {
        if (!force && mapAttachedToSurvey && pendingSurveyMission != null && mapFramedForSurveyMission) {
            return true
        }
        val (source, location) = bestMapLocationWithSource() ?: return false
        if (mapCenteredOnLiveLocation && !force && source == lastCenteredLocationSource) return true
        Log.i(
            TAG,
            "map camera -> live source=$source lat=${location.latitude} lon=${location.longitude} force=$force",
        )
        moveMapTo(location.latitude, location.longitude, MAP_LOCATION_ZOOM)
        mapCenteredOnLiveLocation = true
        lastCenteredLocationSource = source
        return true
    }

    private fun bestMapLocation(): edu.playground.djivln.domain.telemetry.GeoPoint? =
        bestMapLocationWithSource()?.second

    private fun bestMapLocationWithSource(): Pair<MapLocationSource, edu.playground.djivln.domain.telemetry.GeoPoint>? =
        latestSnapshot.aircraftLocation?.let { MapLocationSource.AIRCRAFT to it }
            ?: latestSnapshot.remoteControllerLocation?.let { MapLocationSource.REMOTE_CONTROLLER to it }
            ?: phoneLocation?.let { MapLocationSource.PHONE to it }

    private fun moveMapTo(latitude: Double, longitude: Double, zoom: Float) {
        map?.moveCamera(
            DJICameraUpdateFactory.newCameraPosition(
                DJICameraPosition.Builder()
                    .target(DJILatLng(latitude, longitude))
                    .zoom(zoom)
                    .build(),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneLocation() {
        if (phoneLocationUpdatesStarted) return
        if (!hasPhoneLocationPermission()) {
            updateMapLocationStatus()
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter(::isUsablePhoneLocation)
            .maxByOrNull { it.time }
            ?.let(::updatePhoneLocation)
        var registeredAnyProvider = false
        providers.forEach { provider ->
            if (runCatching {
                locationManager.requestLocationUpdates(provider, 1_000L, 1f, phoneLocationListener)
            }.isSuccess) registeredAnyProvider = true
        }
        phoneLocationUpdatesStarted = registeredAnyProvider
        updateMapLocationStatus()
    }

    private fun updatePhoneLocation(location: Location) {
        if (!isUsablePhoneLocation(location)) return
        phoneLocation = edu.playground.djivln.domain.telemetry.GeoPoint(
            location.latitude,
            location.longitude,
            location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
        )
        activity.runOnUiThread {
            centerMapOnLiveLocationIfNeeded()
            renderRemoteControllerDirection()
            updateMapLocationStatus()
        }
    }

    private fun isUsablePhoneLocation(location: Location): Boolean = MapLocationPolicy.isUsable(
        latitude = location.latitude,
        longitude = location.longitude,
        timestampEpochMillis = location.time,
        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
        nowEpochMillis = System.currentTimeMillis(),
    )

    private fun stopPhoneLocation() {
        if (phoneLocationUpdatesStarted) {
            runCatching { locationManager.removeUpdates(phoneLocationListener) }
        }
        phoneLocationUpdatesStarted = false
        phoneLocation = null
        if (lastCenteredLocationSource == MapLocationSource.PHONE) {
            mapCenteredOnLiveLocation = false
            lastCenteredLocationSource = null
        }
        updateMapLocationStatus()
    }

    private fun circleMarkerIcon(color: Int) = DJIBitmapDescriptorFactory.fromBitmap(
        Bitmap.createBitmap(dp(22), dp(22), Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.43f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    style = Paint.Style.FILL
                })
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.31f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                })
            }
        },
    )

    private fun numberedMarkerIcon(number: Int, color: Int) = DJIBitmapDescriptorFactory.fromBitmap(
        Bitmap.createBitmap(dp(20), dp(20), Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.47f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                })
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.39f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                })
                drawText(number.toString(), bitmap.width / 2f, bitmap.height * 0.68f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = dp(if (number < 10) 9 else 7).toFloat()
                    isFakeBoldText = true
                })
            }
        },
    )

    private fun isRegionDimmed(regionId: String?): Boolean =
        focusedSurveyRegionId != null && regionId != focusedSurveyRegionId

    private fun focusedRouteColor(color: Int, dimmed: Boolean): Int {
        if (!dimmed) return color
        val gray = ((Color.red(color) + Color.green(color) + Color.blue(color)) / 3)
        return Color.argb(72, gray, gray, gray)
    }

    private fun targetMarkerIcon(label: String, dimmed: Boolean = false) = DJIBitmapDescriptorFactory.fromBitmap(
        Bitmap.createBitmap(dp(34), dp(34), Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.47f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                })
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.39f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dimmed) 0x784C535B else 0xFFE65100.toInt()
                    style = Paint.Style.FILL
                })
                drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dimmed) 0x789AA0A6 else 0xFFFFD54F.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = dp(2).toFloat()
                })
                drawText(label, bitmap.width / 2f, bitmap.height * 0.73f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = dp(9).toFloat()
                    isFakeBoldText = true
                })
            }
        },
    )

    private fun directionEndpoint(point: edu.playground.djivln.survey.GeoPoint, headingDegrees: Double, meters: Double): edu.playground.djivln.survey.GeoPoint {
        val heading = Math.toRadians(headingDegrees)
        val north = cos(heading) * meters
        val east = sin(heading) * meters
        val latitude = point.latitude + north / 111_132.0
        val longitude = point.longitude + east /
            (111_320.0 * cos(Math.toRadians(point.latitude)).coerceAtLeast(1e-6))
        return point.copy(latitude = latitude, longitude = longitude)
    }

    private fun arrowMarkerIcon(color: Int) = DJIBitmapDescriptorFactory.fromBitmap(
        Bitmap.createBitmap(dp(28), dp(28), Bitmap.Config.ARGB_8888).also { bitmap ->
            val path = android.graphics.Path().apply {
                moveTo(bitmap.width / 2f, bitmap.height * 0.08f)
                lineTo(bitmap.width * 0.83f, bitmap.height * 0.88f)
                lineTo(bitmap.width / 2f, bitmap.height * 0.70f)
                lineTo(bitmap.width * 0.17f, bitmap.height * 0.88f)
                close()
            }
            Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x88000000.toInt())
            })
        },
    )

    private fun renderRemoteControllerDirection() {
        val location = latestSnapshot.remoteControllerLocation ?: phoneLocation
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val heading = phoneHeading.takeIf {
            PhoneHeadingSource.isDisplayable(it, nowNanos)
        }
        if (map == null || location == null || heading == null) {
            if (remoteControllerDirectionVisible) {
                remoteControllerDirectionMarker?.isVisible = false
                remoteControllerDirectionVisible = false
            }
            return
        }
        val existingMarker = remoteControllerDirectionMarker
        if (existingMarker != null && remoteControllerDirectionVisible &&
            nowNanos - lastRemoteControllerMarkerUpdateNanos < REMOTE_MARKER_MIN_UPDATE_NANOS
        ) return
        val position = DJILatLng(location.latitude, location.longitude)
        val marker = existingMarker ?: map?.addMarker(
            DJIMarkerOptions()
                .position(position)
                .title(activity.getString(R.string.device_heading_marker))
                .icon(arrowMarkerIcon(0xFFFFB300.toInt()))
                .anchor(0.5f, 0.5f)
                .zIndex(46),
        )?.also {
            remoteControllerDirectionMarker = it
            lastRemoteControllerMarkerLocation = location
            lastRemoteControllerMarkerHeadingDegrees = heading.degrees
            lastRemoteControllerMarkerUpdateNanos = nowNanos
            remoteControllerDirectionVisible = true
            it.setRotation(heading.degrees.toFloat())
        } ?: return
        if (existingMarker == null) return

        var changed = false
        val previousLocation = lastRemoteControllerMarkerLocation
        if (previousLocation == null || remoteMarkerDistanceMeters(previousLocation, location) >= REMOTE_MARKER_MIN_MOVE_METERS) {
            marker.setPosition(position)
            lastRemoteControllerMarkerLocation = location
            changed = true
        }
        val previousHeading = lastRemoteControllerMarkerHeadingDegrees
        if (previousHeading == null || circularHeadingDelta(previousHeading, heading.degrees) >= REMOTE_MARKER_MIN_HEADING_DEGREES) {
            marker.setRotation(heading.degrees.toFloat())
            lastRemoteControllerMarkerHeadingDegrees = heading.degrees
            changed = true
        }
        if (!remoteControllerDirectionVisible) {
            marker.isVisible = true
            remoteControllerDirectionVisible = true
            changed = true
        }
        if (changed) lastRemoteControllerMarkerUpdateNanos = nowNanos
    }

    private fun remoteMarkerDistanceMeters(
        from: edu.playground.djivln.domain.telemetry.GeoPoint,
        to: edu.playground.djivln.domain.telemetry.GeoPoint,
    ): Double {
        val north = (to.latitude - from.latitude) * 111_132.0
        val east = (to.longitude - from.longitude) * 111_320.0 *
            cos(Math.toRadians((from.latitude + to.latitude) * 0.5))
        return sqrt(north * north + east * east)
    }

    private fun circularHeadingDelta(from: Double, to: Double): Double =
        abs((to - from + 540.0) % 360.0 - 180.0)

    private fun renderHomeDirection(snapshot: AircraftSnapshot) {
        val aircraft = snapshot.aircraftLocation
        val home = snapshot.homeLocation
        if (aircraft == null || home == null) {
            binding.homeDirection.visibility = android.view.View.GONE
            return
        }
        val lat1 = Math.toRadians(aircraft.latitude)
        val lat2 = Math.toRadians(home.latitude)
        val deltaLon = Math.toRadians(home.longitude - aircraft.longitude)
        val bearing = (Math.toDegrees(atan2(
            sin(deltaLon) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon),
        )) + 360.0) % 360.0
        val relative = (bearing - (snapshot.headingDegrees ?: 0.0) + 360.0) % 360.0
        val arrows = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val arrow = arrows[((relative + 22.5) / 45.0).toInt() % arrows.size]
        val north = (home.latitude - aircraft.latitude) * 111_132.0
        val east = (home.longitude - aircraft.longitude) * 111_320.0 * cos(lat1)
        binding.homeDirection.text = "H $arrow ${sqrt(north * north + east * east).toInt()}m"
        binding.homeDirection.visibility = if (snapshot.isFlying) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private companion object {
        const val TAG = "OpenFlyV5Map"
        const val MAP_READY_INITIAL_DELAY_MILLIS = 250L
        const val MAP_READY_RETRY_MILLIS = 100L
        const val MAP_READY_MAX_ATTEMPTS = 60
        const val MAP_LOCATION_ZOOM = 17f
        const val DEFAULT_MAP_CAMERA_DELAY_MS = 500L
        const val CAMERA_PIP_MAX_AGE_MILLIS = 2_000L
        const val CAMERA_SOURCE_REFRESH_DELAY_MILLIS = 400L
        const val CAMERA_REBIND_RETRY_MILLIS = 2_000L
        const val REMOTE_MARKER_MIN_UPDATE_NANOS = 200_000_000L
        const val REMOTE_MARKER_MIN_MOVE_METERS = 0.5
        const val REMOTE_MARKER_MIN_HEADING_DEGREES = 1.0
        const val CAMERA_CADENCE_LOG_TAG = "DjiVln"
        const val CAMERA_CADENCE_TIMEOUT_MILLIS = 8_000L
        const val CAMERA_CADENCE_STAGE_PAUSE_MILLIS = 1_500L
        const val CAMERA_CADENCE_STAGE_WARMUP_MILLIS = 1_000L
        const val CAMERA_CADENCE_POLL_MILLIS = 50L
        const val PHOTO_FEEDBACK_DEBOUNCE_MILLIS = 250L
        const val PHOTO_FEEDBACK_FADE_IN_MILLIS = 70L
        const val PHOTO_FEEDBACK_HOLD_MILLIS = 380L
        const val PHOTO_FEEDBACK_FADE_OUT_MILLIS = 180L
        const val EXTERNAL_INTERVENTION_TIMEOUT_MILLIS = 2_500L
        const val MAP_PRIVACY_PREFS = "openfly_map_privacy"
        const val MAP_PRIVACY_ACCEPTED = "baidu_map_privacy_v1"
        const val VIDEO_PREFS = "openfly_video_runtime"
        const val VIDEO_POWER_SAVING = "video_power_saving_v2"
        const val MAP_CAMERA_STATE_SAVED = "openfly_map_camera_state_saved"
        const val BAIDU_MAP_PRIVACY_URL =
            "https://lbsyun.baidu.com/docs/pcsa?title=compliance/openprivacy"
        val MAP_REATTACH_CAMERA_DELAYS_MS = longArrayOf(80L, 300L, 900L)
        val CAMERA_CADENCE_DEFAULT_PERIODS_MILLIS = longArrayOf(500L)
        const val DEFAULT_LATITUDE = 31.1815535
        const val DEFAULT_LONGITUDE = 121.4736515
        private var processSafetyPanelCollapsed = false
    }

    private enum class MapLocationSource {
        AIRCRAFT,
        REMOTE_CONTROLLER,
        PHONE,
    }

}
