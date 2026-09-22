package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

enum class DjiWaylineCaptureStrategy {
    ANDROID_DISTANCE_TRIGGER,
    WPML_PHOTO_ACTION,
}

/**
 * Survey-camera geometry derived from DJI's published product specifications.
 *
 * DJI normally publishes one (diagonal) FOV and the still-image dimensions. The
 * planner needs horizontal and vertical FOV, so those two values are derived
 * from the published diagonal FOV and image aspect ratio. Runtime camera keys
 * remain the source of truth for connection and capture support; this catalog is
 * only the calibrated geometry/cadence fallback used for route planning.
 */
object DjiCameraProfileCatalog {
    private val wpmlPhotoActionProfileIds = setOf(
        "dji-matrice-4e-wide-20mp",
        "dji-matrice-4t-wide-48mp",
        "dji-matrice-30-wide-12mp",
        "dji-mavic-3e-wide-20mp",
        "dji-mavic-3t-wide-12mp",
        "dji-mavic-3m-rgb-20mp",
    )

    fun waylineCaptureStrategy(profile: CameraProfile): DjiWaylineCaptureStrategy =
        if (profile.id in wpmlPhotoActionProfileIds) {
            DjiWaylineCaptureStrategy.WPML_PHOTO_ACTION
        } else {
            DjiWaylineCaptureStrategy.ANDROID_DISTANCE_TRIGGER
        }

    data class Resolution(
        val profile: CameraProfile,
        val displayName: String,
        val officialSourceUrl: String?,
        val verifiedProfile: Boolean,
    )

    private data class Entry(
        val aliases: Set<String>,
        val displayNameRes: Int,
        val defaultDisplayName: String,
        val profile: CameraProfile,
        val officialSourceUrl: String,
        val verifiedProfile: Boolean = true,
    )

    private const val MINI_2_SOURCE = "https://www.dji.com/support/product/mini-2"
    private const val AIR_2_SOURCE = "https://www.dji.com/uk/mavic-air-2/specs"
    private const val AIR_2S_SOURCE = "https://www.dji.com/support/product/air-2s"
    private const val MINI_3_SOURCE = "https://www.dji.com/mini-3/specs"
    private const val MINI_3_PRO_SOURCE = "https://www.dji.com/support/product/mini-3-pro"
    private const val MINI_4_PRO_SOURCE = "https://www.dji.com/mini-4-pro/specs"
    private const val M30_SOURCE = "https://enterprise.dji.com/matrice-30/specs"
    private const val M3_ENTERPRISE_SOURCE = "https://enterprise.dji.com/mavic-3-enterprise/specs"
    private const val M3M_SOURCE = "https://enterprise.dji.com/mavic-3-m/specs"
    private const val MATRICE_4_SOURCE = "https://enterprise.dji.com/matrice-4-series/specs"
    private const val MAVIC_2_SOURCE = "https://www.dji.com/mavic-2/info"
    private const val PHANTOM_4_PRO_SOURCE = "https://www.dji.com/support/product/phantom-4-pro-v2"

    private val m30WideEntry = entry(
        aliases = setOf("M30", "M30T", "M30SERIES"),
        nameRes = R.string.camera_matrice_30_wide,
        defaultName = "DJI Matrice 30 Series Wide",
        id = "dji-matrice-30-wide-12mp",
        width = 4000,
        height = 3000,
        diagonalFovDegrees = 84.0,
        minimumIntervalSeconds = 2.0,
        source = M30_SOURCE,
    )

