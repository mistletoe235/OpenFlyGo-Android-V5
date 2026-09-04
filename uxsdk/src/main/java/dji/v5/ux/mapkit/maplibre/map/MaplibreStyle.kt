package dji.v5.ux.mapkit.maplibre.map

/**
 * @author feel.feng
 * @time 2024/07/10 17:27
 * @description: 升级9.5.x后 style 被移除
 */
object MaplibreStyle {
 // Mainland-China flight UI uses a local MapLibre style backed by AMap raster tiles.
 const val MAPBOX_STREETS = "asset://amap_streets.json"
 const val SATELLITE_STREETS = "asset://amap_streets.json"
 const val SATELLITE = "asset://amap_satellite.json"
}
