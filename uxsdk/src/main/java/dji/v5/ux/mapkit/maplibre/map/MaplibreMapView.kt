package dji.v5.ux.mapkit.maplibre.map

import android.content.Context
import dji.v5.ux.mapkit.core.Mapkit
import dji.v5.ux.mapkit.core.maps.DJIMap
import dji.v5.ux.mapkit.core.maps.DJIMapView
import dji.v5.ux.mapkit.core.maps.DJIMapViewInternal
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style

class MaplibreMapView @JvmOverloads constructor(
        context: Context,
        options: MapboxMapOptions = MapboxMapOptions.createFromAttributes(context)
) : MapView(context, options), DJIMapViewInternal {

    override fun getDJIMapAsync(callback: DJIMapView.OnDJIMapReadyCallback?) {
        getMapAsync { mapboxMap ->
            mapboxMap.uiSettings.isLogoEnabled = false
            mapboxMap.uiSettings.isAttributionEnabled = false
            // A local/raster style can finish before OnDidFinishLoadingMapListener is
            // registered. The previous ordering then never delivered map-ready and left
            // the widget blank. Style readiness is the actual requirement for markers,
            // polylines and camera updates, so complete exactly once from this callback.
            var delivered = false
            mapboxMap.setStyle(getMapboxStyle()) {
                if (!delivered) {
                    delivered = true
                    callback?.onDJIMapReady(MaplibreMapDelegateKt(mapboxMap, context, this))
                }
            }
        }
    }

    private fun getMapboxStyle(): String {
        return when (Mapkit.getMapType()) {
            DJIMap.MAP_TYPE_NORMAL -> MaplibreStyle.MAPBOX_STREETS
            DJIMap.MAP_TYPE_HYBRID -> MaplibreStyle.SATELLITE_STREETS
            DJIMap.MAP_TYPE_SATELLITE -> MaplibreStyle.SATELLITE
            else -> MaplibreStyle.MAPBOX_STREETS
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
