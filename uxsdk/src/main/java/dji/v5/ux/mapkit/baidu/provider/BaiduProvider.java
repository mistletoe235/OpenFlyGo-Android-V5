package dji.v5.ux.mapkit.baidu.provider;

import static dji.v5.ux.mapkit.core.Mapkit.MapProviderConstant.BAIDU_PROVIDER;

import android.content.Context;

import androidx.annotation.NonNull;

import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.OverlayUtil;

import dji.v5.ux.mapkit.baidu.map.BaiduMapView;
import dji.v5.ux.mapkit.core.Mapkit;
import dji.v5.ux.mapkit.core.MapkitOptions;
import dji.v5.ux.mapkit.core.maps.DJIMapViewInternal;
import dji.v5.ux.mapkit.core.places.IInternalPlacesClient;
import dji.v5.ux.mapkit.core.providers.MapProvider;

/** Official Baidu Map Android SDK provider for DJI UXSDK's neutral map contract. */
public final class BaiduProvider extends MapProvider {
    public BaiduProvider() {
        providerType = BAIDU_PROVIDER;
    }

    @Override
    protected DJIMapViewInternal requestMapView(@NonNull Context context,
                                                @NonNull MapkitOptions options) {
        Context appContext = context.getApplicationContext();
        SDKInitializer.setAgreePrivacy(appContext, true);
        SDKInitializer.initialize(appContext);
        SDKInitializer.setCoordType(CoordType.GCJ02);
        // SDK 8.2.0 Overlay 2.0 leaves Polygon native geometry/style children alive
        // after remove(). Use Baidu's supported legacy renderer before creating maps.
        // This changes rendering only, not fly-safe data or aircraft restrictions.
        OverlayUtil.setOverlayUpgrade(false);
        Mapkit.mapType(options.getMapType());
        Mapkit.mapProvider(providerType);
        return new BaiduMapView(context);
    }

    @Override
    protected IInternalPlacesClient requestGeocodingClient(Context context,
                                                            MapkitOptions options) {
        return null;
    }
}
