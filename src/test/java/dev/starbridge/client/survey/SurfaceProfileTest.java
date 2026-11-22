package dev.starbridge.client.survey;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SurfaceProfileTest {
    @Test
    void twoMeasuredCutsDistinguishAPitFromAWideCraterOrLongRavine() {
        var shaft =
                new SurfaceProfile(
                        40,
                        -30,
                        (x, z) -> Math.abs(x - 40) <= 3 && Math.abs(z + 30) <= 3 ? 43 : 68);
        assertEquals(SurfaceProfile.Shape.NARROW, shaft.shape);
        assertEquals(25, shaft.depth);
        assertEquals(7, shaft.eastWidth);
        assertEquals(7, shaft.northWidth);
        var hollow =
                new SurfaceProfile(0, 0, (x, z) -> Math.abs(x) <= 6 && Math.abs(z) <= 5 ? 50 : 68);
        assertEquals(SurfaceProfile.Shape.HOLLOW, hollow.shape);
        var ravine = new SurfaceProfile(0, 0, (x, z) -> Math.abs(x) <= 2 ? 45 : 68);
        assertEquals(SurfaceProfile.Shape.CHANNEL, ravine.shape);
    }

    @Test
    void unknownColumnCannotStandInForADeepOpening() {
        var partial = new SurfaceProfile(0, 0, (x, z) -> z == -3 ? SurfaceProfile.UNKNOWN : 52);
        assertEquals(SurfaceProfile.Shape.UNSURVEYED, partial.shape);
        assertEquals(SurfaceProfile.UNKNOWN, partial.northSouth[9]);
        assertEquals(52, partial.bottom);
    }
}
