package edu.playground.djivln.hil

import android.content.Context
import edu.playground.djivln.R
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager

class DjiV5HilSimulatorBackend(
    private val manager: SimulatorManager = SimulatorManager.getInstance(),
    private val keyManager: KeyManager = KeyManager.getInstance(),
    context: Context? = null,
) : HilSimulatorLifecycleCoordinator.Backend {
    private val appContext = context?.applicationContext
    override fun isEnabled(): Boolean = manager.isSimulatorEnabled()

    override fun enable(
        origin: HilSimulatorLifecycleCoordinator.Origin,
        callback: (Boolean, String) -> Unit,
    ) {
        val flightControllerConnected = runCatching {
            keyManager.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection))
        }.getOrNull() == true
        val flightControllerFirmware = runCatching {
            keyManager.getValue(KeyTools.createKey(FlightControllerKey.KeyFirmwareVersion))
        }.getOrNull()?.trim().orEmpty()
        val productFirmware = runCatching {
            keyManager.getValue(KeyTools.createKey(ProductKey.KeyFirmwareVersion))
        }.getOrNull()?.trim().orEmpty()
        if (!flightControllerConnected || flightControllerFirmware.isBlank()) {
            callback(
                false,
                appContext?.getString(
                    R.string.hil_waiting_for_flight_controller_handler,
                    appContext.getString(if (flightControllerConnected) R.string.state_yes else R.string.state_no),
                    flightControllerFirmware.ifBlank { "--" },
                    productFirmware.ifBlank { "--" },
                ) ?: "Waiting for flight-controller handler: connected=$flightControllerConnected " +
                    "fcFirmware=${flightControllerFirmware.ifBlank { "--" }} " +
                    "productFirmware=${productFirmware.ifBlank { "--" }}",
            )
            return
        }
        val settings = InitializationSettings.createInstance(
            LocationCoordinate2D(origin.latitude, origin.longitude),
            DjiSimulatorConfig.SATELLITE_COUNT,
        )
        manager.enableSimulator(settings, completion(callback))
    }

    override fun disable(callback: (Boolean, String) -> Unit) {
        manager.disableSimulator(completion(callback))
    }

    private fun completion(callback: (Boolean, String) -> Unit) =
        object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = callback(true, "ok")

            override fun onFailure(error: IDJIError) {
                callback(false, error.description()?.takeIf(String::isNotBlank) ?: error.toString())
            }
        }
}
