package dji.v5.ux.mapkit.baidu.annotations;

import androidx.annotation.ColorInt;

import com.baidu.mapapi.map.Circle;
import com.baidu.mapapi.map.Stroke;

import dji.v5.ux.mapkit.baidu.utils.BaiduUtils;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJICircle;

public final class BaiduCircle implements DJICircle {
    private final Circle circle;

    public BaiduCircle(Circle circle) { this.circle = circle; }

    @Override public void remove() { circle.remove(); }
    @Override public void setVisible(boolean value) { circle.setVisible(value); }
    @Override public boolean isVisible() { return circle.isVisible(); }
    @Override public void setCenter(DJILatLng value) { circle.setCenter(BaiduUtils.fromDJILatLng(value)); }
    @Override public DJILatLng getCenter() { return BaiduUtils.fromLatLng(circle.getCenter()); }
    @Override public void setRadius(double value) { circle.setRadius((int) Math.round(value)); }
    @Override public double getRadius() { return circle.getRadius(); }
    @Override public void setFillColor(@ColorInt int value) { circle.setFillColor(value); }
    @Override public int getFillColor() { return circle.getFillColor(); }
    @Override public void setStrokeColor(@ColorInt int value) { circle.setStroke(new Stroke(Math.round(getStrokeWidth()), value)); }
    @Override public int getStrokeColor() { return circle.getStroke() == null ? 0 : circle.getStroke().color; }
    @Override public void setZIndex(float value) { circle.setZIndex(Math.round(value)); }
    @Override public float getZIndex() { return circle.getZIndex(); }
    @Override public void setCircle(DJILatLng center, Double radius) { setCenter(center); setRadius(radius); }
    @Override public void setStrokeWidth(float value) { circle.setStroke(new Stroke(Math.round(value), getStrokeColor())); }
    @Override public float getStrokeWidth() { return circle.getStroke() == null ? 0f : circle.getStroke().strokeWidth; }
}
