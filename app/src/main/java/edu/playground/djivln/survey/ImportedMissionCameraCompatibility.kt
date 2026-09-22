package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import kotlin.math.abs

data class ImportedMissionCameraCompatibility(
    val compatible: Boolean,
    val reasons: List<String>,
    val minimumPlannedCaptureIntervalSeconds: Double?,
)

object ImportedMissionCameraCompatibilityPolicy {
    fun evaluate(
        mission: SurveyMission,
        currentCamera: CameraProfile,
        cameraConnected: Boolean,
        profileVerified: Boolean,
        requireKmzPayload: Boolean,
        payloadPositionSupported: Boolean,
        payloadLensSupported: Boolean,
        context: Context? = null,
    ): ImportedMissionCameraCompatibility {
        require(mission.activeMapping != null) { "policy only applies to imported active-recapture missions" }
        val reasons = mutableListOf<String>()
        if (!cameraConnected) reasons += text(context, R.string.current_camera_disconnected, "Current camera is disconnected")
        if (!profileVerified) reasons += text(context, R.string.current_camera_not_calibrated, "Current camera is not calibrated")

        if (!SurveyCameraModePolicy.compatibleRecapture(mission.cameraProfile, currentCamera)) {
            reasons += text(context, R.string.current_camera_not_calibrated, "Current camera is not calibrated")
        }
        val missionAspect = mission.cameraProfile.imageWidthPixels.toDouble() /
            mission.cameraProfile.imageHeightPixels
        val currentAspect = currentCamera.imageWidthPixels.toDouble() /
            currentCamera.imageHeightPixels
        if (abs(missionAspect - currentAspect) > 0.03) {
            reasons += context?.getString(R.string.camera_aspect_mismatch, missionAspect, currentAspect)
                ?: "Mission requires %.2f:1 aspect ratio; current camera profile is %.2f:1".format(missionAspect, currentAspect)
        }

        val minimumPlannedInterval = mission.surveyPasses()
            .filterNot { it.isPointCapture || it.isTransitOnly }
            .mapNotNull { pass ->
                pass.start.captureIntervalMeters?.div(
                    mission.constraints.speedForCaptureView(pass.start.captureView),
                )
            }
            .minOrNull()
        if (minimumPlannedInterval != null &&
            minimumPlannedInterval + 1.0e-9 < currentCamera.minimumCaptureIntervalSeconds
        ) {
            reasons += context?.getString(
                R.string.camera_interval_too_slow,
                currentCamera.minimumCaptureIntervalSeconds,
                minimumPlannedInterval,
            ) ?: "Current camera minimum capture interval is %.2fs; mission requires %.2fs".format(
                    currentCamera.minimumCaptureIntervalSeconds,
                    minimumPlannedInterval,
                )
        }

        val pitchOutsideRange = mission.waypoints.firstOrNull {
            it.gimbalPitchDegrees !in -90.0..30.0
        }
        if (pitchOutsideRange != null) {
            reasons += context?.getString(R.string.mission_gimbal_pitch_out_of_range, pitchOutsideRange.gimbalPitchDegrees)
                ?: "Mission gimbal pitch %.1f° is outside the general execution range -90° to 30°".format(
                    pitchOutsideRange.gimbalPitchDegrees,
                )
        }
        if (requireKmzPayload && !payloadPositionSupported) {
            reasons += text(context, R.string.camera_position_not_mappable_to_kmz, "Current camera position cannot map to a DJI KMZ payload")
        }
        if (requireKmzPayload && !payloadLensSupported) {
            reasons += text(context, R.string.camera_source_not_mappable_to_kmz_lens, "Current image source cannot map to a DJI KMZ capture lens")
        }
        return ImportedMissionCameraCompatibility(
            compatible = reasons.isEmpty(),
            reasons = reasons,
            minimumPlannedCaptureIntervalSeconds = minimumPlannedInterval,
        )
    }

    private fun text(context: Context?, resourceId: Int, fallback: String): String =
        context?.getString(resourceId) ?: fallback
}
