package dji.v5.ux.mapkit.baidu.map;

import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.Circle;
import com.baidu.mapapi.map.CircleOptions;
import com.baidu.mapapi.map.InfoWindow;
import com.baidu.mapapi.map.MapPoi;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.Polygon;
import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.map.Stroke;
import com.baidu.mapapi.model.LatLng;

import java.util.HashMap;
import java.util.Map;

import dji.v5.ux.mapkit.baidu.annotations.BaiduCircle;
import dji.v5.ux.mapkit.baidu.annotations.BaiduMarker;
import dji.v5.ux.mapkit.baidu.annotations.BaiduPolygon;
import dji.v5.ux.mapkit.baidu.annotations.BaiduPolyline;
import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.callback.MapScreenShotListener;
import dji.v5.ux.mapkit.core.callback.OnCameraChangeListener;
import dji.v5.ux.mapkit.core.callback.OnMapTypeLoadedListener;
import dji.v5.ux.mapkit.core.camera.DJICameraUpdate;
import dji.v5.ux.mapkit.core.maps.DJIBaseMap;
import dji.v5.ux.mapkit.core.maps.DJIMap;
import dji.v5.ux.mapkit.core.maps.DJIProjection;
import dji.v5.ux.mapkit.core.maps.DJIUiSettings;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor;
import dji.v5.ux.mapkit.core.models.DJICameraPosition;
import dji.v5.ux.mapkit.core.models.annotations.DJICircle;
import dji.v5.ux.mapkit.core.models.annotations.DJICircleOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIGroupCircle;
import dji.v5.ux.mapkit.core.models.annotations.DJIGroupCircleOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygon;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygonOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;

