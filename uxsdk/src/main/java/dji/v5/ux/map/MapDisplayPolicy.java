package dji.v5.ux.map;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dji.v5.ux.mapkit.core.models.DJILatLng;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;

public final class MapDisplayPolicy {
    public static final long UPDATE_INTERVAL_MILLIS = 200;
    public static final long TRAIL_INTERVAL_MILLIS = 1000;
    public static final int MAX_TRAIL_POINTS = 1024;

    private MapDisplayPolicy() { }

    public static <Value> Flowable<Value> latestUpdates(
            Flowable<Value> source, Scheduler samplingScheduler, Scheduler displayScheduler) {
        return source.onBackpressureLatest()
                .sample(UPDATE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS, samplingScheduler)
                .onBackpressureLatest()
                .observeOn(displayScheduler, false, 1);
    }

    public static boolean samePosition(DJILatLng previous, DJILatLng next) {
        return previous != null && next != null
                && Math.abs(previous.latitude - next.latitude) < 0.0000001
                && Math.abs(previous.longitude - next.longitude) < 0.0000001;
    }

    public static boolean rotationChanged(float previous, float next) {
        if (!Float.isFinite(next)) return false;
        float difference = ((next - previous) % 360f + 540f) % 360f - 180f;
        return !Float.isFinite(previous) || Math.abs(difference) >= 0.5f;
    }

    public static <Point> void compactTrail(List<Point> points) {
        if (points.size() <= MAX_TRAIL_POINTS) return;
        Point last = points.get(points.size() - 1);
        int retained = 1;
        for (int index = 2; index < points.size() - 1; index += 2) {
            points.set(retained++, points.get(index));
        }
        points.set(retained++, last);
        points.subList(retained, points.size()).clear();
    }
}
