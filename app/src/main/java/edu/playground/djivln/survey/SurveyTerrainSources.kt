package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
/** The semantic role of an elevation source selected by the survey planner. */
enum class SurveyTerrainSourceKind {
    SURFACE_DSM,
    BARE_EARTH,
}

data class SurveyTerrainAsset(
    val source: TerrainElevationSource,
    val sha256: String,
    val bareEarthBaseSha256: String? = null,
) {
    init {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "terrain SHA-256 is invalid" }
        require(bareEarthBaseSha256 == null || bareEarthBaseSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "bare-earth SHA-256 is invalid"
        }
    }

    val normalizedSha256: String = sha256.lowercase()
    val normalizedBareEarthBaseSha256: String? = bareEarthBaseSha256?.lowercase()
}

/**
 * Keeps bare-earth and surface products independent.
 *
 * A UI label must never reclassify the active raster. Selecting DEM returns the
 * stored bare-earth asset; selecting DSM returns the stored surface asset.
 */
class SurveyTerrainSourceCatalog(context: Context? = null) {
    private val appContext = context?.applicationContext
    var bareEarth: SurveyTerrainAsset? = null
        private set
    var surface: SurveyTerrainAsset? = null
        private set
    var selectedKind: SurveyTerrainSourceKind = SurveyTerrainSourceKind.SURFACE_DSM
        private set

    val active: SurveyTerrainAsset?
        get() = when (selectedKind) {
            SurveyTerrainSourceKind.SURFACE_DSM -> surface
            SurveyTerrainSourceKind.BARE_EARTH -> bareEarth
        }

    fun prefer(kind: SurveyTerrainSourceKind) {
        selectedKind = kind
    }

    fun select(kind: SurveyTerrainSourceKind): Result<SurveyTerrainAsset> = runCatching {
        val selected = when (kind) {
            SurveyTerrainSourceKind.SURFACE_DSM -> surface
            SurveyTerrainSourceKind.BARE_EARTH -> bareEarth
        } ?: error(
            if (kind == SurveyTerrainSourceKind.BARE_EARTH) {
                appContext?.getString(R.string.bare_earth_dem_not_loaded) ?: "Bare-earth DEM/DTM is not loaded"
            } else {
                appContext?.getString(R.string.surface_dsm_not_loaded) ?: "Surface DSM is not loaded"
            },
        )
        selectedKind = kind
        selected
    }

    fun installBareEarth(
        source: TerrainElevationSource,
        sha256: String,
        clearSurface: Boolean = true,
    ): SurveyTerrainAsset {
        val asset = SurveyTerrainAsset(source, sha256, sha256)
        bareEarth = asset
        if (clearSurface) surface = null
        selectedKind = SurveyTerrainSourceKind.BARE_EARTH
        return asset
    }

    fun installStandaloneSurface(
        source: TerrainElevationSource,
        sha256: String,
    ): SurveyTerrainAsset {
        val asset = SurveyTerrainAsset(source, sha256)
        bareEarth = null
        surface = asset
        selectedKind = SurveyTerrainSourceKind.SURFACE_DSM
        return asset
    }

    fun installRelativeHeightSurface(
        source: TerrainElevationSource,
        relativeHeightSha256: String,
    ): SurveyTerrainAsset {
        val ground = requireNotNull(bareEarth) {
            appContext?.getString(R.string.load_bare_earth_dem_first) ?: "Load a bare-earth DEM/DTM first"
        }
        val asset = SurveyTerrainAsset(
            source = source,
            sha256 = compositeSurfaceSha256(ground.normalizedSha256, relativeHeightSha256),
            bareEarthBaseSha256 = ground.normalizedSha256,
        )
        surface = asset
        selectedKind = SurveyTerrainSourceKind.SURFACE_DSM
        return asset
    }

    fun clear() {
        bareEarth = null
        surface = null
    }

    companion object {
        /** History-independent digest of the two actual inputs used by a composite surface. */
        fun compositeSurfaceSha256(
            bareEarthSha256: String,
            relativeHeightSha256: String,
        ): String {
            fun normalized(value: String): String {
                require(value.matches(Regex("[0-9a-fA-F]{64}"))) { "terrain SHA-256 is invalid" }
                return value.lowercase()
            }
            val manifest = buildString {
                appendLine("openfly-surface-dsm-v1")
                appendLine("bare-earth-sha256=${normalized(bareEarthSha256)}")
                appendLine("relative-height-sha256=${normalized(relativeHeightSha256)}")
            }
            return SurveyTerrainPlanner.sha256(manifest.toByteArray(Charsets.UTF_8))
        }
    }
}
