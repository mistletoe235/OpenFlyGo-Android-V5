package dji.v5.ux.mapkit.baidu.map;

import android.content.Context;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import dji.v5.ux.mapkit.core.Mapkit;
import dji.v5.ux.mapkit.core.maps.DJIMapView;
import dji.v5.ux.mapkit.core.maps.DJIMapViewInternal;

public final class BaiduMapView extends FrameLayout implements DJIMapViewInternal {
    private final com.baidu.mapapi.map.MapView mapView;

    public BaiduMapView(Context context) {
        super(context);
        mapView = new com.baidu.mapapi.map.MapView(context);
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    public void getDJIMapAsync(@NonNull DJIMapView.OnDJIMapReadyCallback callback) {
        post(() -> {
            BaiduMapDelegate map = new BaiduMapDelegate(mapView.getMap(), mapView);
            map.setMapType(Mapkit.getMapType());
            callback.onDJIMapReady(map);
        });
    }

    @Override public void onCreate(Bundle state) { }
    @Override public void onStart() { }
    @Override public void onResume() { mapView.onResume(); }
    @Override public void onPause() { mapView.onPause(); }
    @Override public void onStop() { }
    @Override public void onDestroy() { mapView.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) { mapView.onSaveInstanceState(state); }
    @Override public void onLowMemory() { }
}
