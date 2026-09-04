package dji.v5.ux.mapkit.baidu.annotations;

import com.baidu.mapapi.map.Marker;

import dji.v5.ux.mapkit.baidu.map.BaiduMapDelegate;
import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;

public final class BaiduMarker extends DJIMarker {
    private final Marker marker;
    private final BaiduMapDelegate map;

    public BaiduMarker(Marker marker, BaiduMapDelegate map) {
        this.marker = marker;
        this.map = map;
    }

    @Override public void setPosition(DJILatLng value) { setPositionCache(value); marker.setPosition(BaiduUtils.fromDJILatLng(value)); }
    @Override public void setRotation(float value) {
        setRotationCache(value);
        marker.setRotate(BaiduUtils.fromDJIMarkerRotation(value));
    }
    @Override public void setIcon(DJIBitmapDescriptor value) { marker.setIcon(BaiduUtils.fromDJIBitmapDescriptor(value)); }
    @Override public void setAnchor(float u, float v) { marker.setAnchor(u, v); }
    @Override public void setTitle(String value) { marker.setTitle(value); }
    @Override public String getTitle() { return marker.getTitle(); }
    @Override public void setVisible(boolean value) { marker.setVisible(value); }
    @Override public boolean isVisible() { return marker.isVisible(); }
    @Override public void showInfoWindow() { marker.showInfoWindow(); }
    @Override public void hideInfoWindow() { marker.hideInfoWindow(); }
    @Override public boolean isInfoWindowShown() { return marker.isInfoWindowEnabled(); }
    @Override public void remove() { marker.remove(); map.onMarkerRemove(marker); }
    @Override public void setDraggable(boolean value) { marker.setDraggable(value); }
    @Override public boolean isDraggable() { return marker.isDraggable(); }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof BaiduMarker && marker.equals(((BaiduMarker) other).marker));
    }

    @Override public int hashCode() { return marker.hashCode(); }
}
