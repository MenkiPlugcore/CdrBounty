package store.cadera.cdrbounty.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackerAccuracyTest {
    @Test
    void choosesOffsetByDistanceBand() {
        assertEquals(25, TrackerAccuracy.maxOffset(50, 300, 1000, 2000, 25, 50, 80, 120));
        assertEquals(25, TrackerAccuracy.maxOffset(300, 300, 1000, 2000, 25, 50, 80, 120));
        assertEquals(50, TrackerAccuracy.maxOffset(301, 300, 1000, 2000, 25, 50, 80, 120));
        assertEquals(50, TrackerAccuracy.maxOffset(1000, 300, 1000, 2000, 25, 50, 80, 120));
        assertEquals(80, TrackerAccuracy.maxOffset(1500, 300, 1000, 2000, 25, 50, 80, 120));
        assertEquals(120, TrackerAccuracy.maxOffset(2500, 300, 1000, 2000, 25, 50, 80, 120));
    }

    @Test
    void neverReturnsNegativeOffset() {
        assertEquals(0, TrackerAccuracy.maxOffset(10, 300, 1000, 2000, -25, -50, -80, -120));
        assertEquals(0, TrackerAccuracy.maxOffset(5000, 300, 1000, 2000, -25, -50, -80, -120));
    }
}
