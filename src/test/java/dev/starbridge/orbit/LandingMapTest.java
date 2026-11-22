package dev.starbridge.orbit;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LandingMapTest {
    @Test void offsetsPreserveTheRecordedCenterAndExactPortAltitude() {
        Vec3 port = new Vec3(-1739.875, 93.25, 628.625);
        assertEquals(port, LandingMap.homeAnchor(port, 0, 0));
        assertEquals(new Vec3(-1800.875, 93.25, 760.625), LandingMap.homeAnchor(port, -61, 132));
        assertEquals(new Vec3(-1227.875, 93.25, 116.625), LandingMap.homeAnchor(port, 512, -512));
        assertThrows(IllegalArgumentException.class, () -> LandingMap.homeAnchor(port, Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> LandingMap.homeAnchor(port, 0, 513));
    }

    @Test void visibleSelectionTracksTheClickedPixelOnOddAndEvenSizedCharts() {
        for (int side = 52; side <= 180; side++) {
            assertEquals(-512, LandingMap.coordinate(0, side));
            assertEquals(512, LandingMap.coordinate(side - 1, side));
            assertEquals(0, LandingMap.coordinate((side - 1) / 2d, side));
            for (int pixel = 0; pixel < side; pixel++) {
                assertEquals(pixel, LandingMap.pixel(LandingMap.coordinate(pixel, side), side));
            }
        }
    }
}
