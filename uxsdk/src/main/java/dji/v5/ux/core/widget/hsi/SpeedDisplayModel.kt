package dji.v5.ux.core.widget.hsi

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.WindDirection
import dji.sdk.keyvalue.value.flightcontroller.WindWarning
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.utils.common.LogUtils
import dji.v5.ux.core.base.DJISDKModel
import dji.v5.ux.core.base.WidgetModel
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore
import dji.v5.ux.core.util.DataProcessor

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/11/25
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
open class SpeedDisplayModel constructor(
    djiSdkModel: DJISDKModel,
    keyedStore: ObservableInMemoryKeyedStore
) : WidgetModel(djiSdkModel, keyedStore) {
    private val tag = LogUtils.getTag("SpeedDisplayModel")
    private val telemetryHandler = Handler(Looper.getMainLooper())
    val velocityProcessor = DataProcessor.create(Velocity3D())
    val aircraftAttitudeProcessor: DataProcessor<Attitude> = DataProcessor.create(Attitude())
    val windSpeedProcessor = DataProcessor.create(0)
    val windDirectionProcessor = DataProcessor.create(WindDirection.WINDLESS)
    val windWarningProcessor = DataProcessor.create(WindWarning.UNKNOWN)

    private val telemetryPoller = object : Runnable {
        override fun run() {
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))?.let(velocityProcessor::onNext)
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))?.let(aircraftAttitudeProcessor::onNext)
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyWindSpeed))?.let(windSpeedProcessor::onNext)
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyWindDirection))?.let(windDirectionProcessor::onNext)
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyWindWarning))?.let(windWarningProcessor::onNext)
            telemetryHandler.postDelayed(this, TELEMETRY_POLL_INTERVAL_MS)
        }
    }

    override fun inSetup() {
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyAircraftVelocity), velocityProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyAircraftAttitude), aircraftAttitudeProcessor)

        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyWindSpeed), windSpeedProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyWindDirection), windDirectionProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyWindWarning), windWarningProcessor)
        telemetryHandler.removeCallbacks(telemetryPoller)
        telemetryHandler.post(telemetryPoller)
    }

    override fun inCleanup() {
        telemetryHandler.removeCallbacks(telemetryPoller)
        KeyManager.getInstance().cancelListen(this)
    }

    private companion object {
        const val TELEMETRY_POLL_INTERVAL_MS = 200L
    }
}
