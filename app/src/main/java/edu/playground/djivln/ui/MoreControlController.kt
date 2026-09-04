package edu.playground.djivln.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import edu.playground.djivln.R
import edu.playground.djivln.databinding.ViewMoreControlBinding
import edu.playground.djivln.hil.HilRuntimeSession
import edu.playground.djivln.hil.DjiV5SimulatorPoseSource
import edu.playground.djivln.hil.HilSimulatorLifecycleCoordinator
import edu.playground.djivln.localization.AppLanguageController
import edu.playground.djivln.domain.telemetry.AircraftSnapshot

class MoreControlController(
    private val activity: AppCompatActivity,
    container: FrameLayout,
    private val vln: DeferredFeatureController,
    private val onQualification: () -> Unit,
    private val onOpenLog: () -> Unit,
    private val exportDiagnosticLog: ((Result<String>) -> Unit) -> Unit,
    private val accountStatus: () -> String,
    private val accountLoggedIn: () -> Boolean,
    private val loginDjiAccount: () -> Unit,
    private val canChangeLanguage: () -> Boolean,
    hilSession: HilRuntimeSession,
    simulatorPoseSource: DjiV5SimulatorPoseSource,
    simulatorLifecycle: HilSimulatorLifecycleCoordinator,
    private val snapshot: () -> AircraftSnapshot,
    onClose: () -> Unit,
) : AutoCloseable {
    private val binding = ViewMoreControlBinding.inflate(LayoutInflater.from(activity), container, false)
    private val hilController = HilFeatureController(
        activity,
        binding.hilInlineHost,
        hilSession,
        snapshot,
        simulatorPoseSource,
        simulatorLifecycle,
        cameraSourceLabels = vln::cameraSourceLabels,
        selectedCameraSourceIndex = vln::selectedCameraSourceIndex,
        selectCameraSource = vln::selectCameraSource,
    ).also { it.useCompactInlineLayout() }
    private var selectedTransport = Transport.LOCAL
    private var updatingClosureControls = false

    init {
        container.addView(binding.root)
        binding.executedPrefix.adapter = ArrayAdapter(
            activity,
            R.layout.item_cockpit_spinner,
            (1..10).map { activity.getString(R.string.execute_horizon, it) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.executedPrefix.setSelection(vln.selectedExecutedPrefix() - 1)
        binding.ethernetEndpoint.setText(vln.currentEthernetEndpoint())
        binding.cameraSource.adapter = ArrayAdapter(
            activity,
            R.layout.item_cockpit_spinner,
            vln.cameraSourceLabels(),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.cameraSource.setSelection(vln.selectedCameraSourceIndex(), false)
        binding.cameraSource.onItemSelectedListener = SimpleSelection(vln::selectCameraSource)
        binding.tabLink.visibility = View.GONE
        binding.tabFlight.setOnClickListener { select(Tab.FLIGHT) }
        binding.tabExecution.visibility = View.GONE
        binding.tabSystem.setOnClickListener { select(Tab.SYSTEM) }
        binding.appLanguage.setOnClickListener {
            AppLanguageController.showSettingsPicker(
                activity,
                canChangeNow = canChangeLanguage(),
            )
        }
        binding.closeMore.setOnClickListener { onClose() }
        binding.transportLocal.visibility = View.GONE
        binding.transportAoa.setOnClickListener { selectTransport(Transport.AOA) }
        binding.transportEthernet.setOnClickListener { selectTransport(Transport.ETHERNET) }
        binding.ethernetEndpoint.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) vln.setEthernetEndpoint(binding.ethernetEndpoint.text.toString())
        }
        binding.ethernetEndpoint.setOnEditorActionListener { _, _, _ ->
            vln.setEthernetEndpoint(binding.ethernetEndpoint.text.toString())
            false
        }
        binding.importModel.visibility = View.GONE
        binding.downloadModel.visibility = View.GONE
        binding.modelHealth.setOnClickListener { vln.checkHealth() }
        binding.modelLoad.setOnClickListener { vln.loadModel() }
        binding.modelPreflight.setOnClickListener { vln.preflightModel() }
        binding.modelStart.setOnClickListener { vln.startModelSession() }
        binding.modelStop.setOnClickListener { vln.stopAutomatic() }
        binding.modelReset.setOnClickListener { vln.resetRuntime() }
        binding.stopThreshold.setOnClickListener {
            vln.cycleStopThreshold()
            renderStatus()
        }
        binding.deviceQualification.setOnClickListener { onQualification() }
        binding.djiAccountLogin.setOnClickListener { loginDjiAccount() }
        binding.exportDiagnosticLog.setOnClickListener {
            binding.exportDiagnosticLog.isEnabled = false
            binding.diagnosticLogStatus.text = activity.getString(R.string.diagnostic_log_exporting)
            exportDiagnosticLog { result ->
                activity.runOnUiThread {
                    binding.exportDiagnosticLog.isEnabled = true
                    binding.diagnosticLogStatus.text = result.fold(
                        onSuccess = { activity.getString(R.string.diagnostic_log_saved, it) },
                        onFailure = { activity.getString(R.string.diagnostic_log_export_failed, it.message) },
                    )
                }
            }
        }
        binding.gpsClosure.setOnCheckedChangeListener { _, checked ->
            if (updatingClosureControls) return@setOnCheckedChangeListener
            if (!checked) {
                if (!binding.velocityEstimate.isChecked) {
                    updatingClosureControls = true
                    binding.gpsClosure.isChecked = true
                    updatingClosureControls = false
                }
                return@setOnCheckedChangeListener
            }
            updatingClosureControls = true
            if (checked && binding.velocityEstimate.isChecked) binding.velocityEstimate.isChecked = false
            updatingClosureControls = false
            vln.setVelocityEstimateEnabled(!checked)
            renderStatus()
        }
        binding.velocityEstimate.setOnCheckedChangeListener { _, checked ->
            if (updatingClosureControls) return@setOnCheckedChangeListener
            if (!checked) {
                if (!binding.gpsClosure.isChecked) {
                    updatingClosureControls = true
                    binding.velocityEstimate.isChecked = true
                    updatingClosureControls = false
                }
                return@setOnCheckedChangeListener
            }
            updatingClosureControls = true
            if (checked && binding.gpsClosure.isChecked) binding.gpsClosure.isChecked = false
            updatingClosureControls = false
            vln.setVelocityEstimateEnabled(checked)
            renderStatus()
        }
        updatingClosureControls = true
        binding.gpsClosure.isChecked = !vln.velocityEstimateEnabled()
        binding.velocityEstimate.isChecked = vln.velocityEstimateEnabled()
        updatingClosureControls = false
        binding.aoaEnabled.setOnCheckedChangeListener { _, checked ->
            vln.setAoaEnabled(checked)
            if (!checked && selectedTransport == Transport.AOA) selectTransport(Transport.ETHERNET)
        }
        binding.executedPrefix.onItemSelectedListener = SimpleSelection { position -> vln.setExecutedPrefix(position + 1) }
        binding.chunkMode.setOnClickListener {
            vln.setContinuousChunkEnabled(!vln.continuousChunkEnabled())
            renderStatus()
        }
        binding.flyThrough.setOnClickListener {
            vln.setFlyThroughEnabled(!vln.flyThroughEnabled())
            renderStatus()
        }
        binding.manualXyz.setOnClickListener { executeManualXyz() }
        binding.manualXyzStop.setOnClickListener {
            vln.stopManualRelative()
            renderStatus()
        }
        binding.inferOnce.setOnClickListener { vln.inferSingleFrame() }
        binding.previewFrame.setOnClickListener { vln.previewFrame() }
        binding.openLog.setOnClickListener { onOpenLog() }
        renderSimulatorOrigin()
        binding.simulatorOriginApply.setOnClickListener {
            if (vln.updateSimulatorOrigin(
                    binding.simulatorOriginLatitude.text.toString(),
                    binding.simulatorOriginLongitude.text.toString(),
                )
            ) renderSimulatorOrigin()
            renderStatus()
        }
        binding.simulatorOriginAircraft.setOnClickListener {
            if (vln.useAircraftLocationAsSimulatorOrigin()) renderSimulatorOrigin()
            renderStatus()
        }
        binding.simulatorToggle.setOnClickListener {
            if (!vln.isDjiSimulatorActive() &&
                !vln.updateSimulatorOrigin(
                    binding.simulatorOriginLatitude.text.toString(),
                    binding.simulatorOriginLongitude.text.toString(),
                )
            ) return@setOnClickListener
            vln.toggleDjiSimulator()
            renderStatus()
        }
        val initialSpeed = vln.maximumHorizontalSpeed()
        binding.speedLimit.progress = ((initialSpeed - 0.2) * 10.0).toInt().coerceIn(0, binding.speedLimit.max)
        binding.speedLimitLabel.text = activity.getString(R.string.horizontal_limit_value, initialSpeed)
        binding.speedLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.2 + progress / 10.0
                binding.speedLimitLabel.text = activity.getString(R.string.horizontal_limit_value, speed)
                vln.setMaximumHorizontalSpeed(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        select(Tab.FLIGHT)
        selectTransport(
            when (vln.selectedDeferredTransport()) {
                DeferredTransport.LOCAL -> Transport.LOCAL
                DeferredTransport.USB -> Transport.AOA
                DeferredTransport.ETHERNET -> Transport.ETHERNET
            },
        )
        renderStatus()
    }

    private fun renderSimulatorOrigin() {
        val origin = vln.simulatorOrigin()
        binding.simulatorOriginLatitude.setText(origin.latitude.toString())
        binding.simulatorOriginLongitude.setText(origin.longitude.toString())
    }

    fun renderStatus() {
        hilController.onAircraftSnapshot(snapshot())
        binding.linkStatus.text = vln.currentStatus()
        updatingClosureControls = true
        binding.gpsClosure.isChecked = !vln.velocityEstimateEnabled()
        binding.velocityEstimate.isChecked = vln.velocityEstimateEnabled()
        updatingClosureControls = false
        binding.stopThreshold.text = activity.getString(R.string.uavflow_stop_threshold_value, vln.stopThreshold())
        binding.flightState.text = vln.controlSummary()
        binding.chunkMode.text = activity.getString(if (vln.continuousChunkEnabled()) R.string.uavflow_on else R.string.uavflow_off)
        binding.flyThrough.text = activity.getString(if (vln.flyThroughEnabled()) R.string.fly_through_on else R.string.fly_through_off)
        val inferIssue = vln.inferenceIssue()
        val inferEnabled = inferIssue == null
        setAvailability(binding.inferOnce, inferEnabled)
        binding.inferOnce.contentDescription = if (inferEnabled) activity.getString(R.string.infer_once)
        else activity.getString(R.string.infer_unavailable, inferIssue)
        val previewIssue = vln.previewFrameIssue()
        val previewEnabled = previewIssue == null
        setAvailability(binding.previewFrame, previewEnabled)
        binding.previewFrame.contentDescription = if (previewEnabled) activity.getString(R.string.preview_current_frame)
        else activity.getString(R.string.preview_unavailable, previewIssue)
        val manualIssue = vln.manualControlIssue()
        val manualEnabled = manualIssue == null
        setAvailability(binding.manualXyz, manualEnabled)
        binding.manualXyz.contentDescription = if (manualEnabled) activity.getString(R.string.execute_manual_xyz)
        else activity.getString(R.string.manual_xyz_unavailable, manualIssue)
        binding.simulatorToggle.text = vln.simulatorButtonLabel()
        binding.simulatorStatus.text = vln.simulatorStatus()
        binding.djiAccountStatus.text = accountStatus()
        binding.djiAccountLogin.text = activity.getString(
            if (accountLoggedIn()) R.string.relogin_dji_account else R.string.login_dji_account,
        )
        setAvailability(binding.simulatorToggle, vln.simulatorControlEnabled())
    }

    override fun close() = hilController.close()

    private fun select(tab: Tab) {
        binding.contentLink.visibility = visible(tab == Tab.LINK)
        binding.contentFlight.visibility = visible(tab == Tab.FLIGHT)
        binding.contentExecution.visibility = visible(tab == Tab.EXECUTION)
        binding.contentSystem.visibility = visible(tab == Tab.SYSTEM)
        listOf(binding.tabLink, binding.tabFlight, binding.tabExecution, binding.tabSystem).forEach {
            it.setBackgroundResource(R.drawable.cockpit_button)
        }
        when (tab) {
            Tab.LINK -> binding.tabLink
            Tab.FLIGHT -> binding.tabFlight
            Tab.EXECUTION -> binding.tabExecution
            Tab.SYSTEM -> binding.tabSystem
        }.setBackgroundResource(R.drawable.cockpit_button_primary)
    }

    private fun selectTransport(transport: Transport) {
        selectedTransport = transport
        listOf(binding.transportLocal, binding.transportAoa, binding.transportEthernet).forEach {
            it.setBackgroundResource(R.drawable.cockpit_button)
        }
        when (transport) {
            Transport.LOCAL -> binding.transportLocal
            Transport.AOA -> binding.transportAoa
            Transport.ETHERNET -> binding.transportEthernet
        }.setBackgroundResource(R.drawable.cockpit_button_primary)
        binding.ethernetEndpoint.visibility = visible(transport == Transport.ETHERNET)
        val local = transport == Transport.LOCAL
        binding.importModel.isEnabled = local
        binding.downloadModel.isEnabled = local
        binding.importModel.alpha = if (local) 1f else 0.45f
        binding.downloadModel.alpha = if (local) 1f else 0.45f
        binding.aoaEnabled.isChecked = transport == Transport.AOA
        vln.selectDeferredTransport(
            when (transport) {
                Transport.LOCAL -> DeferredTransport.LOCAL
                Transport.AOA -> DeferredTransport.USB
                Transport.ETHERNET -> DeferredTransport.ETHERNET
            },
            binding.ethernetEndpoint.text.toString(),
        )
    }

    private fun executeManualXyz() {
        val x = binding.manualX.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
        val y = binding.manualY.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
        val z = binding.manualZ.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
        val numericError = activity.getString(R.string.enter_numeric_value)
        binding.manualX.error = if (x == null) numericError else null
        binding.manualY.error = if (y == null) numericError else null
        binding.manualZ.error = if (z == null) numericError else null
        if (x == null || y == null || z == null) return
        vln.executeManualRelative(x, y, z)
        renderStatus()
    }

    private fun setAvailability(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE

    private enum class Tab { LINK, FLIGHT, EXECUTION, SYSTEM }
    private enum class Transport { LOCAL, AOA, ETHERNET }

    private companion object {
    }

    private class SimpleSelection(private val action: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
