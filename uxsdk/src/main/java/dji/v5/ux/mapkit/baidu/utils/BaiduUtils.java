package dji.v5.ux.mapkit.baidu.utils;

import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.PolygonOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.map.Stroke;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;

import java.util.ArrayList;
import java.util.List;

import dji.v5.ux.mapkit.core.camera.DJICameraUpdate;
import dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor;
import dji.v5.ux.mapkit.core.models.DJICameraPosition;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.DJILatLngBounds;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygonOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;

/**
 * Converts MapKit's WGS84 models to the Baidu SDK's globally configured GCJ02 model.
 * Baidu keeps overseas coordinates in WGS84. The mainland decision is made from each
 * coordinate instead of DJI's process-global area code because the map can be initialized
 * before an aircraft is connected and before an area code is available.
 */
public final class BaiduUtils {
    private static final double SEMI_MAJOR_AXIS = 6378245.0;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;
    private static final int GCJ_INVERSE_REFINEMENT_STEPS = 6;
    // A deliberately conservative mainland outline. The common GCJ rectangle also
    // contains Seoul, Mongolia, Nepal and northern Vietnam, which silently shifts
    // overseas missions by hundreds of metres. Points close to an international
    // border are kept outside unless they are clearly on the mainland side.
    private static final double[][] MAINLAND_CHINA_OUTLINE = new double[][]{
            {53.56, 122.34}, {52.50, 120.00}, {49.50, 116.70}, {47.00, 116.50},
            {45.00, 114.00}, {43.60, 112.00}, {41.50, 110.50}, {42.50, 107.50},
            {41.60, 104.50}, {42.50, 101.50}, {42.80, 96.50}, {45.20, 95.00},
            {46.50, 90.00}, {48.00, 89.00}, {49.10, 87.80}, {48.20, 82.00},
            {45.00, 82.30}, {42.50, 80.20}, {40.00, 74.00}, {37.00, 74.50},
            {35.50, 78.00}, {33.00, 79.00}, {31.00, 80.00}, {29.00, 82.00},
            {27.80, 88.10}, {28.20, 92.50}, {28.00, 97.30}, {25.60, 98.20},
            {24.00, 97.60}, {21.10, 101.10}, {22.40, 103.40}, {21.50, 107.00},
            {20.90, 108.10}, {21.50, 108.80}, {21.50, 110.00}, {22.00, 113.50},
            {23.50, 117.50}, {25.50, 120.50}, {28.30, 121.80}, {31.80, 122.20},
            {34.50, 120.50}, {37.50, 122.70}, {40.00, 122.00}, {42.50, 124.50},
            {43.00, 129.00}, {44.50, 131.50}, {47.50, 134.80}, {49.50, 130.50},
            {52.00, 126.50}
    };
    private static final double[][] HAINAN_OUTLINE = new double[][]{
            {20.18, 110.72}, {19.20, 111.05}, {18.15, 110.58}, {18.05, 108.62},
            {19.15, 108.35}, {20.15, 109.25}
    };

    private BaiduUtils() { }

    public static LatLng fromDJILatLng(DJILatLng value) {
        double latitude = value.getLatitude();
        double longitude = value.getLongitude();
        if (outsideMainlandChina(latitude, longitude)) {
            return new LatLng(latitude, longitude);
        }
        double[] offset = gcjOffset(latitude, longitude);
        return new LatLng(latitude + offset[0], longitude + offset[1]);
    }

    public static DJILatLng fromLatLng(LatLng value) {
        if (outsideMainlandChina(value.latitude, value.longitude)) {
            return new DJILatLng(value.latitude, value.longitude);
        }
        // A single subtraction leaves a metre-scale residual in parts of mainland China.
        // Fixed-point refinement matches the mission-side ChinaCoordinateTransform.
        double latitude = value.latitude;
        double longitude = value.longitude;
        for (int index = 0; index < GCJ_INVERSE_REFINEMENT_STEPS; index++) {
            double[] offset = gcjOffset(latitude, longitude);
            latitude -= latitude + offset[0] - value.latitude;
            longitude -= longitude + offset[1] - value.longitude;
        }
        return new DJILatLng(latitude, longitude);
    }

    static boolean outsideMainlandChina(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return true;
        return !insideOutline(latitude, longitude, MAINLAND_CHINA_OUTLINE)
                && !insideOutline(latitude, longitude, HAINAN_OUTLINE);
    }

