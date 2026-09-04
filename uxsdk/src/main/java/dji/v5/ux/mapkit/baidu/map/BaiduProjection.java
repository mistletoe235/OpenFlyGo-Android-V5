package dji.v5.ux.mapkit.baidu.map;

import android.graphics.Point;

import com.baidu.mapapi.map.Projection;

import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.maps.DJIProjection;
import dji.v5.ux.mapkit.core.models.DJILatLng;

public final class BaiduProjection implements DJIProjection {
    private final Projection projection;

    public BaiduProjection(Projection projection) {
        this.projection = projection;
    }

    @Override
    public DJILatLng fromScreenLocation(Point point) {
        com.baidu.mapapi.model.LatLng value = projection.fromScreenLocation(point);
        return value == null ? null : BaiduUtils.fromLatLng(value);
    }

    @Override
    public Point toScreenLocation(DJILatLng location) {
        return projection.toScreenLocation(BaiduUtils.fromDJILatLng(location));
    }
}
