package dji.v5.ux.mapkit.baidu.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.baidu.mapapi.model.LatLng;

import org.junit.Test;

import dji.v5.ux.mapkit.core.models.DJILatLng;

public class BaiduUtilsTest {
    private static final double SHANGHAI_WGS_LATITUDE = 31.0253;
    private static final double SHANGHAI_WGS_LONGITUDE = 121.4371;
    private static final double SHANGHAI_GCJ_LATITUDE = 31.023328299998937;
    private static final double SHANGHAI_GCJ_LONGITUDE = 121.44170121326704;

    @Test
    public void shanghaiConversionWorksWithoutMapkitAreaCodeInitialization() {
        LatLng display = BaiduUtils.fromDJILatLng(
                new DJILatLng(SHANGHAI_WGS_LATITUDE, SHANGHAI_WGS_LONGITUDE));

        assertEquals(SHANGHAI_GCJ_LATITUDE, display.latitude, 1.0e-10);
        assertEquals(SHANGHAI_GCJ_LONGITUDE, display.longitude, 1.0e-10);
    }

    @Test
    public void shanghaiRoundTripUsesRefinedGcjInverse() {
        LatLng display = new LatLng(SHANGHAI_GCJ_LATITUDE, SHANGHAI_GCJ_LONGITUDE);

        DJILatLng restored = BaiduUtils.fromLatLng(display);

        assertEquals(SHANGHAI_WGS_LATITUDE, restored.latitude, 1.0e-9);
        assertEquals(SHANGHAI_WGS_LONGITUDE, restored.longitude, 1.0e-9);
    }

    @Test
    public void overseasCoordinatesRemainWgs84() {
        DJILatLng london = new DJILatLng(51.5074, -0.1278);

        LatLng display = BaiduUtils.fromDJILatLng(london);
        DJILatLng restored = BaiduUtils.fromLatLng(display);

        assertEquals(london.latitude, display.latitude, 0.0);
        assertEquals(london.longitude, display.longitude, 0.0);
        assertEquals(london.latitude, restored.latitude, 0.0);
        assertEquals(london.longitude, restored.longitude, 0.0);
    }

    @Test
    public void mainlandBoundaryDecisionUsesCoordinate() {
        assertFalse(BaiduUtils.outsideMainlandChina(SHANGHAI_WGS_LATITUDE, SHANGHAI_WGS_LONGITUDE));
        assertTrue(BaiduUtils.outsideMainlandChina(51.5074, -0.1278));
    }

    @Test
    public void nearbyOverseasCitiesInsideLegacyRectangleRemainWgs84() {
        double[][] overseas = new double[][]{
                {37.5665, 126.9780}, // Seoul
                {27.7172, 85.3240},  // Kathmandu
                {47.8864, 106.9057}, // Ulaanbaatar
                {21.0278, 105.8342}  // Hanoi
        };
        for (double[] coordinate : overseas) {
            DJILatLng source = new DJILatLng(coordinate[0], coordinate[1]);
            LatLng display = BaiduUtils.fromDJILatLng(source);
            assertTrue(BaiduUtils.outsideMainlandChina(coordinate[0], coordinate[1]));
            assertEquals(source.latitude, display.latitude, 0.0);
            assertEquals(source.longitude, display.longitude, 0.0);
        }
    }

    @Test
    public void representativeMainlandAndHainanCitiesUseGcj() {
        assertFalse(BaiduUtils.outsideMainlandChina(39.9042, 116.4074));
        assertFalse(BaiduUtils.outsideMainlandChina(43.8256, 87.6168));
        assertFalse(BaiduUtils.outsideMainlandChina(45.8038, 126.5349));
        assertFalse(BaiduUtils.outsideMainlandChina(20.0440, 110.1999));
    }

    @Test
    public void djiClockwiseMarkerHeadingIsConvertedToBaiduCounterClockwiseRotation() {
        assertEquals(-90.0f, BaiduUtils.fromDJIMarkerRotation(90.0f), 0.0f);
        assertEquals(113.5f, BaiduUtils.fromDJIMarkerRotation(-113.5f), 0.0f);
        assertEquals(0.0f, BaiduUtils.fromDJIMarkerRotation(0.0f), 0.0f);
    }
}
