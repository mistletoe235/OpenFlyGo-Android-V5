package dji.v5.ux.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import dji.v5.ux.mapkit.core.models.DJILatLng;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;

public class MapDisplayPolicyTest {
    @Test
    public void highRateTelemetryProducesAtMostFiveDisplayUpdatesPerSecond() {
        TestScheduler clock = new TestScheduler();
        PublishProcessor<Integer> source = PublishProcessor.create();
        TestSubscriber<Integer> display = MapDisplayPolicy.latestUpdates(source, clock, clock).test();
        for (int sequence = 0; sequence < 10000; sequence++) {
            source.onNext(sequence);
            clock.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        }
        display.assertNoErrors();
        assertEquals(50, display.values().size());
        assertEquals(Integer.valueOf(9999), display.values().get(49));
        display.cancel();
    }

    @Test
    public void blockedDisplayRetainsOnlyOneQueuedAndOneLatestSample() {
        TestScheduler clock = new TestScheduler();
        TestScheduler ui = new TestScheduler();
        PublishProcessor<Integer> source = PublishProcessor.create();
        TestSubscriber<Integer> display = MapDisplayPolicy.latestUpdates(source, clock, ui).test();
        for (int sequence = 0; sequence < 10000; sequence++) {
            source.onNext(sequence);
            clock.advanceTimeBy(10, TimeUnit.MILLISECONDS);
        }
        display.assertNoValues();
        ui.triggerActions();
        display.assertNoErrors();
        assertTrue(display.values().size() <= 2);
        assertEquals(Integer.valueOf(9999), display.values().get(display.values().size() - 1));
        display.cancel();
    }

    @Test
    public void disposalDropsPendingDisplayAndUnsubscribesTelemetry() {
        TestScheduler clock = new TestScheduler();
        TestScheduler ui = new TestScheduler();
        PublishProcessor<Integer> source = PublishProcessor.create();
        TestSubscriber<Integer> display = MapDisplayPolicy.latestUpdates(source, clock, ui).test();
        source.onNext(1);
        clock.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        display.cancel();
        ui.triggerActions();
        display.assertNoValues();
        assertFalse(source.hasSubscribers());
    }

    @Test
    public void stationaryPositionDoesNotNeedAnotherSdkUpdate() {
        DJILatLng position = new DJILatLng(31.0, 121.0);
        assertTrue(MapDisplayPolicy.samePosition(position, new DJILatLng(31.0, 121.0)));
        assertFalse(MapDisplayPolicy.samePosition(position, new DJILatLng(31.00001, 121.0)));
        assertFalse(MapDisplayPolicy.samePosition(null, position));
    }

    @Test
    public void rotationIgnoresDuplicatesAndWraparoundButKeepsTurns() {
        assertFalse(MapDisplayPolicy.rotationChanged(90f, 90f));
        assertFalse(MapDisplayPolicy.rotationChanged(-180f, 180f));
        assertFalse(MapDisplayPolicy.rotationChanged(359.9f, 0.1f));
        assertFalse(MapDisplayPolicy.rotationChanged(0f, Float.NaN));
        assertTrue(MapDisplayPolicy.rotationChanged(Float.NaN, 0f));
        assertTrue(MapDisplayPolicy.rotationChanged(0f, 0.5f));
        assertTrue(MapDisplayPolicy.rotationChanged(179f, -179f));
    }

    @Test
    public void longTrailRemainsBoundedAndPreservesStartAndCurrentPosition() {
        List<Integer> points = new ArrayList<>();
        for (int sequence = 0; sequence < 100000; sequence++) {
            points.add(sequence);
            MapDisplayPolicy.compactTrail(points);
            assertTrue(points.size() <= MapDisplayPolicy.MAX_TRAIL_POINTS);
            assertEquals(Integer.valueOf(0), points.get(0));
            assertEquals(Integer.valueOf(sequence), points.get(points.size() - 1));
        }
        for (int index = 1; index < points.size(); index++) {
            assertTrue(points.get(index) > points.get(index - 1));
        }
    }
}
