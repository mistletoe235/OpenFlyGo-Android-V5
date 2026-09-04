package edu.playground.djivln.adapter.dji

import android.os.SystemClock
import android.util.Log
import edu.playground.djivln.logging.AppDiagnosticLogger
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.AircraftSnapshotListener
import edu.playground.djivln.domain.telemetry.AircraftTelemetrySource
import edu.playground.djivln.domain.telemetry.RcStickTakeoverDetector
import edu.playground.djivln.domain.telemetry.TelemetryNormalizer

class DjiV5TelemetrySource(
    // Every freshness gate in the app uses elapsedRealtimeNanos. Mixing it with
    // System.nanoTime becomes observably wrong after device suspend.
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val cameraDiscovery: DjiV5CameraDiscovery = DjiV5CameraDiscovery(),
) : AircraftTelemetrySource {
    private val listenerOwner = Any()
    private var listener: AircraftSnapshotListener? = null
    private var current = AircraftSnapshot()
    private var started = false
    private var sdkConnected = false
    private var listenersInstalled = false
    private val stickPositions = IntArray(4)
    private val stickTakeoverDetector = RcStickTakeoverDetector()
    private var primaryRcBatteryPercent: Int? = null
    private var secondaryRcBatteryPercent: Int? = null
    private var aggregateAircraftBatteryPercent: Int? = null
    private var mainAircraftBatteryPercent: Int? = null
    private var rightAircraftBatteryPercent: Int? = null
    private var lastCoreTelemetrySummary: String? = null
    private var lastCoreTelemetryLogNanos = Long.MIN_VALUE
    private val bootstrapListener: (DjiSdkBootstrap.State) -> Unit = ::onSdkState

    @Synchronized
    override fun start(listener: AircraftSnapshotListener) {
        this.listener = listener
        if (started) {
            listener.onSnapshot(current)
            return
        }
        started = true
        DjiSdkBootstrap.addListener(bootstrapListener)
        if (!listenersInstalled) rebindTelemetry()
        listener.onSnapshot(current)
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        runCatching { KeyManager.getInstance().cancelListen(listenerOwner) }
        listenersInstalled = false
        sdkConnected = false
        DjiSdkBootstrap.removeListener(bootstrapListener)
        listener = null
    }

    @Synchronized
    override fun snapshot(): AircraftSnapshot = current

    private fun onSdkState(state: DjiSdkBootstrap.State) {
        val becameConnected = state.connected && !sdkConnected
        sdkConnected = state.connected
        if (!state.connected) {
            stickPositions.fill(0)
            stickTakeoverDetector.reset()
            primaryRcBatteryPercent = null
            secondaryRcBatteryPercent = null
            aggregateAircraftBatteryPercent = null
            mainAircraftBatteryPercent = null
            rightAircraftBatteryPercent = null
            update {
                AircraftSnapshot(
                    connected = false,
                    productId = state.productId,
                    updatedAtNanos = clockNanos(),
                    unsupportedFields = it.unsupportedFields
                )
            }
        } else {
            update { it.copy(connected = true, productId = state.productId) }
            if (becameConnected) rebindTelemetry()
        }
    }

    private fun rebindTelemetry() {
        runCatching { KeyManager.getInstance().cancelListen(listenerOwner) }
        listenersInstalled = false
        update { it.copy(unsupportedFields = emptySet()) }
        installListeners()
        listenersInstalled = true
        readSnapshot()
        Log.i(TAG, "listeners rebound sdkConnected=$sdkConnected")
    }

    private fun installListeners() {
        safeListen("aircraftLocation") {
            FlightControllerKey.KeyAircraftLocation3D.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        aircraftLocation = value?.let { location ->
                            TelemetryNormalizer.geoPoint(location.latitude, location.longitude, location.altitude)
                        },
                        aircraftLocationUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("homeLocation") {
            FlightControllerKey.KeyHomeLocation.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        homeLocation = value?.let { location ->
                            TelemetryNormalizer.geoPoint(location.latitude, location.longitude)
                        },
                        homeLocationUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("remoteControllerLocation") {
            RemoteControllerKey.KeyRcGPSInfo.create().listen(listenerOwner) { value ->
                update {
                    val reportedValid = value?.isValid
                    val normalized = value?.location?.let { coordinate ->
                        TelemetryNormalizer.geoPoint(coordinate.latitude, coordinate.longitude)
                    }
                    it.copy(
                        remoteControllerLocation = normalized.takeIf { reportedValid == true },
                        remoteControllerLocationUpdatedAtNanos = clockNanos(),
                        remoteControllerGpsValid = reportedValid,
                    )
                }
            }
        }
        safeListen("relativeAltitudeMeters") {
            FlightControllerKey.KeyAltitude.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        relativeAltitudeMeters = value?.takeIf(Double::isFinite),
                        relativeAltitudeUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("takeoffAbsoluteAltitudeMeters") {
            FlightControllerKey.KeyTakeoffLocationAltitude.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        takeoffAbsoluteAltitudeMeters = value?.takeIf(Double::isFinite),
                        takeoffAbsoluteAltitudeUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("altitudeAboveSeaLevelMeters") {
            FlightControllerKey.KeyHeightAboveSeaLevel.create().listen(listenerOwner) { value ->
                update { it.copy(altitudeAboveSeaLevelMeters = value?.height?.takeIf(Double::isFinite)) }
            }
        }
        safeListen("altitudeAboveGroundMeters") {
            FlightControllerKey.KeyUltrasonicHeight.create().listen(listenerOwner) { value ->
                update { it.copy(altitudeAboveGroundMeters = value?.takeIf { cm -> cm > 0 }?.div(100.0)) }
            }
        }
        safeListen("attitude") {
            FlightControllerKey.KeyAircraftAttitude.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        attitude = value?.let { attitude ->
                            TelemetryNormalizer.attitude(attitude.roll, attitude.pitch, attitude.yaw)
                        },
                        attitudeUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("velocity") {
            FlightControllerKey.KeyAircraftVelocity.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        velocity = value?.let { velocity ->
                            TelemetryNormalizer.nedVelocity(velocity.x, velocity.y, velocity.z)
                        },
                        velocityUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeListen("headingDegrees") {
            FlightControllerKey.KeyCompassHeading.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        headingDegrees = value?.takeIf(Double::isFinite),
                        headingUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        listenAircraftBattery(ComponentIndexType.AGGREGATION)
        listenAircraftBattery(ComponentIndexType.LEFT_OR_MAIN)
        listenAircraftBattery(ComponentIndexType.RIGHT)
        safeListen("remoteControllerBatteryPercent") {
            RemoteControllerKey.KeyBatteryInfo.create().listen(listenerOwner) { value ->
                primaryRcBatteryPercent = DjiTelemetryBatteryPolicy.retainConnectedRcPercent(
                    previous = primaryRcBatteryPercent,
                    reported = value?.batteryPercent,
                    enabled = value?.enabled,
                )
                publishRemoteControllerBattery()
            }
        }
        safeListen("remoteControllerSecondBatteryPercent") {
            RemoteControllerKey.KeySecondBatteryInfo.create().listen(listenerOwner) { value ->
                secondaryRcBatteryPercent = DjiTelemetryBatteryPolicy.retainConnectedRcPercent(
                    previous = secondaryRcBatteryPercent,
                    reported = value?.batteryPercent,
                    enabled = value?.enabled,
                )
                publishRemoteControllerBattery()
            }
        }
        safeListen("remoteControllerSignalPercent") {
            // MSDK4's setUplinkSignalQualityCallback and MSDK5's documented
            // KeySignalQuality both expose a percentage. KeyLinkSignalQuality
            // is a different SDK key and returns 5 on RC Plus, which was
            // incorrectly rendered as 5%.
            AirLinkKey.KeySignalQuality.create().listen(listenerOwner) { value ->
                update { it.copy(remoteControllerSignalPercent = value.validPercent()) }
            }
        }
        listenStick(0, "leftHorizontal", RemoteControllerKey.KeyStickLeftHorizontal)
        listenStick(1, "leftVertical", RemoteControllerKey.KeyStickLeftVertical)
        listenStick(2, "rightHorizontal", RemoteControllerKey.KeyStickRightHorizontal)
        listenStick(3, "rightVertical", RemoteControllerKey.KeyStickRightVertical)
        safeListen("gpsSatelliteCount") {
            FlightControllerKey.KeyGPSSatelliteCount.create().listen(listenerOwner) { value ->
                update { it.copy(gpsSatelliteCount = value?.takeIf { count -> count >= 0 }) }
            }
        }
        safeListen("gpsSignalLevel") {
            FlightControllerKey.KeyGPSSignalLevel.create().listen(listenerOwner) { value ->
                update { it.copy(gpsSignalLevel = value?.name) }
            }
        }
        safeListen("goHomeHeightMeters") {
            FlightControllerKey.KeyGoHomeHeight.create().listen(listenerOwner) { value ->
                update { it.copy(goHomeHeightMeters = value?.takeIf { height -> height > 0 }) }
            }
        }
        safeListen("goHomeState") {
            FlightControllerKey.KeyGoHomeStatus.create().listen(listenerOwner) { value ->
                update { it.copy(goHomeState = value?.name, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("goHomeInfo") {
            FlightControllerKey.KeyGoHomeInfo.create().listen(listenerOwner) { value ->
                update { it.copy(goHomeConfirmType = value?.type?.name) }
            }
        }
        safeListen("autoRthReason") {
            FlightControllerKey.KeyAutoRTHReason.create().listen(listenerOwner) { value ->
                update { it.copy(autoRthReason = value?.name, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("failsafeActive") {
            FlightControllerKey.KeyIsFailSafe.create().listen(listenerOwner) { value ->
                update { it.copy(failsafeActive = value, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("failsafeAction") {
            FlightControllerKey.KeyFailsafeAction.create().listen(listenerOwner) { value ->
                update { it.copy(failsafeAction = value?.name) }
            }
        }
        safeListen("lowBatteryRthEnabled") {
            FlightControllerKey.KeyLowBatteryRTHEnabled.create().listen(listenerOwner) { value ->
                update { it.copy(lowBatteryRthEnabled = value) }
            }
        }
        safeListen("lowBatteryRthInfo") {
            FlightControllerKey.KeyLowBatteryRTHInfo.create().listen(listenerOwner) { value ->
                update {
                    it.copy(
                        lowBatteryRthState = value?.lowBatteryRTHStatus?.name,
                        smartRthCountdownSeconds = value?.smartRTHCountdown?.takeIf { seconds -> seconds >= 0 },
                        remainingFlightTimeSeconds = value?.remainingFlightTime?.takeIf { seconds -> seconds >= 0 },
                        timeNeededToGoHomeSeconds = value?.timeNeededToGoHome?.takeIf { seconds -> seconds >= 0 },
                        timeNeededToLandSeconds = value?.timeNeededToLand?.takeIf { seconds -> seconds >= 0 },
                        batteryNeededToGoHomePercent = value?.batteryPercentNeededToGoHome?.takeIf { percent -> percent in 0..100 },
                        batteryNeededToLandPercent = value?.batteryPercentNeededToLand?.takeIf { percent -> percent in 0..100 },
                        maxSafeFlightRadiusMeters = value?.maxRadiusCanFlyAndGoHome?.takeIf { radius -> radius.isFinite() && radius >= 0.0 },
                    )
                }
            }
        }
        safeListen("maxFlightHeightMeters") {
            FlightControllerKey.KeyHeightLimit.create().listen(listenerOwner) { value ->
                update { it.copy(maxFlightHeightMeters = value?.takeIf { height -> height > 0 }) }
            }
        }
        safeListen("maxFlightRadiusMeters") {
            FlightControllerKey.KeyDistanceLimit.create().listen(listenerOwner) { value ->
                update { it.copy(maxFlightRadiusMeters = value?.takeIf { radius -> radius > 0 }) }
            }
        }
        safeListen("maxFlightRadiusEnabled") {
            FlightControllerKey.KeyDistanceLimitEnabled.create().listen(listenerOwner) { value ->
                update { it.copy(maxFlightRadiusEnabled = value) }
            }
        }
        safeListen("gimbalPitchDegrees") {
            GimbalKey.KeyGimbalAttitude.create(cameraDiscovery.current().index).listen(listenerOwner) { value ->
                update { it.copy(gimbalPitchDegrees = value?.pitch?.takeIf(Double::isFinite)) }
            }
        }
        safeListen("flightMode") {
            FlightControllerKey.KeyFlightModeString.create().listen(listenerOwner) { value ->
                update { it.copy(flightMode = value, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("isFlying") {
            FlightControllerKey.KeyIsFlying.create().listen(listenerOwner) { value ->
                update { it.copy(isFlying = value == true, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("landingConfirmationNeeded") {
            FlightControllerKey.KeyIsLandingConfirmationNeeded.create().listen(listenerOwner) { value ->
                update { it.copy(landingConfirmationNeeded = value == true, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("motorsOn") {
            FlightControllerKey.KeyAreMotorsOn.create().listen(listenerOwner) { value ->
                update { it.copy(motorsOn = value == true, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
        safeListen("simulatorActive") {
            FlightControllerKey.KeyIsSimulatorStarted.create().listen(listenerOwner) { value ->
                update { it.copy(simulatorActive = value == true, flightStateUpdatedAtNanos = clockNanos()) }
            }
        }
    }

    private fun listenAircraftBattery(index: ComponentIndexType) {
        safeListen("aircraftBatteryPercent:${index.name}") {
            BatteryKey.KeyChargeRemainingInPercent.create(index).listen(listenerOwner) { value ->
                val percent = value.validPercent()
                if (index == ComponentIndexType.AGGREGATION) {
                    aggregateAircraftBatteryPercent = percent
                } else if (index == ComponentIndexType.LEFT_OR_MAIN) {
                    mainAircraftBatteryPercent = percent
                } else if (index == ComponentIndexType.RIGHT) {
                    rightAircraftBatteryPercent = percent
                }
                publishAircraftBattery()
            }
        }
    }

    private fun publishRemoteControllerBattery() {
        // RC Plus-class controllers may expose an internal and an external
        // battery. Either powered source can keep the controller available, so
        // the flight-readiness gate should fail only when every enabled source
        // is below its threshold. Raw values remain visible separately.
        val percent = DjiTelemetryBatteryPolicy.remoteControllerAvailablePercent(
            primaryRcBatteryPercent,
            secondaryRcBatteryPercent,
        )
        update {
            it.copy(
                remoteControllerBatteryPercent = percent,
                remoteControllerInternalBatteryPercent = primaryRcBatteryPercent,
                remoteControllerExternalBatteryPercent = secondaryRcBatteryPercent,
            )
        }
    }

    private fun publishAircraftBattery() {
        val effectivePercent = DjiTelemetryBatteryPolicy.aircraftPercent(
            aggregateAircraftBatteryPercent,
            mainAircraftBatteryPercent,
            rightAircraftBatteryPercent,
        )
        update {
            // Prefer DJI's aggregate value on multi-battery aircraft. If a
            // model does not publish it, fall back to the lower pack rather
            // than hiding an imbalanced/low battery.
            it.copy(
                aircraftBatteryPercent = effectivePercent,
                aircraftLeftBatteryPercent = mainAircraftBatteryPercent,
                aircraftRightBatteryPercent = rightAircraftBatteryPercent,
            )
        }
    }

    private fun listenStick(
        index: Int,
        label: String,
        key: dji.sdk.keyvalue.key.DJIKeyInfo<Int>,
    ) {
        safeListen("remoteControllerStick:$label") {
            key.create().listen(listenerOwner) { value ->
                updateStick(index, value ?: 0)
            }
        }
    }

    @Synchronized
    private fun updateStick(index: Int, value: Int) {
        stickPositions[index] = value
        update { snapshot ->
            snapshot.copy(
                leftStickHorizontal = stickPositions[0],
                leftStickVertical = stickPositions[1],
                rightStickHorizontal = stickPositions[2],
                rightStickVertical = stickPositions[3],
            )
        }
    }

    private fun readSnapshot() {
        val manager = KeyManager.getInstance()
        safeRead("aircraftLocation") {
            manager.getValue(FlightControllerKey.KeyAircraftLocation3D.create())?.let { location ->
                update {
                    it.copy(
                        aircraftLocation = TelemetryNormalizer.geoPoint(location.latitude, location.longitude, location.altitude),
                        aircraftLocationUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("homeLocation") {
            manager.getValue(FlightControllerKey.KeyHomeLocation.create())?.let { location ->
                update {
                    it.copy(
                        homeLocation = TelemetryNormalizer.geoPoint(location.latitude, location.longitude),
                        homeLocationUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("remoteControllerLocation") {
            val value = manager.getValue(RemoteControllerKey.KeyRcGPSInfo.create())
            val reportedValid = value?.isValid
            val normalized = value?.location?.let { location ->
                TelemetryNormalizer.geoPoint(location.latitude, location.longitude)
            }
            update {
                it.copy(
                    remoteControllerLocation = normalized.takeIf { reportedValid == true },
                    remoteControllerLocationUpdatedAtNanos = clockNanos(),
                    remoteControllerGpsValid = reportedValid,
                )
            }
        }
        safeRead("relativeAltitudeMeters") {
            manager.getValue(FlightControllerKey.KeyAltitude.create())?.let { altitude ->
                update {
                    it.copy(
                        relativeAltitudeMeters = altitude.takeIf(Double::isFinite),
                        relativeAltitudeUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("takeoffAbsoluteAltitudeMeters") {
            manager.getValue(FlightControllerKey.KeyTakeoffLocationAltitude.create())?.let { altitude ->
                update {
                    it.copy(
                        takeoffAbsoluteAltitudeMeters = altitude.takeIf(Double::isFinite),
                        takeoffAbsoluteAltitudeUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("altitudeAboveSeaLevelMeters") {
            manager.getValue(FlightControllerKey.KeyHeightAboveSeaLevel.create())?.height?.let { altitude ->
                update { it.copy(altitudeAboveSeaLevelMeters = altitude.takeIf(Double::isFinite)) }
            }
        }
        safeRead("altitudeAboveGroundMeters") {
            manager.getValue(FlightControllerKey.KeyUltrasonicHeight.create())?.let { centimeters ->
                update { it.copy(altitudeAboveGroundMeters = centimeters.takeIf { value -> value > 0 }?.div(100.0)) }
            }
        }
        safeRead("attitude") {
            manager.getValue(FlightControllerKey.KeyAircraftAttitude.create())?.let { attitude ->
                update {
                    it.copy(
                        attitude = TelemetryNormalizer.attitude(attitude.roll, attitude.pitch, attitude.yaw),
                        attitudeUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("velocity") {
            manager.getValue(FlightControllerKey.KeyAircraftVelocity.create())?.let { velocity ->
                update {
                    it.copy(
                        velocity = TelemetryNormalizer.nedVelocity(velocity.x, velocity.y, velocity.z),
                        velocityUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("headingDegrees") {
            manager.getValue(FlightControllerKey.KeyCompassHeading.create())?.let { heading ->
                update {
                    it.copy(
                        headingDegrees = heading.takeIf(Double::isFinite),
                        headingUpdatedAtNanos = clockNanos(),
                        flightStateUpdatedAtNanos = clockNanos(),
                    )
                }
            }
        }
        safeRead("aircraftBatteryPercent:AGGREGATION") {
            aggregateAircraftBatteryPercent = manager
                .getValue(BatteryKey.KeyChargeRemainingInPercent.create(ComponentIndexType.AGGREGATION))
                .validPercent()
            publishAircraftBattery()
        }
        safeRead("aircraftBatteryPercent:LEFT_OR_MAIN") {
            mainAircraftBatteryPercent = manager
                .getValue(BatteryKey.KeyChargeRemainingInPercent.create(ComponentIndexType.LEFT_OR_MAIN))
                .validPercent()
            publishAircraftBattery()
        }
        safeRead("aircraftBatteryPercent:RIGHT") {
            rightAircraftBatteryPercent = manager
                .getValue(BatteryKey.KeyChargeRemainingInPercent.create(ComponentIndexType.RIGHT))
                .validPercent()
            publishAircraftBattery()
        }
        safeRead("remoteControllerBatteryPercent") {
            val value = manager.getValue(RemoteControllerKey.KeyBatteryInfo.create())
            primaryRcBatteryPercent = DjiTelemetryBatteryPolicy.retainConnectedRcPercent(
                previous = primaryRcBatteryPercent,
                reported = value?.batteryPercent,
                enabled = value?.enabled,
            )
            publishRemoteControllerBattery()
        }
        safeRead("remoteControllerSecondBatteryPercent") {
            val value = manager.getValue(RemoteControllerKey.KeySecondBatteryInfo.create())
            secondaryRcBatteryPercent = DjiTelemetryBatteryPolicy.retainConnectedRcPercent(
                previous = secondaryRcBatteryPercent,
                reported = value?.batteryPercent,
                enabled = value?.enabled,
            )
            publishRemoteControllerBattery()
        }
        safeRead("flightSafetyLimits") {
            update {
                it.copy(
                    remoteControllerSignalPercent = manager.getValue(AirLinkKey.KeySignalQuality.create()).validPercent(),
                    gpsSatelliteCount = manager.getValue(FlightControllerKey.KeyGPSSatelliteCount.create())?.takeIf { count -> count >= 0 },
                    gpsSignalLevel = manager.getValue(FlightControllerKey.KeyGPSSignalLevel.create())?.name,
                    goHomeHeightMeters = manager.getValue(FlightControllerKey.KeyGoHomeHeight.create())?.takeIf { height -> height > 0 },
                    goHomeState = manager.getValue(FlightControllerKey.KeyGoHomeStatus.create())?.name,
                    goHomeConfirmType = manager.getValue(FlightControllerKey.KeyGoHomeInfo.create())?.type?.name,
                    autoRthReason = manager.getValue(FlightControllerKey.KeyAutoRTHReason.create())?.name,
                    failsafeActive = manager.getValue(FlightControllerKey.KeyIsFailSafe.create()),
                    failsafeAction = manager.getValue(FlightControllerKey.KeyFailsafeAction.create())?.name,
                    lowBatteryRthEnabled = manager.getValue(FlightControllerKey.KeyLowBatteryRTHEnabled.create()),
                    maxFlightHeightMeters = manager.getValue(FlightControllerKey.KeyHeightLimit.create())?.takeIf { height -> height > 0 },
                    maxFlightRadiusMeters = manager.getValue(FlightControllerKey.KeyDistanceLimit.create())?.takeIf { radius -> radius > 0 },
                    maxFlightRadiusEnabled = manager.getValue(FlightControllerKey.KeyDistanceLimitEnabled.create()),
                )
            }
        }
        safeRead("lowBatteryRthInfo") {
            val value = manager.getValue(FlightControllerKey.KeyLowBatteryRTHInfo.create())
            update {
                it.copy(
                    lowBatteryRthState = value?.lowBatteryRTHStatus?.name,
                    smartRthCountdownSeconds = value?.smartRTHCountdown?.takeIf { seconds -> seconds >= 0 },
                    remainingFlightTimeSeconds = value?.remainingFlightTime?.takeIf { seconds -> seconds >= 0 },
                    timeNeededToGoHomeSeconds = value?.timeNeededToGoHome?.takeIf { seconds -> seconds >= 0 },
                    timeNeededToLandSeconds = value?.timeNeededToLand?.takeIf { seconds -> seconds >= 0 },
                    batteryNeededToGoHomePercent = value?.batteryPercentNeededToGoHome?.takeIf { percent -> percent in 0..100 },
                    batteryNeededToLandPercent = value?.batteryPercentNeededToLand?.takeIf { percent -> percent in 0..100 },
                    maxSafeFlightRadiusMeters = value?.maxRadiusCanFlyAndGoHome?.takeIf { radius -> radius.isFinite() && radius >= 0.0 },
                )
            }
        }
        safeRead("flightState") {
            update {
                it.copy(
                    flightMode = manager.getValue(FlightControllerKey.KeyFlightModeString.create()),
                    isFlying = manager.getValue(FlightControllerKey.KeyIsFlying.create()) == true,
                    landingConfirmationNeeded = manager.getValue(
                        FlightControllerKey.KeyIsLandingConfirmationNeeded.create(),
                    ) == true,
                    motorsOn = manager.getValue(FlightControllerKey.KeyAreMotorsOn.create()) == true,
                    simulatorActive = manager.getValue(FlightControllerKey.KeyIsSimulatorStarted.create()) == true,
                    flightStateUpdatedAtNanos = clockNanos(),
                )
            }
        }
        safeRead("gimbalPitchDegrees") {
            manager.getValue(GimbalKey.KeyGimbalAttitude.create(cameraDiscovery.current().index))?.pitch?.let { pitch ->
                update { it.copy(gimbalPitchDegrees = pitch.takeIf(Double::isFinite)) }
            }
        }
        safeRead("remoteControllerSticks") {
            stickPositions[0] = manager.getValue(RemoteControllerKey.KeyStickLeftHorizontal.create()) ?: 0
            stickPositions[1] = manager.getValue(RemoteControllerKey.KeyStickLeftVertical.create()) ?: 0
            stickPositions[2] = manager.getValue(RemoteControllerKey.KeyStickRightHorizontal.create()) ?: 0
            stickPositions[3] = manager.getValue(RemoteControllerKey.KeyStickRightVertical.create()) ?: 0
            update { snapshot ->
                snapshot.copy(
                    leftStickHorizontal = stickPositions[0],
                    leftStickVertical = stickPositions[1],
                    rightStickHorizontal = stickPositions[2],
                    rightStickVertical = stickPositions[3],
                )
            }
        }
    }

    private inline fun safeListen(field: String, block: () -> Unit) {
        runCatching(block).onFailure { error -> markUnsupported(field, error) }
    }

    private inline fun safeRead(field: String, block: () -> Unit) {
        runCatching(block).onFailure { error -> markUnsupported(field, error) }
    }

    @Synchronized
    private fun markUnsupported(field: String, error: Throwable) {
        val firstOccurrence = field !in current.unsupportedFields
        current = current.copy(unsupportedFields = current.unsupportedFields + field)
        if (firstOccurrence) {
            AppDiagnosticLogger.warn(
                source = TAG,
                message = "DJI telemetry field unavailable: $field",
                error = error,
                fields = mapOf("field" to field),
            )
        }
    }

    @Synchronized
    private fun update(reducer: (AircraftSnapshot) -> AircraftSnapshot) {
        val now = clockNanos()
        current = reducer(current).copy(
            sticksActive = stickTakeoverDetector.update(stickPositions, now),
            updatedAtNanos = now,
        )
        logCoreTelemetryIfChanged(current, now)
        if (started) listener?.onSnapshot(current)
    }

    private fun logCoreTelemetryIfChanged(snapshot: AircraftSnapshot, nowNanos: Long) {
        if (lastCoreTelemetryLogNanos != Long.MIN_VALUE &&
            nowNanos - lastCoreTelemetryLogNanos < CORE_TELEMETRY_LOG_INTERVAL_NANOS
        ) return
        lastCoreTelemetryLogNanos = nowNanos
        val summary = buildString {
            append("connected=${snapshot.connected}")
            append(" gps=${snapshot.gpsSatelliteCount ?: "--"}/${snapshot.gpsSignalLevel ?: "--"}")
            append(" link=${snapshot.remoteControllerSignalPercent ?: "--"}%")
            append(" aircraft=${snapshot.aircraftBatteryPercent ?: "--"}%")
            append("[aggregate=${aggregateAircraftBatteryPercent ?: "--"}")
            append(",left=${mainAircraftBatteryPercent ?: "--"}")
            append(",right=${rightAircraftBatteryPercent ?: "--"}]")
            append(" rc=${snapshot.remoteControllerBatteryPercent ?: "--"}%")
            append("[internal=${primaryRcBatteryPercent ?: "--"}")
            append(",external=${secondaryRcBatteryPercent ?: "--"}]")
            append(" rcGps=${snapshot.remoteControllerGpsValid ?: "--"}")
            append("/${if (snapshot.remoteControllerLocation != null) "usable" else "none"}")
        }
        if (summary == lastCoreTelemetrySummary) return
        lastCoreTelemetrySummary = summary
        Log.i(TAG, summary)
    }

    private fun Int?.validPercent(): Int? = this?.takeIf { it in 0..100 }

    private companion object {
        const val TAG = "OpenFlyV5Telemetry"
        const val CORE_TELEMETRY_LOG_INTERVAL_NANOS = 1_000_000_000L
    }
}
