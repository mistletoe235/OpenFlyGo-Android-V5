package edu.playground.djivln.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import dji.v5.manager.aircraft.simulator.SimulatorManager
import edu.playground.djivln.R
import edu.playground.djivln.adapter.dji.DjiV5CameraDiscovery
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.hil.HilRuntimeSession
import edu.playground.djivln.hil.SimulatorOrigin
import edu.playground.djivln.hil.SimulatorOriginParser
import edu.playground.djivln.hil.SimulatorOriginStore

/**
 * Compatibility surface retained by the survey/HIL shell.
 *
 * The public repository intentionally contains no VLN, local-model or remote-inference
 * implementation. Every inference/control entry point below fails closed.
 */
class DeferredFeatureController(
    private val activity: Activity,
    @Suppress("UNUSED_PARAMETER") container: FrameLayout,
    private val snapshot: () -> AircraftSnapshot,
    @Suppress("UNUSED_PARAMETER") hilSession: HilRuntimeSession,
    @Suppress("UNUSED_PARAMETER") cameraDiscovery: DjiV5CameraDiscovery,
    @Suppress("UNUSED_PARAMETER") accountIssue: () -> String? = { null },
    @Suppress("UNUSED_PARAMETER") onOpenMore: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenLog: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onFramePreviewReady: () -> Unit = {},
    private val onStatusChanged: (String) -> Unit = {},
    private val onToggleSimulator: (SimulatorOrigin, Boolean, (Boolean, String) -> Unit) -> Unit,
) : AutoCloseable {
    private val simulatorManager = SimulatorManager.getInstance()
    private val simulatorOriginStore = SimulatorOriginStore(activity.applicationContext)
    private var status = activity.getString(R.string.public_inference_not_included)
    private var maximumHorizontalSpeed = 1.0
    private var velocityEstimate = false

    fun createImportIntent(): Intent = Intent()
    fun importModel(@Suppress("UNUSED_PARAMETER") uri: Uri) = unavailable()
    fun requestModelImport() = unavailable()
    fun downloadCloudModel() = unavailable()
    fun checkHealth() = unavailable()
    fun loadModel() = unavailable()
    fun preflightModel() = unavailable()
    fun startModelSession() = unavailable()
    fun startAutomatic() = unavailable()
    fun stopAutomatic() = Unit
    fun resetRuntime() = unavailable()
    fun inferSingleFrame() = unavailable()
    fun previewFrame() = unavailable()
    fun inferenceIssue(): String = activity.getString(R.string.public_inference_not_included)
    fun previewFrameIssue(): String = activity.getString(R.string.public_inference_not_included)
    fun manualControlIssue(): String = activity.getString(R.string.public_inference_not_included)
    fun executeManualRelative(forward: Double, right: Double, up: Double) = unavailable()
    fun stopManualRelative() = Unit

    fun setVelocityEstimateEnabled(enabled: Boolean) { velocityEstimate = enabled }
    fun velocityEstimateEnabled(): Boolean = velocityEstimate
    fun setAoaEnabled(enabled: Boolean) = Unit
    fun setExecutedPrefix(count: Int) = Unit
    fun selectedExecutedPrefix(): Int = 1
    fun setMaximumHorizontalSpeed(metersPerSecond: Double) {
        maximumHorizontalSpeed = metersPerSecond.coerceIn(0.2, 4.0)
    }
    fun maximumHorizontalSpeed(): Double = maximumHorizontalSpeed
    fun stopThreshold(): Double = 0.7
    fun cycleStopThreshold(): Double = 0.7
    fun continuousChunkEnabled(): Boolean = false
    fun setContinuousChunkEnabled(enabled: Boolean) = Unit
    fun flyThroughEnabled(): Boolean = false
    fun setFlyThroughEnabled(enabled: Boolean) = Unit
    fun isControlActive(): Boolean = false
    fun controlSummary(): String = activity.getString(R.string.public_inference_not_included)

    fun simulatorButtonLabel(): String = activity.getString(
        if (isDjiSimulatorActive()) R.string.dji_simulator_on_tap_to_stop
        else R.string.dji_simulator_off_tap_to_start,
    )
    fun simulatorStatus(): String = status
    fun isDjiSimulatorActive(): Boolean = simulatorManager.isSimulatorEnabled() || snapshot().simulatorActive
    fun simulatorOrigin(): SimulatorOrigin = simulatorOriginStore.load()

    fun updateSimulatorOrigin(latitudeText: String, longitudeText: String): Boolean {
        val origin = SimulatorOriginParser.parse(latitudeText, longitudeText, activity).getOrElse {
            status = activity.getString(R.string.simulator_origin_invalid, it.message ?: it.javaClass.simpleName)
            onStatusChanged(status)
            return false
        }
        simulatorOriginStore.save(origin)
        return true
    }

    fun useAircraftLocationAsSimulatorOrigin(): Boolean {
        val location = snapshot().aircraftLocation ?: return false
        simulatorOriginStore.save(SimulatorOrigin(location.latitude, location.longitude))
        return true
    }

    fun simulatorControlEnabled(): Boolean {
        val current = snapshot()
        return current.connected && !current.isFlying
    }

    fun toggleDjiSimulator() {
        val origin = simulatorOriginStore.load()
        val enable = !isDjiSimulatorActive()
        onToggleSimulator(origin, enable) { success, message ->
            status = message
            onStatusChanged(message)
            if (!success) unavailable(message)
        }
    }

    fun selectDeferredTransport(transport: DeferredTransport, ethernetInput: String = "") = unavailable()
    fun setEthernetEndpoint(value: String) = Unit
    fun selectedDeferredTransport(): DeferredTransport = DeferredTransport.LOCAL
    fun currentEthernetEndpoint(): String = ""
    fun cameraSourceLabels(): List<String> = listOf("DJI", "UE HIL")
    fun selectedCameraSourceIndex(): Int = 0
    fun selectCameraSource(position: Int) = Unit
    fun currentStatus(): String = status
    fun renderAircraftConnection(connected: Boolean) = Unit

    private fun unavailable(message: String = activity.getString(R.string.public_inference_not_included)) {
        status = message
        onStatusChanged(message)
    }

    override fun close() = Unit

    companion object {
        const val REQUEST_IMPORT_MODEL = 7_301
    }
}
