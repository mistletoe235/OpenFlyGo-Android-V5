package dji.v5.ux.mapkit.baidu.annotations;

import androidx.annotation.ColorInt;

import com.baidu.mapapi.map.Polygon;
import com.baidu.mapapi.map.Stroke;
import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.List;

import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygon;

public final class BaiduPolygon implements DJIPolygon {
    private final Polygon polygon;

    public BaiduPolygon(Polygon polygon) { this.polygon = polygon; }

    @Override public void remove() { polygon.remove(); }
    @Override public boolean isVisible() { return polygon.isVisible(); }
    @Override public void setVisible(boolean value) { polygon.setVisible(value); }
    @Override public void setFillColor(@ColorInt int value) { polygon.setFillColor(value); }
    @Override public int getFillColor() { return polygon.getFillColor(); }
    @Override public void setStrokeColor(@ColorInt int value) { polygon.setStroke(new Stroke(Math.round(getStrokeWidth()), value)); }
    @Override public int getStrokeColor() { return polygon.getStroke() == null ? 0 : polygon.getStroke().color; }
    @Override public void setStrokeWidth(float value) { polygon.setStroke(new Stroke(Math.round(value), getStrokeColor())); }
    @Override public float getStrokeWidth() { return polygon.getStroke() == null ? 0f : polygon.getStroke().strokeWidth; }

    @Override
    public void setPoints(List<DJILatLng> points) {
        List<LatLng> converted = new ArrayList<>();
        for (DJILatLng point : points) converted.add(BaiduUtils.fromDJILatLng(point));
        polygon.setPoints(converted);
    }

    @Override
    public List<DJILatLng> getPoints() {
        List<DJILatLng> result = new ArrayList<>();
        for (LatLng point : polygon.getPoints()) result.add(BaiduUtils.fromLatLng(point));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof BaiduPolygon && polygon.equals(((BaiduPolygon) other).polygon));
    }

    @Override public int hashCode() { return polygon.hashCode(); }
}