    private val entries = listOf(
        entry(
            aliases = setOf("M4E"),
            nameRes = R.string.camera_matrice_4e_wide,
            defaultName = "DJI Matrice 4E Wide",
            id = "dji-matrice-4e-wide-20mp",
            width = 5280,
            height = 3956,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 0.5,
            source = MATRICE_4_SOURCE,
        ),
        entry(
            aliases = setOf("M4T"),
            nameRes = R.string.camera_matrice_4t_wide,
            defaultName = "DJI Matrice 4T Wide",
            id = "dji-matrice-4t-wide-48mp",
            width = 8064,
            height = 6048,
            diagonalFovDegrees = 82.0,
            minimumIntervalSeconds = 0.7,
            source = MATRICE_4_SOURCE,
        ),
        entry(
            aliases = setOf("M3E"),
            nameRes = R.string.camera_mavic_3e_wide,
            defaultName = "DJI Mavic 3E Wide",
            id = "dji-mavic-3e-wide-20mp",
            width = 5280,
            height = 3956,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 0.7,
            source = M3_ENTERPRISE_SOURCE,
        ),
        entry(
            aliases = setOf("M3T", "M3TA"),
            nameRes = R.string.camera_mavic_3t_wide,
            defaultName = "DJI Mavic 3T/3TA Wide",
            id = "dji-mavic-3t-wide-12mp",
            width = 4000,
            height = 3000,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 2.0,
            source = M3_ENTERPRISE_SOURCE,
        ),
        entry(
            aliases = setOf("M3M"),
            nameRes = R.string.camera_mavic_3m_rgb,
            defaultName = "DJI Mavic 3M RGB",
            id = "dji-mavic-3m-rgb-20mp",
            width = 5280,
            height = 3956,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 0.7,
            source = M3M_SOURCE,
        ),
        entry(
            aliases = setOf("DJIMINI4PRO"),
            nameRes = R.string.camera_mini_4_pro_12mp,
            defaultName = "DJI Mini 4 Pro 12MP",
            id = "dji-mini-4-pro-photo-12mp",
            width = 4032,
            height = 3024,
            diagonalFovDegrees = 82.1,
            minimumIntervalSeconds = 2.5,
            source = MINI_4_PRO_SOURCE,
        ),
        entry(
            aliases = setOf("DJIMINI3PRO"),
            nameRes = R.string.camera_mini_3_pro_12mp,
            defaultName = "DJI Mini 3 Pro 12MP",
            id = "dji-mini-3-pro-photo-12mp",
            width = 4032,
            height = 3024,
            diagonalFovDegrees = 82.1,
            minimumIntervalSeconds = 2.0,
            source = MINI_3_PRO_SOURCE,
        ),
        entry(
            aliases = setOf("DJIMINI3"),
            nameRes = R.string.camera_mini_3_12mp,
            defaultName = "DJI Mini 3 12MP",
            id = "dji-mini-3-photo-12mp",
            width = 4032,
            height = 3024,
            diagonalFovDegrees = 82.1,
            minimumIntervalSeconds = 2.0,
            source = MINI_3_SOURCE,
        ),
        entry(
            aliases = setOf("DJIAIR2S", "MAVICAIR2S"),
            nameRes = R.string.camera_air_2s_20mp,
            defaultName = "DJI Air 2S 20MP",
            id = "dji-air-2s-photo-20mp",
            width = 5472,
            height = 3648,
            diagonalFovDegrees = 88.0,
            minimumIntervalSeconds = 2.0,
            source = AIR_2S_SOURCE,
        ),
        entry(
            aliases = setOf("MAVIC2PRO"),
            nameRes = R.string.camera_mavic_2_pro,
            defaultName = "DJI Mavic 2 Pro",
            id = "dji-mavic-2-pro-photo-20mp",
            width = 5472,
            height = 3648,
            diagonalFovDegrees = 77.0,
            minimumIntervalSeconds = 2.0,
            source = MAVIC_2_SOURCE,
        ),
        entry(
            aliases = setOf("MAVIC2ZOOM"),
            nameRes = R.string.camera_mavic_2_zoom_wide,
            defaultName = "DJI Mavic 2 Zoom Wide",
            id = "dji-mavic-2-zoom-wide-photo-12mp",
            width = 4000,
            height = 3000,
            diagonalFovDegrees = 83.0,
            minimumIntervalSeconds = 2.0,
            source = MAVIC_2_SOURCE,
        ),
        entry(
            aliases = setOf("PHANTOM4PRO", "PHANTOM4ADVANCED", "P4PV2CAMERA"),
            nameRes = R.string.camera_phantom_4_pro_advanced,
            defaultName = "DJI Phantom 4 Pro/Advanced",
            id = "dji-phantom-4-pro-photo-20mp",
            width = 5472,
            height = 3648,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 2.0,
            source = PHANTOM_4_PRO_SOURCE,
        ),
        entry(
            aliases = setOf("MAVICAIR2"),
            nameRes = R.string.camera_mavic_air_2_12mp,
            defaultName = "DJI Mavic Air 2 12MP",
            id = "dji-mavic-air-2-photo-12mp",
            width = 4000,
            height = 3000,
            diagonalFovDegrees = 84.0,
            minimumIntervalSeconds = 2.0,
            source = AIR_2_SOURCE,
        ),
        entry(
            aliases = setOf("DJIMINI2", "MAVICMINI2", "DJIMINISE", "MAVICMINI"),
            nameRes = R.string.camera_mini_series_12mp,
            defaultName = "DJI Mini Series 12MP",
            id = "dji-mini-2-photo-4x3",
            width = 4000,
            height = 3000,
            diagonalFovDegrees = 83.0,
            minimumIntervalSeconds = 2.0,
            source = MINI_2_SOURCE,
        ),
    )

