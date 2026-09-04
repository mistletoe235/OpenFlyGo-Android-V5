package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
/** Role played by a dataset in a globally composited surface model. */
enum class SurfaceLayerRole {
    TERRAIN_ELEVATION,
    BUILDING_FOOTPRINT,
    BUILDING_HEIGHT_ABOVE_GROUND,
    ABSOLUTE_SURFACE_ELEVATION,
}

enum class SurfaceUseLevel {
    VISUALIZATION_ONLY,
    PREVIEW_AND_SIMULATION,
    REAL_FLIGHT,
}

data class GlobalSurfaceDataSource(
    val id: String,
    val displayName: String,
    val role: SurfaceLayerRole,
    val nominalResolutionMeters: Double?,
    val globalCoverage: Boolean,
    val license: String,
    val useLevel: SurfaceUseLevel,
    val notes: String,
)

/**
 * Product-wide source registry. Shanghai and other test locations select from
 * this catalog by RoI; they must never be hard-coded as special data sources.
 */
object GlobalSurfaceDataCatalog {
    val MAPZEN_AWS_TERRAIN = GlobalSurfaceDataSource(
        id = "mapzen-aws-terrain-tiles",
        displayName = "Mapzen Terrain Tiles on AWS Open Data",
        role = SurfaceLayerRole.TERRAIN_ELEVATION,
        nominalResolutionMeters = 30.0,
        globalCoverage = true,
        license = "Mixed open sources; attribution required",
        useLevel = SurfaceUseLevel.PREVIEW_AND_SIMULATION,
        notes = "Operational no-auth global bare-earth tiles; China is primarily SRTM-scale terrain.",
    )

    val COPERNICUS_DEM_GLO_30 = GlobalSurfaceDataSource(
        id = "copernicus-dem-glo-30",
        displayName = "Copernicus DEM GLO-30",
        role = SurfaceLayerRole.TERRAIN_ELEVATION,
        nominalResolutionMeters = 30.0,
        globalCoverage = true,
        license = "Copernicus DEM licence",
        useLevel = SurfaceUseLevel.PREVIEW_AND_SIMULATION,
        notes = "Global DSM-style terrain fallback; too coarse and dated for building clearance.",
    )

    val OVERTURE_BUILDINGS = GlobalSurfaceDataSource(
        id = "overture-buildings",
        displayName = "Overture Buildings",
        role = SurfaceLayerRole.BUILDING_FOOTPRINT,
        nominalResolutionMeters = null,
        globalCoverage = true,
        license = "ODbL 1.0",
        useLevel = SurfaceUseLevel.PREVIEW_AND_SIMULATION,
        notes = "Global footprints with sparse height attributes; missing height must remain unknown.",
    )

    val GLOBAL_BUILDING_ATLAS_HEIGHT = GlobalSurfaceDataSource(
        id = "global-building-atlas-height",
        displayName = "GlobalBuildingAtlas Height",
        role = SurfaceLayerRole.BUILDING_HEIGHT_ABOVE_GROUND,
        nominalResolutionMeters = 3.0,
        globalCoverage = true,
        license = "CC BY-NC 4.0",
        useLevel = SurfaceUseLevel.PREVIEW_AND_SIMULATION,
        notes = "ML-estimated relative building height; research/non-commercial and not flight-certified.",
    )

    fun userVerifiedDsm(resolutionMeters: Double) = GlobalSurfaceDataSource(
        id = "user-verified-dsm",
        displayName = "User verified surface DSM",
        role = SurfaceLayerRole.ABSOLUTE_SURFACE_ELEVATION,
        nominalResolutionMeters = resolutionMeters,
        globalCoverage = false,
        license = "User supplied",
        useLevel = SurfaceUseLevel.REAL_FLIGHT,
        notes = "Recent, locally verified DSM with buildings visible and aligned to the mission RoI.",
    )
}

data class GlobalSurfaceStack(
    val terrain: GlobalSurfaceDataSource,
    val buildingFootprints: GlobalSurfaceDataSource?,
    val buildingHeights: GlobalSurfaceDataSource?,
    val absoluteSurfaceOverride: GlobalSurfaceDataSource? = null,
) {
    val maximumUseLevel: SurfaceUseLevel
        get() = if (absoluteSurfaceOverride?.useLevel == SurfaceUseLevel.REAL_FLIGHT) {
            SurfaceUseLevel.REAL_FLIGHT
        } else {
            SurfaceUseLevel.PREVIEW_AND_SIMULATION
        }

    init {
        require(terrain.role == SurfaceLayerRole.TERRAIN_ELEVATION)
        require(buildingFootprints == null ||
            buildingFootprints.role == SurfaceLayerRole.BUILDING_FOOTPRINT)
        require(buildingHeights == null ||
            buildingHeights.role == SurfaceLayerRole.BUILDING_HEIGHT_ABOVE_GROUND)
        require(absoluteSurfaceOverride == null ||
            absoluteSurfaceOverride.role == SurfaceLayerRole.ABSOLUTE_SURFACE_ELEVATION)
    }
}

/** Adds a relative height layer to an absolute ground elevation source. */
class CompositeSurfaceElevationSource(
    private val terrain: TerrainElevationSource,
    private val heightAboveGround: TerrainElevationSource,
    displayName: String = "${terrain.info.displayName} + ${heightAboveGround.info.displayName}",
    context: Context? = null,
) : TerrainElevationSource {
    private val appContext = context?.applicationContext
    override val info = TerrainRasterInfo(
        displayName = displayName,
        width = minOf(terrain.info.width, heightAboveGround.info.width),
        height = minOf(terrain.info.height, heightAboveGround.info.height),
        epsg = 4326,
        noDataValue = null,
        pixelSizeX = maxOf(terrain.info.pixelSizeX, heightAboveGround.info.pixelSizeX),
        pixelSizeY = maxOf(terrain.info.pixelSizeY, heightAboveGround.info.pixelSizeY),
        minimumLatitude = maxOf(terrain.info.minimumLatitude, heightAboveGround.info.minimumLatitude),
        maximumLatitude = minOf(terrain.info.maximumLatitude, heightAboveGround.info.maximumLatitude),
        minimumLongitude = maxOf(terrain.info.minimumLongitude, heightAboveGround.info.minimumLongitude),
        maximumLongitude = minOf(terrain.info.maximumLongitude, heightAboveGround.info.maximumLongitude),
    )

    init {
        require(info.minimumLatitude < info.maximumLatitude &&
            info.minimumLongitude < info.maximumLongitude) {
            appContext?.getString(R.string.terrain_building_height_no_overlap)
                ?: "Terrain and building-height rasters have no overlapping coverage"
        }
    }

    override fun elevationMeters(latitude: Double, longitude: Double): Double {
        val ground = terrain.elevationMeters(latitude, longitude)
        val buildingHeight = heightAboveGround.elevationMeters(latitude, longitude)
        require(buildingHeight >= 0.0) {
            appContext?.getString(R.string.building_relative_height_negative)
                ?: "Relative building height cannot be negative"
        }
        return ground + buildingHeight
    }
}
