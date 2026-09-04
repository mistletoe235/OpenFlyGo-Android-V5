package edu.playground.djivln.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.FrameLayout
import edu.playground.djivln.BuildConfig
import edu.playground.djivln.databinding.ViewHilFeatureBinding
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.hil.AndroidHilController
import edu.playground.djivln.hil.DjiV5SimulatorPoseSource
import edu.playground.djivln.hil.HilConnectionMode
import edu.playground.djivln.hil.HilProtocol
import edu.playground.djivln.hil.HilRuntimeSession
import edu.playground.djivln.hil.HilSimulatorLifecycleCoordinator
import edu.playground.djivln.hil.SimulatorOriginStore
import edu.playground.djivln.localization.resolve
import edu.playground.djivln.localization.UiText

class HilFeatureController(
    private val activity: Activity,
    container: FrameLayout,
    private val session: HilRuntimeSession,
    private val snapshot: () -> AircraftSnapshot,
    private val simulatorPoseSource: DjiV5SimulatorPoseSource,
    private val simulatorLifecycle: HilSimulatorLifecycleCoordinator,
    private val cameraSourceLabels: () -> List<String>,
    private val selectedCameraSourceIndex: () -> Int,
    private val selectCameraSource: (Int) -> Unit,
) : AutoCloseable, HilRuntimeSession.Listener, HilSimulatorLifecycleCoordinator.Listener {
    private val binding = ViewHilFeatureBinding.inflate(LayoutInflater.from(activity), container, false)
    private val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
    private val simulatorOriginStore = SimulatorOriginStore(activity)
    private var lastAircraftConnected = false
    private var previewBitmap: Bitmap? = null
    @Volatile private var simulatorLifecycleMessage = UiText.resource(edu.playground.djivln.R.string.simulator_not_checked)
    private var lastLoggedMessage: String? = null

    init {
        container.addView(binding.root)
        session.addListener(this)
        simulatorLifecycle.addListener(this)
        binding.hilMode.adapter = ArrayAdapter(
            activity,
            edu.playground.djivln.R.layout.item_cockpit_spinner,
            listOf(activity.getString(edu.playground.djivln.R.string.hil_mode_hotspot), activity.getString(edu.playground.djivln.R.string.hil_mode_lan)),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.hilFrequency.adapter = ArrayAdapter(
            activity,
            edu.playground.djivln.R.layout.item_cockpit_spinner,
            FREQUENCIES.map { "$it Hz" },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        renderFrequencySource()
        val sourceLabels = cameraSourceLabels().ifEmpty {
            listOf(activity.getString(edu.playground.djivln.R.string.camera_source_dji), activity.getString(edu.playground.djivln.R.string.camera_source_ue))
        }
        binding.hilCameraSource.adapter = ArrayAdapter(
            activity,
            edu.playground.djivln.R.layout.item_cockpit_spinner,
            sourceLabels,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.hilCameraSource.setSelection(selectedCameraSourceIndex().coerceIn(sourceLabels.indices), false)
        binding.hilCameraSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectCameraSource(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.hilHost.setText(preferences.getString(KEY_HOST, DEFAULT_HOST))
        binding.hilUdpPort.setText(preferences.getInt(KEY_UDP_PORT, DEFAULT_UDP_PORT).toString())
        binding.hilFramePort.setText(preferences.getInt(KEY_FRAME_PORT, DEFAULT_FRAME_PORT).toString())
        val savedMode = runCatching {
            HilConnectionMode.valueOf(preferences.getString(KEY_MODE, HilConnectionMode.HOTSPOT.name).orEmpty())
        }.getOrDefault(HilConnectionMode.HOTSPOT)
        binding.hilMode.setSelection(if (savedMode == HilConnectionMode.HOTSPOT) 0 else 1, false)
        val savedFrequency = preferences.getInt(KEY_OUTPUT_FREQUENCY, DEFAULT_OUTPUT_FREQUENCY)
        binding.hilFrequency.setSelection(FREQUENCIES.indexOf(savedFrequency).takeIf { it >= 0 } ?: DEFAULT_FREQUENCY_INDEX, false)
        binding.hilFrequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val frequency = FREQUENCIES[position.coerceIn(FREQUENCIES.indices)]
                preferences.edit().putInt(KEY_OUTPUT_FREQUENCY, frequency).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.hilMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = if (position == 0) HilConnectionMode.HOTSPOT else HilConnectionMode.LAN
                renderMode(mode)
                preferences.edit().putString(KEY_MODE, mode.name).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderMode(savedMode)
        binding.hilStart.setOnClickListener { start() }
        binding.hilStop.setOnClickListener {
            simulatorLifecycle.stopPreservingSimulator()
            session.stop()
            renderRunning(false)
            simulatorLifecycleMessage = UiText.resource(edu.playground.djivln.R.string.simulator_state_preserved)
            show(activity.getString(edu.playground.djivln.R.string.hil_stopped_ports_released))
        }
        binding.hilOpenHotspotSettings.setOnClickListener { openHotspotSettings() }
        binding.hilPreviewOnce.setOnClickListener {
            val frame = session.decodeLatestFrame(1_500L)
            if (frame == null) {
                show(activity.getString(edu.playground.djivln.R.string.hil_no_virtual_camera_frame))
            } else {
                val previous = previewBitmap
                previewBitmap = frame
                binding.hilPreview.setImageBitmap(frame)
                if (previous !== frame && previous?.isRecycled == false) previous.recycle()
            }
        }
        val alreadyRunning = session.status()?.running == true
        renderRunning(alreadyRunning)
        if (alreadyRunning) {
            val currentAircraft = snapshot()
            lastAircraftConnected = currentAircraft.connected
            // Reopening this panel must not create a new lifecycle session: start() intentionally
            // clears the user's manual-disable override. The active HIL session already owns the
            // coordinator, so only attach the latest origin/telemetry observation here.
            simulatorLifecycle.observe(origin(currentAircraft), observation(currentAircraft))
        }
    }

    override fun onHilEvent(event: HilProtocol.Event) = show(activity.getString(
        edu.playground.djivln.R.string.hil_event_status,
        event.kind,
        "%.2f".format(event.stopScore),
        event.reason.ifBlank { activity.getString(edu.playground.djivln.R.string.reason_unavailable) },
    ))
    override fun onHilLinkStale() = show(activity.getString(edu.playground.djivln.R.string.hil_heartbeat_safe_wait))
    override fun onHilError(message: String) = show(message)
    override fun onHilStatus(status: AndroidHilController.Status) {
        renderRunning(status.running)
        val simulatorSource = session.simulatorSourceStatus()
        show(buildString {
            appendLine(activity.resolve(status.message))
            appendLine(activity.getString(
                edu.playground.djivln.R.string.hil_link_metrics,
                status.mode,
                status.peerHost ?: activity.getString(edu.playground.djivln.R.string.waiting_for_discovery),
                boolText(status.peerFresh),
            ))
            appendLine(activity.resolve(simulatorLifecycleMessage))
            appendLine(
                activity.getString(
                    edu.playground.djivln.R.string.hil_raw_metrics,
                    nativeSourceRequestHz(),
                    simulatorSource.ageMillis?.let {
                        activity.getString(
                            edu.playground.djivln.R.string.hil_raw_measured_age,
                            simulatorSource.measuredRateHz,
                            it,
                        )
                    } ?: activity.getString(edu.playground.djivln.R.string.waiting_for_sample),
                ),
            )
            appendLine(activity.getString(
                edu.playground.djivln.R.string.hil_pose_metrics,
                status.measuredPoseSendHz,
                status.receivedPacketCount,
                status.roundTripMillis.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "--",
            ))
            appendLine(activity.getString(
                edu.playground.djivln.R.string.hil_tcp_metrics,
                boolText(status.frameListening),
                boolText(status.frameConnected),
                status.framePeerHost ?: activity.getString(edu.playground.djivln.R.string.waiting_for_discovery),
            ))
            append(activity.getString(edu.playground.djivln.R.string.hil_frame_status, status.frame?.let { "${it.width}x${it.height} #${it.frameId}" } ?: activity.getString(edu.playground.djivln.R.string.waiting_for_frame)))
        }, log = false)
    }

    override fun close() {
        session.removeListener(this)
        simulatorLifecycle.removeListener(this)
        binding.hilPreview.setImageDrawable(null)
        previewBitmap?.takeIf { !it.isRecycled }?.recycle()
        previewBitmap = null
    }

    fun useCompactInlineLayout() {
        binding.hilPreviewContainer.visibility = android.view.View.GONE
        binding.root.layoutParams = binding.root.layoutParams.apply {
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.hilSettingsScroll.layoutParams =
            (binding.hilSettingsScroll.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                weight = 1f
                marginStart = 0
            }
        binding.hilSettingsScroll.isNestedScrollingEnabled = false
    }

    private fun boolText(value: Boolean): String =
        activity.getString(if (value) edu.playground.djivln.R.string.state_yes else edu.playground.djivln.R.string.state_no)

    override fun onStateChanged(message: UiText) {
        simulatorLifecycleMessage = message
        show(activity.resolve(message))
    }

    fun onAircraftSnapshot(aircraft: AircraftSnapshot) {
        val newlyConnected = aircraft.connected && !lastAircraftConnected
        lastAircraftConnected = aircraft.connected
        if (newlyConnected) {
            simulatorPoseSource.refreshListener()
        }
        if (session.status()?.running == true) simulatorLifecycle.observe(origin(aircraft), observation(aircraft))
    }

    private fun start() {
        val aircraft = snapshot()
        val hotspot = binding.hilMode.selectedItemPosition == 0
        val udpPort = binding.hilUdpPort.text.toString().toIntOrNull() ?: DEFAULT_UDP_PORT
        val framePort = binding.hilFramePort.text.toString().toIntOrNull() ?: DEFAULT_FRAME_PORT
        val frequency = FREQUENCIES[binding.hilFrequency.selectedItemPosition.coerceIn(FREQUENCIES.indices)]
        val host = binding.hilHost.text.toString().trim()
        if (!hotspot && host.isBlank()) return show(activity.getString(edu.playground.djivln.R.string.hil_lan_ip_required))
        if (udpPort !in 1..65_534) return show(activity.getString(edu.playground.djivln.R.string.hil_udp_port_invalid))
        if (framePort !in 1..65_535) return show(activity.getString(edu.playground.djivln.R.string.hil_tcp_port_invalid))
        preferences.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_UDP_PORT, udpPort)
            .putInt(KEY_FRAME_PORT, framePort)
            .putInt(KEY_OUTPUT_FREQUENCY, frequency)
            .putString(KEY_MODE, if (hotspot) HilConnectionMode.HOTSPOT.name else HilConnectionMode.LAN.name)
            .apply()
        lastAircraftConnected = aircraft.connected
        simulatorLifecycleMessage = UiText.resource(edu.playground.djivln.R.string.simulator_start_checking)
        show(activity.getString(edu.playground.djivln.R.string.hil_starting_simulator))
        runCatching {
            val config = AndroidHilController.Config(
                mode = if (hotspot) HilConnectionMode.HOTSPOT else HilConnectionMode.LAN,
                host = host,
                udpServerPort = udpPort,
                udpLocalPort = udpPort + 1,
                frameTcpPort = framePort,
                poseSendHz = frequency,
                // This metadata describes the native DJI RAW request, not the independently
                // configurable Android -> UE output cap.
                simulatorStateHz = nativeSourceRequestHz(),
            )
            check(session.start(config)) { activity.getString(edu.playground.djivln.R.string.hil_ports_start_failed) }
            renderRunning(true)
            simulatorPoseSource.setOutputFrequencyHz(frequency)
            simulatorPoseSource.start(session::submitSimulatorPose)
            simulatorLifecycle.start(origin(aircraft), observation(aircraft))
        }.onFailure {
            simulatorLifecycle.stopPreservingSimulator()
            session.stop()
            renderRunning(false)
            show(activity.getString(edu.playground.djivln.R.string.hil_start_failed_detail, it.message ?: it.javaClass.simpleName))
        }
    }

    private fun origin(aircraft: AircraftSnapshot): HilSimulatorLifecycleCoordinator.Origin {
        val origin = simulatorOriginStore.load()
        simulatorPoseSource.setFallbackOrigin(origin.latitude, origin.longitude)
        return HilSimulatorLifecycleCoordinator.Origin(origin.latitude, origin.longitude)
    }

    private fun observation(aircraft: AircraftSnapshot): HilSimulatorLifecycleCoordinator.Observation {
        val sample = simulatorPoseSource.latest()
        return HilSimulatorLifecycleCoordinator.Observation(
            connected = aircraft.connected,
            simulatorReportedActive = aircraft.simulatorActive,
            simulatorAirborne = sample?.let { it.motorsOn || it.flying }
                ?: (aircraft.simulatorActive && (aircraft.motorsOn || aircraft.isFlying)),
        )
    }

    private fun renderRunning(running: Boolean) {
        activity.runOnUiThread {
            binding.hilStart.isEnabled = !running
            binding.hilStart.alpha = if (running) 0.45f else 1f
            binding.hilStop.isEnabled = running
            binding.hilStop.alpha = if (running) 1f else 0.45f
            listOf(binding.hilMode, binding.hilHost, binding.hilUdpPort, binding.hilFramePort, binding.hilFrequency)
                .forEach {
                    it.isEnabled = !running
                    it.alpha = if (running) 0.65f else 1f
                }
        }
    }

    private fun renderMode(mode: HilConnectionMode) {
        val hotspot = mode == HilConnectionMode.HOTSPOT
        binding.hilOpenHotspotSettings.visibility = if (hotspot) View.VISIBLE else View.GONE
        binding.hilHostContainer.visibility = if (hotspot) View.GONE else View.VISIBLE
        binding.hilNetworkHint.text = if (hotspot) {
            activity.getString(edu.playground.djivln.R.string.hil_hotspot_instructions)
        } else {
            activity.getString(edu.playground.djivln.R.string.hil_lan_instructions)
        }
    }

    private fun renderFrequencySource() {
        val nativeHz = nativeSourceRequestHz()
        binding.hilNativeFrequency.text = "$nativeHz Hz"
        binding.hilFrequencyHint.text = if (BuildConfig.HIL_NATIVE_RATE_EXPERIMENT_HZ > 0) {
            activity.getString(edu.playground.djivln.R.string.hil_raw_experimental_hint)
        } else {
            activity.getString(edu.playground.djivln.R.string.hil_raw_default_hint)
        }
    }

    private fun nativeSourceRequestHz(): Int =
        BuildConfig.HIL_NATIVE_RATE_EXPERIMENT_HZ.takeIf { it > 0 } ?: DEFAULT_NATIVE_SOURCE_FREQUENCY

    private fun openHotspotSettings() {
        val opened = runCatching { activity.startActivity(Intent("android.settings.TETHER_SETTINGS")) }.isSuccess
        if (!opened) runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            .onFailure { show(activity.getString(edu.playground.djivln.R.string.hil_open_hotspot_settings_failed)) }
    }

    private fun show(message: String, log: Boolean = true) {
        if (log && message != lastLoggedMessage) {
            lastLoggedMessage = message
            Log.i(TAG, message.replace('\n', ' '))
        }
        activity.runOnUiThread { binding.hilStatus.text = message }
    }

    private companion object {
        const val PREFERENCES = "ue-hil"
        const val KEY_HOST = "host"
        const val KEY_UDP_PORT = "udp-port"
        const val KEY_FRAME_PORT = "frame-port"
        // Do not migrate the old ambiguous "simulator-hz" preference: it mixed the native
        // source request with the Android -> UE output cap and defaulted existing installs to 100.
        const val KEY_OUTPUT_FREQUENCY = "pose-output-hz"
        const val KEY_MODE = "connection-mode"
        const val DEFAULT_HOST = "192.168.1.20"
        const val DEFAULT_UDP_PORT = 30_020
        const val DEFAULT_FRAME_PORT = 30_022
        const val DEFAULT_OUTPUT_FREQUENCY = 50
        const val DEFAULT_FREQUENCY_INDEX = 2
        const val DEFAULT_NATIVE_SOURCE_FREQUENCY = 20
        const val TAG = "OpenFlyV5HIL"
        val FREQUENCIES = intArrayOf(10, 25, 50, 100, 150)
    }
}
