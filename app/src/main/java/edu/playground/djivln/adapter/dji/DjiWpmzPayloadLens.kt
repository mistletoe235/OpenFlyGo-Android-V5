package edu.playground.djivln.adapter.dji

import dji.sdk.wpmz.value.mission.CameraLensType

object DjiWpmzPayloadLens {
    fun fromStreamSourceName(sourceName: String?): CameraLensType? = when (sourceName?.uppercase()) {
        "DEFAULT_CAMERA", "WIDE_CAMERA" -> CameraLensType.WIDE
        "ZOOM_CAMERA" -> CameraLensType.ZOOM
        "INFRARED_CAMERA" -> CameraLensType.IR
        "RGB_CAMERA" -> CameraLensType.VISABLE
        "MS_G_CAMERA", "MS_R_CAMERA", "MS_RE_CAMERA", "MS_NIR_CAMERA" -> CameraLensType.NARROW_BAND
        "NDVI_CAMERA", "VISION_CAMERA", "POINT_CLOUD_CAMERA", "UNKNOWN" -> null
        null -> null
        else -> null
    }
}
