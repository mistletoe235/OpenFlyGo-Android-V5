package dji.v5.ux.map;

import java.util.ArrayList;
import java.util.List;

import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.v5.manager.aircraft.flysafe.info.FlyZoneInformation;
import dji.v5.manager.aircraft.flysafe.info.MultiPolygonFlyZoneInformation;

/** Copies render-relevant values: SDK lists and coordinates may be mutated in place. */
final class FlyZoneRenderSnapshot {
    private final List<Object> values;

    private FlyZoneRenderSnapshot(List<Object> values) { this.values = values; }

    static FlyZoneRenderSnapshot capture(List<FlyZoneInformation> zones) {
        List<Object> values = new ArrayList<>();
        values.add(zones.size());
        for (FlyZoneInformation zone : zones) {
            values.add(zone.getFlyZoneID());
            values.add(zone.getName());
            values.add(zone.getCategory());
            values.add(zone.getShape());
            values.add(zone.getFlyZoneType());
            point(values, zone.getCircleCenter());
            values.add(zone.getCircleRadius());
            values.add(zone.getLowerLimit());
            values.add(zone.getUpperLimit());
            List<MultiPolygonFlyZoneInformation> polygons = zone.getMultiPolygonFlyZoneInformation();
            values.add(polygons == null ? -1 : polygons.size());
            if (polygons == null) continue;
            for (MultiPolygonFlyZoneInformation polygon : polygons) {
                values.add(polygon.getFlyZoneID());
                values.add(polygon.getShape());
                values.add(polygon.getLimitedHeight());
                point(values, polygon.getCylinderCenter());
                values.add(polygon.getCylinderRadius());
                List<LocationCoordinate2D> points = polygon.getPolygonPoints();
                values.add(points == null ? -1 : points.size());
                if (points != null) for (LocationCoordinate2D p : points) point(values, p);
            }
        }
        return new FlyZoneRenderSnapshot(values);
    }

    private static void point(List<Object> values, LocationCoordinate2D point) {
        values.add(point == null ? null : point.getLatitude());
        values.add(point == null ? null : point.getLongitude());
    }

    @Override public boolean equals(Object other) {
        return other instanceof FlyZoneRenderSnapshot && values.equals(((FlyZoneRenderSnapshot) other).values);
    }

    @Override public int hashCode() { return values.hashCode(); }
}
