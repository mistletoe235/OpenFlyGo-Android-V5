package dji.v5.ux.mapkit.baidu.map;

import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.UiSettings;

import dji.v5.ux.mapkit.core.maps.DJIUiSettings;

public final class BaiduUiSettings implements DJIUiSettings {
    private final UiSettings settings;
    private final MapView mapView;

    public BaiduUiSettings(UiSettings settings, MapView mapView) {
        this.settings = settings;
        this.mapView = mapView;
    }

    @Override public void setZoomControlsEnabled(boolean enabled) { mapView.showZoomControls(enabled); }
    @Override public void setScrollGesturesEnabled(boolean enabled) { settings.setScrollGesturesEnabled(enabled); }
    @Override public void setCompassEnabled(boolean enabled) { settings.setCompassEnabled(enabled); }
    @Override public void setMapToolbarEnabled(boolean enabled) { }
    @Override public void setMyLocationButtonEnabled(boolean enabled) { }
    @Override public void setRotateGesturesEnabled(boolean enabled) { settings.setRotateGesturesEnabled(enabled); }
    @Override public void setTiltGesturesEnabled(boolean enabled) { settings.setOverlookingGesturesEnabled(enabled); }
    @Override public void setZoomGesturesEnabled(boolean enabled) { settings.setZoomGesturesEnabled(enabled); }
}
