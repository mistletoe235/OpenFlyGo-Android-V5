package edu.playground.djivln.survey

import kotlin.math.abs

enum class SurveyCameraModeIssue {
    PHOTO_RATIO_UNCONFIRMED, PHOTO_RESOLUTION_UNCONFIRMED, ZOOM_UNCONFIRMED, ORIENTATION_UNCONFIRMED,
}

object SurveyCameraModePolicy {
    fun captureGeometry(base: CameraProfile, aspectRatio: Double?): CameraProfile? {
        if (aspectRatio == null || !aspectRatio.isFinite()) return null
        val baseRatio = base.imageWidthPixels.toDouble() / base.imageHeightPixels
        if (abs(aspectRatio - baseRatio) <= 0.01) return base
        if (abs(aspectRatio - 16.0 / 9.0) > 0.01 || aspectRatio < baseRatio) return null
        val height = (base.imageWidthPixels * 9.0 / 16.0).toInt().coerceAtLeast(1)
        val verticalFov = Math.toDegrees(2.0 * kotlin.math.atan(
            kotlin.math.tan(Math.toRadians(base.horizontalFieldOfViewDegrees / 2.0)) /
                (base.imageWidthPixels.toDouble() / height),
        ))
        return base.copy(imageHeightPixels = height, verticalFieldOfViewDegrees = verticalFov)
    }

    fun issue(
        profile: CameraProfile,
        aspectRatio: Double?,
        resolutionMegapixels: Int?,
        requireResolution: Boolean,
        zoomRatio: Double?,
        requireZoom: Boolean,
        landscape: Boolean?,
        requireOrientation: Boolean,
    ): SurveyCameraModeIssue? {
        val expectedRatio = profile.imageWidthPixels.toDouble() / profile.imageHeightPixels
        if (aspectRatio == null || !aspectRatio.isFinite() || abs(aspectRatio - expectedRatio) > 0.01) {
            return SurveyCameraModeIssue.PHOTO_RATIO_UNCONFIRMED
        }
        val expectedMegapixels = when {
            profile.imageWidthPixels >= 7000 -> 48
            profile.imageWidthPixels >= 5000 -> 20
            else -> 12
        }
        if ((requireResolution && resolutionMegapixels == null) ||
            (resolutionMegapixels != null && resolutionMegapixels != expectedMegapixels)) {
            return SurveyCameraModeIssue.PHOTO_RESOLUTION_UNCONFIRMED
        }
        if ((requireZoom && zoomRatio == null) ||
            (zoomRatio != null && (!zoomRatio.isFinite() || abs(zoomRatio - 1.0) > 0.01))) {
            return SurveyCameraModeIssue.ZOOM_UNCONFIRMED
        }
        if (landscape == false || (requireOrientation && landscape != true)) {
            return SurveyCameraModeIssue.ORIENTATION_UNCONFIRMED
        }
        return null
    }

    fun compatibleRecapture(planned: CameraProfile, current: CameraProfile): Boolean =
        current.imageWidthPixels >= planned.imageWidthPixels &&
            current.imageHeightPixels >= planned.imageHeightPixels &&
            abs(planned.imageWidthPixels.toDouble() / planned.imageHeightPixels -
                current.imageWidthPixels.toDouble() / current.imageHeightPixels) <= 0.01 &&
            abs(planned.horizontalFieldOfViewDegrees - current.horizontalFieldOfViewDegrees) <= 0.1 &&
            abs(planned.verticalFieldOfViewDegrees - current.verticalFieldOfViewDegrees) <= 0.1

    fun sameGeometry(planned: CameraProfile, current: CameraProfile): Boolean =
        planned.imageWidthPixels == current.imageWidthPixels &&
            planned.imageHeightPixels == current.imageHeightPixels &&
            abs(planned.horizontalFieldOfViewDegrees - current.horizontalFieldOfViewDegrees) <= 0.1 &&
            abs(planned.verticalFieldOfViewDegrees - current.verticalFieldOfViewDegrees) <= 0.1
}