    /** Resolve from any SDK-specific identity strings (product, camera and lens). */
    fun resolve(vararg identities: String?, context: Context? = null): Resolution {
        val meaningfulIdentities = identities.filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { normalize(it) in SDK_SENTINEL_IDENTITIES }
        val normalizedIdentities = meaningfulIdentities.map(::canonicalIdentity)
        val matches = (entries + m30WideEntry).filter { entry ->
            entry.aliases.any { canonicalIdentity(it) in normalizedIdentities }
        }
        val match = matches.singleOrNull()
        val source = meaningfulIdentities.map(::normalize)
        val wrongLens = source.any { it in NON_SURVEY_LENSES }
        val familyIdentities = when (match?.profile?.id) {
            "dji-mavic-3e-wide-20mp", "dji-mavic-3t-wide-12mp", "dji-mavic-3m-rgb-20mp" -> setOf("MAVIC3ENTERPRISESERIES")
            "dji-matrice-4e-wide-20mp", "dji-matrice-4t-wide-48mp" -> setOf("MATRICE4SERIES")
            "dji-mavic-2-pro-photo-20mp", "dji-mavic-2-zoom-wide-photo-12mp" -> setOf("MAVIC2")
            else -> emptySet()
        }
        val recognizedIdentities = match?.aliases?.map(::canonicalIdentity).orEmpty().toSet() +
            familyIdentities + setOf("WIDE", "RGB", "DEFAULT")
        val unknownIdentity = normalizedIdentities.any { it !in recognizedIdentities }
        val needsWideLens = match?.profile?.id in MULTI_LENS_PROFILES
        val lensConfirmed = !needsWideLens || "WIDECAMERA" in source ||
            (match?.profile?.id == "dji-mavic-3m-rgb-20mp" && "RGBCAMERA" in source)
        return if (match != null && !wrongLens && !unknownIdentity && lensConfirmed &&
            match.profile.id != "dji-mavic-2-zoom-wide-photo-12mp") {
            match.toResolution(context)
        } else {
            Resolution(CameraProfile.GENERIC_4_BY_3,
                meaningfulIdentities.joinToString(" / ").ifBlank {
                    context?.getString(R.string.camera_unrecognized) ?: "Unrecognized camera"
                }, match?.officialSourceUrl, false)
        }
    }

    fun allVerified(context: Context? = null): List<Resolution> = entries.map {
        it.toResolution(context)
    } + m30WideEntry.toResolution(context)

    private fun Entry.toResolution(context: Context?) = Resolution(
        profile = profile,
        displayName = context?.getString(displayNameRes) ?: defaultDisplayName,
        officialSourceUrl = officialSourceUrl,
        verifiedProfile = verifiedProfile,
    )

    private fun entry(
        aliases: Set<String>,
        nameRes: Int,
        defaultName: String,
        id: String,
        width: Int,
        height: Int,
        diagonalFovDegrees: Double,
        minimumIntervalSeconds: Double,
        source: String,
    ): Entry {
        val diagonalTangent = tan(Math.toRadians(diagonalFovDegrees / 2.0))
        val aspect = width.toDouble() / height
        val verticalHalf = atan(diagonalTangent / sqrt(aspect * aspect + 1.0))
        val horizontalHalf = atan(aspect * tan(verticalHalf))
        return Entry(
            aliases = aliases.map(::normalize).toSet(),
            displayNameRes = nameRes,
            defaultDisplayName = defaultName,
            profile = CameraProfile(
                id = id,
                imageWidthPixels = width,
                imageHeightPixels = height,
                horizontalFieldOfViewDegrees = Math.toDegrees(horizontalHalf * 2.0),
                verticalFieldOfViewDegrees = Math.toDegrees(verticalHalf * 2.0),
                minimumCaptureIntervalSeconds = minimumIntervalSeconds,
            ),
            officialSourceUrl = source,
        )
    }

    private fun normalize(value: String): String = value.uppercase().filter(Char::isLetterOrDigit)

    private val SDK_SENTINEL_IDENTITIES = setOf(
        "UNKNOWN",
        "UNRECOGNIZED",
        "NOTSUPPORTED",
        "NONE",
        "DEFAULT",
        "OTHER",
    )

    private fun canonicalIdentity(value: String): String {
        val normalized = normalize(value).removePrefix("DJI").removeSuffix("CAMERA")
        return when (normalized) {
            "MATRICE30", "MATRICE30SERIES", "M30SERIES" -> "M30"
            "MATRICE30T" -> "M30T"
            "MATRICE4E" -> "M4E"
            "MATRICE4T" -> "M4T"
            "MAVIC3E" -> "M3E"
            "MAVIC3T" -> "M3T"
            "MAVIC3TA" -> "M3TA"
            "MAVIC3M" -> "M3M"
            "P4A" -> "PHANTOM4ADVANCED"
            "P4P", "P4PV2" -> "PHANTOM4PRO"
            "MAVICMINI2" -> "MINI2"
            "MAVICMINISE" -> "MINISE"
            "PHANTOM4PROFESSIONAL", "PHANTOM4PROV20", "PHANTOM4PROV2" -> "PHANTOM4PRO"
            else -> normalized
        }
    }

    private val NON_SURVEY_LENSES = setOf(
        "ZOOMCAMERA", "INFRAREDCAMERA", "THERMAL", "NDVICAMERA", "VISIONCAMERA",
        "MSGCAMERA", "MSRCAMERA", "MSRECAMERA", "MSNIRCAMERA", "POINTCLOUDCAMERA",
    )
    private val MULTI_LENS_PROFILES = setOf(
        "dji-matrice-4e-wide-20mp", "dji-matrice-4t-wide-48mp",
        "dji-mavic-3e-wide-20mp", "dji-mavic-3t-wide-12mp",
        "dji-mavic-3m-rgb-20mp", "dji-matrice-30-wide-12mp",
    )
}
