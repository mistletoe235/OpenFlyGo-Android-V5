package edu.playground.djivln.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedV5FixWiringTest {
    private val project: File
        get() {
            val current = File(requireNotNull(System.getProperty("user.dir")))
            return listOf(current, current.parentFile).first { File(it, "app/build.gradle").isFile }
        }

    private fun source(name: String) = File(project, "app/src/main/java/edu/playground/djivln/$name").readText()

    @Test
    fun mapWidgetActuallyUsesTheBoundedDisplayPolicy() {
        val widget = File(project, "uxsdk/src/main/java/dji/v5/ux/map/MapWidget.java").readText()
        assertTrue(widget.contains("MapDisplayPolicy.latestUpdates(source"))
        assertTrue(widget.contains("displayUpdates(widgetModel.getAircraftLocation())"))
        assertTrue(widget.contains("displayUpdates(Flowable.combineLatest(widgetModel.getAircraftHeading()"))
        assertTrue(widget.contains("MapDisplayPolicy.compactTrail(flightPathPoints)"))
        assertFalse(widget.contains("ValueAnimator"))
    }

    @Test
    fun missionOverlayUsesCoalescingAndDisposesItsPendingRender() {
        val controller = source("ui/FlightFeatureController.kt")
        assertTrue(controller.contains("executionOverlayUpdates.submit(overlay)"))
        assertTrue(controller.contains("executionOverlayRenderer?.render("))
        val closing = controller.substringAfter("override fun close() {")
        assertTrue(closing.contains("executionOverlayUpdates.clear()"))
        assertTrue(closing.contains("executionOverlayRenderer?.clear()"))
    }

    @Test
    fun sdkDisableAndObservedStateMustBothCompleteBeforeReleasingTheLease() {
        val controller = source("adapter/dji/DjiV5FlightControlPort.kt")
        assertTrue(controller.contains("disableCompletion.observeEnabled(state.isVirtualStickEnable)"))
        assertTrue(controller.contains("disableCompletion.completeCommand(token)"))
        val success = controller.substringAfter("private fun finishDisableSuccess()").substringBefore("private fun finishDisableFailure")
        assertTrue(success.indexOf("!disableCompletion.ready") in 0 until success.indexOf("lease.release(leaseOwner)"))
    }

    @Test
    fun bothUltrasonicReadAndListenUseTheSharedUnitConversion() {
        val telemetry = source("adapter/dji/DjiV5TelemetrySource.kt")
        assertEquals(2, "TelemetryNormalizer.decimetersToMeters(".toRegex(RegexOption.LITERAL).findAll(telemetry).count())
        val poller = source("hil/DjiV5SimulatorPoseSource.kt")
        assertTrue(poller.contains("SimulatorPosePredictor.predictFresh(previousRawPose.get(), raw, now) ?: return"))
    }
}