public final class BaiduMapDelegate extends DJIBaseMap implements
        BaiduMap.OnMarkerClickListener,
        BaiduMap.OnMapClickListener,
        BaiduMap.OnMapLongClickListener,
        BaiduMap.OnMarkerDragListener {
    private static final String TAG = "OpenFlyV5BaiduMap";
    private final BaiduMap map;
    private final MapView mapView;
    private final Map<Marker, BaiduMarker> markerMap = new HashMap<>();
    private Runnable pendingBoundsAnimation;
    private boolean awaitingBoundsResult;

    public BaiduMapDelegate(BaiduMap map, MapView mapView) {
        this.map = map;
        this.mapView = mapView;
        map.setOnMarkerClickListener(this);
        map.setOnMapClickListener(this);
        map.setOnMapLongClickListener(this);
        map.setOnMarkerDragListener(this);
        installCameraListener();
    }

    private void installCameraListener() {
        map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener() {
            @Override public void onMapStatusChangeStart(MapStatus status) { }
            @Override public void onMapStatusChangeStart(MapStatus status, int reason) { }
            @Override public void onMapStatusChange(MapStatus status) {
                BaiduMapDelegate.this.onCameraChange(BaiduUtils.fromMapStatus(status));
            }
            @Override public void onMapStatusChangeFinish(MapStatus status) {
                if (awaitingBoundsResult) {
                    awaitingBoundsResult = false;
                    Log.i(TAG, "bounds settled target=" + status.target.latitude + ","
                            + status.target.longitude + " zoom=" + status.zoom
                            + " map=" + mapView.getWidth() + "x" + mapView.getHeight());
                }
                BaiduMapDelegate.this.onCameraChangeFinish(BaiduUtils.fromMapStatus(status));
            }
        });
    }

    @NonNull
    @Override
    public DJIMarker addMarker(DJIMarkerOptions options) {
        if (options.getPosition() == null) {
            throw new IllegalArgumentException("DJIMarkerOptions parameter must have position set");
        }
        MarkerOptions baiduOptions = new MarkerOptions()
                .draggable(options.getDraggable())
                .position(BaiduUtils.fromDJILatLng(options.getPosition()))
                .anchor(options.getAnchorU(), options.getAnchorV())
                .rotate(BaiduUtils.fromDJIMarkerRotation(options.getRotation()))
                .zIndex(Math.round(options.getZIndex()))
                .visible(options.getVisible())
                .title(options.getTitle())
                .flat(options.isFlat());
        DJIBitmapDescriptor icon = options.getIcon();
        if (icon != null) baiduOptions.icon(BaiduUtils.fromDJIBitmapDescriptor(icon));
        Marker marker = (Marker) map.addOverlay(baiduOptions);
        BaiduMarker result = new BaiduMarker(marker, this);
        result.setPositionCache(options.getPosition());
        result.setRotationCache(options.getRotation());
        markerMap.put(marker, result);
        return result;
    }

    @Override public Object getMap() { return map; }
    @Override public DJICameraPosition getCameraPosition() { return BaiduUtils.fromMapStatus(map.getMapStatus()); }
    @Override
    public void animateCamera(DJICameraUpdate update) {
        if (!(update instanceof dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory.CameraBoundsUpdate)) {
            map.animateMapStatus(BaiduUtils.fromDJICameraUpdate(update));
            return;
        }
        dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory.CameraBoundsUpdate boundsUpdate =
                (dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory.CameraBoundsUpdate) update;
        if (pendingBoundsAnimation != null) {
            mapView.removeCallbacks(pendingBoundsAnimation);
        }
        pendingBoundsAnimation = () -> {
            pendingBoundsAnimation = null;
            Log.i(TAG, "apply bounds ne="
                    + boundsUpdate.getBounds().getNortheast().getLatitude() + ","
                    + boundsUpdate.getBounds().getNortheast().getLongitude()
                    + " sw=" + boundsUpdate.getBounds().getSouthwest().getLatitude() + ","
                    + boundsUpdate.getBounds().getSouthwest().getLongitude()
                    + " map=" + mapView.getWidth() + "x" + mapView.getHeight()
                    + " padding=" + boundsUpdate.getPadding());
            awaitingBoundsResult = true;
            map.animateMapStatus(BaiduUtils.fromDJICameraUpdate(
                    update, mapView.getWidth(), mapView.getHeight()));
        };
        mapView.post(pendingBoundsAnimation);
    }
    @Override public void moveCamera(@NonNull DJICameraUpdate update) { map.setMapStatus(BaiduUtils.fromDJICameraUpdate(update)); }

    @Override
    public void setOnCameraChangeListener(OnCameraChangeListener listener) {
        if (listener != null && !onCameraChangeListeners.contains(listener)) {
            onCameraChangeListeners.add(listener);
        }
        installCameraListener();
    }

    @Override
    public void removeAllOnCameraChangeListeners() {
        onCameraChangeListeners.clear();
        map.setOnMapStatusChangeListener(null);
    }

    @Override
    public void setInfoWindowAdapter(InfoWindowAdapter adapter) {
        if (adapter == null) {
            map.setInfoWindowAdapter(null);
            return;
        }
        map.setInfoWindowAdapter(new com.baidu.mapapi.map.InfoWindowAdapter() {
            @Override public View getInfoWindowView(Marker marker) {
                return adapter.getInfoWindow(markerMap.get(marker));
            }
            @Override public int getInfoWindowViewYOffset() { return 0; }
            @Override public InfoWindow getInfoWindow(Marker marker) { return null; }
        });
    }

    @NonNull
    @Override
    public DJIPolyline addPolyline(DJIPolylineOptions options) {
        Polyline polyline = (Polyline) map.addOverlay(BaiduUtils.fromDJIPolylineOptions(options));
        return new BaiduPolyline(polyline);
    }

    @NonNull
    @Override
    public DJIPolygon addPolygon(DJIPolygonOptions options) {
        Polygon polygon = (Polygon) map.addOverlay(BaiduUtils.fromDJIPolygonOptions(options));
        return new BaiduPolygon(polygon);
    }

    @Nullable @Override public DJICircle addMarkerCircle(DJICircleOptions options) { return addSingleCircle(options); }
    @Nullable @Override public DJIGroupCircle addGroupCircle(DJIGroupCircleOptions options) { return null; }

    @Nullable
    @Override
    public DJICircle addSingleCircle(DJICircleOptions options) {
        if (options.getRadius() <= 0 || options.getCenter() == null) return null;
        CircleOptions baiduOptions = new CircleOptions()
                .center(BaiduUtils.fromDJILatLng(options.getCenter()))
                .radius((int) Math.round(options.getRadius()))
                .stroke(new Stroke(Math.round(options.getStrokeWidth()), options.getStrokeColor()))
                .fillColor(options.getFillColor());
        Circle circle = (Circle) map.addOverlay(baiduOptions);
        return new BaiduCircle(circle);
    }

    @Override
    public void setMapType(MapType type, OnMapTypeLoadedListener listener) {
        setMapType(type);
        if (listener != null) listener.onMapTypeLoaded();
    }

    @Override
    public void setMapType(int type) {
        map.setMapType(type == DJIMap.MAP_TYPE_SATELLITE || type == DJIMap.MAP_TYPE_HYBRID
                ? BaiduMap.MAP_TYPE_SATELLITE : BaiduMap.MAP_TYPE_NORMAL);
    }

    @Override
    public void setMapType(MapType type) {
        setMapType(type == MapType.NORMAL ? DJIMap.MAP_TYPE_NORMAL
                : type == MapType.SATELLITE ? DJIMap.MAP_TYPE_SATELLITE
                : DJIMap.MAP_TYPE_HYBRID);
    }

    @Override public DJIUiSettings getUiSettings() { return new BaiduUiSettings(map.getUiSettings(), mapView); }
    @Override public boolean onMarkerClick(Marker marker) { BaiduMarker value = markerMap.get(marker); return value != null && onMarkerClick(value); }
    @Override public void onMapClick(LatLng point) { onMapClick(BaiduUtils.fromLatLng(point)); }
    @Override
    public void onMapPoiClick(MapPoi point) {
        // Baidu uses a separate callback when the finger lands on a labelled
        // road/building/POI. Forward it through the provider-neutral map-click
        // path so consumers such as the survey editor receive every map tap.
        if (point != null && point.getPosition() != null) {
            onMapClick(point.getPosition());
        }
    }
    @Override public void onMapLongClick(LatLng point) { onMapLongClick(BaiduUtils.fromLatLng(point)); }
    @Override public void onMarkerDragStart(Marker marker) { BaiduMarker value = markerMap.get(marker); if (value != null) onMarkerDragStart(value); }

    @Override
    public void onMarkerDrag(Marker marker) {
        BaiduMarker value = markerMap.get(marker);
        if (value != null) {
            value.setPositionCache(BaiduUtils.fromLatLng(marker.getPosition()));
            onMarkerDrag(value);
        }
    }

    @Override
    public void onMarkerDragEnd(Marker marker) {
        BaiduMarker value = markerMap.get(marker);
        if (value != null) {
            value.setPositionCache(BaiduUtils.fromLatLng(marker.getPosition()));
            onMarkerDragEnd(value);
        }
    }

    @Override
    public void snapshot(MapScreenShotListener callback) {
        map.snapshot(callback::onMapScreenShot);
    }

    @Override public DJIProjection getProjection() { return new BaiduProjection(map.getProjection()); }
    @Override public void clear() { map.clear(); markerMap.clear(); }
    public void onMarkerRemove(Marker marker) { markerMap.remove(marker); }
}