    private static boolean insideOutline(double latitude, double longitude, double[][] outline) {
        boolean inside = false;
        for (int index = 0, previous = outline.length - 1; index < outline.length; previous = index++) {
            double latitudeA = outline[index][0];
            double longitudeA = outline[index][1];
            double latitudeB = outline[previous][0];
            double longitudeB = outline[previous][1];
            if ((latitudeA > latitude) != (latitudeB > latitude)
                    && longitude < (longitudeB - longitudeA) * (latitude - latitudeA)
                    / (latitudeB - latitudeA) + longitudeA) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double[] gcjOffset(double latitude, double longitude) {
        double latitudeOffset = transformLatitude(longitude - 105.0, latitude - 35.0);
        double longitudeOffset = transformLongitude(longitude - 105.0, latitude - 35.0);
        double latitudeRadians = Math.toRadians(latitude);
        double sinLatitude = Math.sin(latitudeRadians);
        double magic = 1.0 - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude;
        double sqrtMagic = Math.sqrt(magic);
        latitudeOffset = latitudeOffset * 180.0
                / ((SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY_SQUARED))
                / (magic * sqrtMagic) * Math.PI);
        longitudeOffset = longitudeOffset * 180.0
                / (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(latitudeRadians) * Math.PI);
        return new double[]{latitudeOffset, longitudeOffset};
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(y * Math.PI)
                + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (160.0 * Math.sin(y / 12.0 * Math.PI)
                + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(x * Math.PI)
                + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (150.0 * Math.sin(x / 12.0 * Math.PI)
                + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return result;
    }

    public static MapStatusUpdate fromDJICameraUpdate(DJICameraUpdate update) {
        return fromDJICameraUpdate(update, 0, 0);
    }

    public static MapStatusUpdate fromDJICameraUpdate(
            DJICameraUpdate update,
            int viewportWidth,
            int viewportHeight) {
        if (update instanceof DJICameraUpdateFactory.CameraBoundsUpdate) {
            DJICameraUpdateFactory.CameraBoundsUpdate boundsUpdate =
                    (DJICameraUpdateFactory.CameraBoundsUpdate) update;
            DJILatLngBounds bounds = boundsUpdate.getBounds();
            LatLngBounds baiduBounds = new LatLngBounds.Builder()
                    .include(fromDJILatLng(bounds.getNortheast()))
                    .include(fromDJILatLng(bounds.getSouthwest()))
                    .build();
            if (boundsUpdate.getZoom() > 0) {
                DJILatLng center = new DJILatLng(
                        (bounds.getNortheast().getLatitude() + bounds.getSouthwest().getLatitude()) * 0.5,
                        (bounds.getNortheast().getLongitude() + bounds.getSouthwest().getLongitude()) * 0.5);
                float zoom = fitBoundsZoom(boundsUpdate, baiduBounds, viewportWidth, viewportHeight);
                return MapStatusUpdateFactory.newLatLngZoom(
                        fromDJILatLng(center), zoom);
            }
            if (boundsUpdate.getPadding() < 0) {
                return MapStatusUpdateFactory.newLatLngBounds(
                        baiduBounds,
                        boundsUpdate.getPaddingLeft(),
                        boundsUpdate.getPaddingTop(),
                        boundsUpdate.getPaddingRight(),
                        boundsUpdate.getPaddingBottom());
            }
            if (boundsUpdate.getWidth() == 0 || boundsUpdate.getHeight() == 0) {
                int padding = Math.max(0, boundsUpdate.getPadding());
                if (viewportWidth > padding * 2 && viewportHeight > padding * 2) {
                    return MapStatusUpdateFactory.newLatLngBounds(
                            baiduBounds,
                            viewportWidth - padding * 2,
                            viewportHeight - padding * 2);
                }
                return MapStatusUpdateFactory.newLatLngBounds(
                        baiduBounds, padding, padding, padding, padding);
            }
            return MapStatusUpdateFactory.newLatLngBounds(
                    baiduBounds,
                    boundsUpdate.getWidth(),
                    boundsUpdate.getHeight());
        }
        if (update instanceof DJICameraUpdateFactory.CameraPositionUpdate) {
            DJICameraUpdateFactory.CameraPositionUpdate position =
                    (DJICameraUpdateFactory.CameraPositionUpdate) update;
            MapStatus status = new MapStatus.Builder()
                    .target(fromDJILatLng(position.getTarget()))
                    .zoom(position.getZoom())
                    .overlook(-Math.abs(position.getTilt()))
                    .rotate(position.getBearing())
                    .build();
            return MapStatusUpdateFactory.newMapStatus(status);
        }
        return MapStatusUpdateFactory.newLatLng(new LatLng(0.0, 0.0));
    }

    private static float fitBoundsZoom(
            DJICameraUpdateFactory.CameraBoundsUpdate update,
            LatLngBounds bounds,
            int viewportWidth,
            int viewportHeight) {
        int horizontalPadding = update.getPadding() >= 0
                ? update.getPadding() * 2
                : update.getPaddingLeft() + update.getPaddingRight();
        int verticalPadding = update.getPadding() >= 0
                ? update.getPadding() * 2
                : update.getPaddingTop() + update.getPaddingBottom();
        int availableWidth = Math.max(1, viewportWidth - horizontalPadding);
        int availableHeight = Math.max(1, viewportHeight - verticalPadding);
        if (viewportWidth <= 0 || viewportHeight <= 0) return update.getZoom();

        double longitudeSpan = Math.abs(bounds.northeast.longitude - bounds.southwest.longitude);
        double northY = mercatorY(bounds.northeast.latitude);
        double southY = mercatorY(bounds.southwest.latitude);
        double longitudePixelsAtZoomZero = longitudeSpan / 360.0 * 256.0;
        double latitudePixelsAtZoomZero = Math.abs(northY - southY) / (2.0 * Math.PI) * 256.0;
        double widthZoom = longitudePixelsAtZoomZero > 0.0
                ? log2(availableWidth / longitudePixelsAtZoomZero)
                : update.getZoom();
        double heightZoom = latitudePixelsAtZoomZero > 0.0
                ? log2(availableHeight / latitudePixelsAtZoomZero)
                : update.getZoom();
        return (float) Math.max(3.0, Math.min(update.getZoom(), Math.min(widthZoom, heightZoom)));
    }

    private static double mercatorY(double latitude) {
        double clampedLatitude = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double radians = Math.toRadians(clampedLatitude);
        return Math.log(Math.tan(Math.PI * 0.25 + radians * 0.5));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public static DJICameraPosition fromMapStatus(MapStatus value) {
        return new DJICameraPosition.Builder()
                .target(fromLatLng(value.target))
                .zoom(value.zoom)
                .tilt(Math.abs(value.overlook))
                .bearing(value.rotate)
                .build();
    }

    public static BitmapDescriptor fromDJIBitmapDescriptor(DJIBitmapDescriptor descriptor) {
        switch (descriptor.getType()) {
            case BITMAP:
                return BitmapDescriptorFactory.fromBitmap(descriptor.getBitmap());
            case PATH_ABSOLUTE:
                return BitmapDescriptorFactory.fromPath(descriptor.getPath());
            case PATH_ASSET:
                return BitmapDescriptorFactory.fromAsset(descriptor.getPath());
            case PATH_FILEINPUT:
                return BitmapDescriptorFactory.fromFile(descriptor.getPath());
            case RESOURCE_ID:
                return BitmapDescriptorFactory.fromResource(descriptor.getResourceId());
            default:
                throw new AssertionError("Unsupported bitmap descriptor type");
        }
    }

    /** DJI MapKit rotations are clockwise; Baidu marker rotations are counter-clockwise. */
    public static float fromDJIMarkerRotation(float clockwiseDegrees) {
        return -clockwiseDegrees;
    }

    public static PolygonOptions fromDJIPolygonOptions(DJIPolygonOptions options) {
        List<LatLng> points = new ArrayList<>();
        for (DJILatLng point : options.getPoints()) points.add(fromDJILatLng(point));
        return new PolygonOptions()
                .stroke(new Stroke(Math.round(options.getStrokeWidth()), options.getStrokeColor()))
                .zIndex(Math.round(options.getZIndex()))
                .fillColor(options.getFillColor())
                .visible(options.isVisible())
                .points(points);
    }

    public static PolylineOptions fromDJIPolylineOptions(DJIPolylineOptions options) {
        List<LatLng> points = new ArrayList<>();
        for (DJILatLng point : options.getPoints()) points.add(fromDJILatLng(point));
        return new PolylineOptions()
                .width(options.getWidth())
                .zIndex(Math.round(options.getZIndex()))
                .color(options.getColor())
                .visible(options.isVisible())
                .isGeodesic(options.isGeodesic())
                .dottedLine(options.isDashed())
                .points(points);
    }
}
