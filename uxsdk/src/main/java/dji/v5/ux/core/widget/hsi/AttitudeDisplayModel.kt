package dji.v5.ux.core.widget.hsi

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dji.sdk.keyvalue.key.*
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.common.XYZ
import dji.sdk.keyvalue.value.flightcontroller.HeightAboveSeaLevelMsg
import dji.sdk.keyvalue.value.flightcontroller.SimulatorState
import dji.sdk.keyvalue.value.rtkmobilestation.RTKTakeoffAltitudeInfo
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionInfo
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener
import dji.v5.manager.aircraft.perception.radar.RadarInformation
import dji.v5.manager.aircraft.perception.radar.RadarInformationListener
import dji.v5.ux.core.base.DJISDKModel
import dji.v5.ux.core.base.WidgetModel
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore
import dji.v5.ux.core.util.DataProcessor

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/11/26
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
open class AttitudeDisplayModel constructor(
    djiSdkModel: DJISDKModel,
    keyedStore: ObservableInMemoryKeyedStore,
) : WidgetModel(djiSdkModel, keyedStore) {
    private val perceptionManager = PerceptionManager.getInstance()
    private val altitudeHandler = Handler(Looper.getMainLooper())
    val velocityProcessor = DataProcessor.create(Velocity3D())
    val altitudeProcessor = DataProcessor.create(0.0)
    private val rawAltitudeProcessor = DataProcessor.create(0.0)
    private val relativeToTakeoffPositionProcessor = DataProcessor.create(XYZ())
    private val simulatorStateProcessor = DataProcessor.create(SimulatorState())
    val goHomeHeightProcessor: DataProcessor<Int> = DataProcessor.create(0)
    val limitMaxFlightHeightInMeterProcessor = DataProcessor.create(0)
    val rtkTakeoffAltitudeInfoProcessor = DataProcessor.create(RTKTakeoffAltitudeInfo())
    val heightAboveSeaLevelProcessor = DataProcessor.create(HeightAboveSeaLevelMsg())

    val aircraftLocationDataProcessor = DataProcessor.create(LocationCoordinate2D(Double.NaN, Double.NaN))
    val perceptionInfoProcessor = DataProcessor.create(PerceptionInfo())
    val radarInfoProcessor = DataProcessor.create(RadarInformation())
    val perceptionObstacleDataProcessor = DataProcessor.create(ObstacleData())
    val radarObstacleDataProcessor = DataProcessor.create(ObstacleData())

    private var lastPerceptionProbeLogMs = 0L
    private var lastPerceptionProbeSignature = ""

    private val perceptionInformationListener = PerceptionInformationListener {
        perceptionInfoProcessor.onNext(it)
    }

    private val perceptionObstacleDataListener = ObstacleDataListener {
        perceptionObstacleDataProcessor.onNext(it)
        logPerceptionProbe("vision", it)
    }

    private val radarObstacleDataListener = ObstacleDataListener {
        radarObstacleDataProcessor.onNext(it)
        logPerceptionProbe("radar", it)
    }

    private fun logPerceptionProbe(source: String, data: ObstacleData) {
        val horizontal = data.horizontalObstacleDistance ?: emptyList()
        val signature = buildString {
            append(source)
            append('|').append(data.horizontalAngleInterval)
            append('|').append(data.upwardObstacleDistance)
            append('|').append(data.downwardObstacleDistance)
            append('|').append(horizontal.hashCode())
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerceptionProbeLogMs < 1_000L) return
        lastPerceptionProbeSignature = signature
        lastPerceptionProbeLogMs = now
        val valid = horizontal.filter { distance -> distance in 1 until 60_000 }
        Log.i(
            PERCEPTION_PROBE_TAG,
            "source=$source angleInterval=${data.horizontalAngleInterval} " +
                "up=${data.upwardObstacleDistance} down=${data.downwardObstacleDistance} " +
                "count=${horizontal.size} valid=${valid.size} " +
                "min=${valid.minOrNull() ?: -1} max=${valid.maxOrNull() ?: -1} " +
                "distances=${horizontal.joinToString(",")}",
        )
    }
    private val radarInformationListener = RadarInformationListener {
        radarInfoProcessor.onNext(it)
    }

    private val altitudePoller = object : Runnable {
        override fun run() {
            val raw = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude))
            val relative = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyRelateToTakeOffPosition))
            val simulator = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeySimulatorState))
            val simulatorStarted = KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyIsSimulatorStarted)
            ) == true
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))?.let(velocityProcessor::onNext)
            KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyHeightAboveSeaLevel))?.let(heightAboveSeaLevelProcessor::onNext)
            val aircraftLocation = KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftLocation)
            )
            val homeLocation = KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyHomeLocation)
            )
            when {
                aircraftLocation.isUsableLocation() -> aircraftLocationDataProcessor.onNext(aircraftLocation!!)
                homeLocation.isUsableLocation() -> aircraftLocationDataProcessor.onNext(homeLocation!!)
                simulatorStarted -> aircraftLocationDataProcessor.onNext(SHANGHAI_SIMULATOR_LOCATION)
            }
            updateEffectiveAltitude(
                rawAltitude = raw ?: rawAltitudeProcessor.value,
                relativePosition = relative ?: relativeToTakeoffPositionProcessor.value,
                simulatorState = simulator ?: simulatorStateProcessor.value,
            )
            altitudeHandler.postDelayed(this, ALTITUDE_POLL_INTERVAL_MS)
        }
    }



    override fun inSetup() {
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyAircraftVelocity), velocityProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyAltitude), rawAltitudeProcessor) { altitude ->
            updateEffectiveAltitude(rawAltitude = altitude)
        }
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyRelateToTakeOffPosition), relativeToTakeoffPositionProcessor) { position ->
            updateEffectiveAltitude(relativePosition = position)
        }
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeySimulatorState), simulatorStateProcessor) { state ->
            updateEffectiveAltitude(simulatorState = state)
        }
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyGoHomeHeight), goHomeHeightProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyHeightLimit), limitMaxFlightHeightInMeterProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyHeightAboveSeaLevel), heightAboveSeaLevelProcessor)

        bindDataProcessor(
            KeyTools.createKey(
                RtkMobileStationKey.KeyRTKTakeoffAltitudeInfo), rtkTakeoffAltitudeInfoProcessor)
        bindDataProcessor(
            KeyTools.createKey(
                FlightControllerKey.KeyAircraftLocation), aircraftLocationDataProcessor)


        perceptionManager.addPerceptionInformationListener(perceptionInformationListener)
        perceptionManager.addObstacleDataListener(perceptionObstacleDataListener)
        perceptionManager.radarManager.addRadarInformationListener(radarInformationListener)
        perceptionManager.radarManager.addObstacleDataListener(radarObstacleDataListener)
        altitudeHandler.removeCallbacks(altitudePoller)
        altitudeHandler.post(altitudePoller)

    }

    override fun inCleanup() {
        perceptionManager.removePerceptionInformationListener(perceptionInformationListener)
        perceptionManager.removeObstacleDataListener(perceptionObstacleDataListener)
        perceptionManager.radarManager.removeRadarInformationListener(radarInformationListener)
        perceptionManager.radarManager.removeObstacleDataListener(radarObstacleDataListener)
        altitudeHandler.removeCallbacks(altitudePoller)
        KeyManager.getInstance().cancelListen(this)

    }

    private fun updateEffectiveAltitude(
        rawAltitude: Double = rawAltitudeProcessor.value,
        relativePosition: XYZ = relativeToTakeoffPositionProcessor.value,
        simulatorState: SimulatorState = simulatorStateProcessor.value,
    ) {
        val raw = rawAltitude.takeIf { it.isFinite() }
        val simulatorHeight = simulatorState.positionZ
            ?.takeIf { it.isFinite() }
            ?.let { kotlin.math.abs(it) }
        val relativeHeight = relativePosition.z
            ?.takeIf { it.isFinite() }
            ?.let { kotlin.math.abs(it) }
        altitudeProcessor.onNext(
            when {
                raw != null && kotlin.math.abs(raw) >= ALTITUDE_EPSILON_METERS -> raw
                simulatorHeight != null && simulatorHeight >= ALTITUDE_EPSILON_METERS -> simulatorHeight
                relativeHeight != null && relativeHeight >= ALTITUDE_EPSILON_METERS -> relativeHeight
                raw != null -> raw
                simulatorHeight != null -> simulatorHeight
                relativeHeight != null -> relativeHeight
                else -> 0.0
            }
        )
    }

    private companion object {
        const val ALTITUDE_EPSILON_METERS = 0.05
        const val ALTITUDE_POLL_INTERVAL_MS = 200L
        const val PERCEPTION_PROBE_TAG = "OpenFlyPerceptionProbe"
        val SHANGHAI_SIMULATOR_LOCATION = LocationCoordinate2D(31.2304, 121.4737)

        fun LocationCoordinate2D?.isUsableLocation(): Boolean =
            this != null &&
                latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                !(kotlin.math.abs(latitude) < 0.000001 && kotlin.math.abs(longitude) < 0.000001)
    }
}
