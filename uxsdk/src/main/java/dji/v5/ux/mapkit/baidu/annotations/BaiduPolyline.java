package dji.v5.ux.mapkit.baidu.annotations;

import androidx.annotation.ColorInt;

import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.List;

import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;

public final class BaiduPolyline implements DJIPolyline {
    private final Polyline polyline;

    public BaiduPolyline(Polyline polyline) { this.polyline = polyline; }

    @Override public void remove() { polyline.remove(); }
    @Override public void setWidth(float value) { polyline.setWidth(value); }
    @Override public float getWidth() { return polyline.getWidth(); }
    @Override public void setColor(@ColorInt int value) { polyline.setColor(value); }
    @Override public int getColor() { return polyline.getColor(); }
    @Override public void setZIndex(float value) { polyline.setZIndex(Math.round(value)); }
    @Override public float getZIndex() { return polyline.getZIndex(); }

    @Override
    public void setPoints(List<DJILatLng> points) {
        List<LatLng> converted = new ArrayList<>();
        for (DJILatLng point : points) converted.add(BaiduUtils.fromDJILatLng(point));
        polyline.setPoints(converted);
    }

    @Override
    public List<DJILatLng> getPoints() {
        List<DJILatLng> result = new ArrayList<>();
        for (LatLng point : polyline.getPoints()) result.add(BaiduUtils.fromLatLng(point));
        return result;
    }

    public DJIPolylineOptions getOptions() { return new DJIPolylineOptions(); }

    public void setOptions(DJIPolylineOptions options) {
        setPoints(options.getPoints());
        setWidth(options.getWidth());
        setColor(options.getColor());
        setZIndex(options.getZIndex());
        polyline.setVisible(options.isVisible());
        polyline.setGeodesic(options.isGeodesic());
        polyline.setDottedLine(options.isDashed());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof BaiduPolyline && polyline.equals(((BaiduPolyline) other).polyline));
    }

    @Override public int hashCode() { return polyline.hashCode(); }
}
