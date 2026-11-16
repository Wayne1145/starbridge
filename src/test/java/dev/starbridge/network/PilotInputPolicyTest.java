package dev.starbridge.network;

import dev.starbridge.flight.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PilotInputPolicyTest {
    @Test
    void diagonalAndExtremeInputsCannotExceedAccelerationLimit() {
        assertEquals(3.0, PilotInputPolicy.acceleration(3, 3, 3).length(), 1e-12);
        Vec3d huge = PilotInputPolicy.acceleration(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(3.0, huge.length(), 1e-12);
        assertEquals(Vec3d.ZERO, PilotInputPolicy.acceleration(0, 0, 0));
    }

    @Test
    void rejectsNonFiniteNetworkPayloads() {
        assertThrows(IllegalArgumentException.class, () -> PilotInputPolicy.acceleration(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PilotInputPolicy.acceleration(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> PilotInputPolicy.acceleration(0, 0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void rangeUsesSphericalWorldDistanceRatherThanPerAxisBounds() {
        assertTrue(PilotInputPolicy.withinRange(0, 0, 0, 512, 0, 0));
        assertFalse(PilotInputPolicy.withinRange(0, 0, 0, 512.01, 0, 0));
        assertFalse(PilotInputPolicy.withinRange(0, 0, 0, 400, 400, 0));
        assertTrue(PilotInputPolicy.withinRange(-1_000_000, 64, 2_000_000, -999_999, 64, 2_000_001));
        assertFalse(PilotInputPolicy.withinRange(Double.NaN, 0, 0, 0, 0, 0));
        assertFalse(PilotInputPolicy.withinRange(Double.MAX_VALUE, 0, 0, -Double.MAX_VALUE, 0, 0));
    }

    @Test
    void controlRateRequiresTwoServerTicksAndRejectsClockRollback() {
        assertTrue(PilotInputPolicy.acceptsTick(0, -2));
        assertFalse(PilotInputPolicy.acceptsTick(10, 10));
        assertFalse(PilotInputPolicy.acceptsTick(11, 10));
        assertTrue(PilotInputPolicy.acceptsTick(12, 10));
        assertFalse(PilotInputPolicy.acceptsTick(9, 10));
    }

    @Test
    void cameraControlsFollowMinecraftHeadingsAndNormalizeDiagonals() {
        Vec3d south = PilotInputPolicy.cameraAcceleration(0, 1, 0, 0);
        assertEquals(0, south.x(), 1e-12);
        assertEquals(3, south.z(), 1e-12);
        Vec3d west = PilotInputPolicy.cameraAcceleration(90, 1, 0, 0);
        assertEquals(-3, west.x(), 1e-12);
        assertEquals(0, west.z(), 1e-12);
        Vec3d rightFacingSouth = PilotInputPolicy.cameraAcceleration(0, 0, 1, 0);
        assertEquals(-3, rightFacingSouth.x(), 1e-12);
        assertEquals(0, rightFacingSouth.z(), 1e-12);
        assertEquals(3, PilotInputPolicy.cameraAcceleration(37, 1, 1, 1).length(), 1e-12);
        assertEquals(Vec3d.ZERO, PilotInputPolicy.cameraAcceleration(37, 0, 0, 0));
    }
}
