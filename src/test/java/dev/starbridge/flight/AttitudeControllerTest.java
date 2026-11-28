package dev.starbridge.flight;

import dev.starbridge.flight.AttitudeController.Quaternion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttitudeControllerTest {
    private static final double[] UNIT_INERTIA = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    @Test
    void uprightAndStillNeedsNoTorque() {
        Vec3d alpha = AttitudeController.angularAcceleration(Quaternion.IDENTITY, Vec3d.ZERO, 0);
        assertEquals(0, alpha.length(), 1e-12);
    }

    @Test
    void rolledShipIsPushedBackTowardUpright() {
        Quaternion rolled = Quaternion.axisAngle(new Vec3d(0, 0, 1), Math.toRadians(20));
        Vec3d alpha = AttitudeController.angularAcceleration(rolled, Vec3d.ZERO, 0);
        // Rolled +20° about Z: the correction must be about -Z.
        assertTrue(alpha.z() < 0, "expected negative roll correction, got " + alpha);
        assertEquals(0, alpha.y(), 1e-9);
    }

    @Test
    void spinningShipIsDamped() {
        Vec3d alpha = AttitudeController.angularAcceleration(Quaternion.IDENTITY, new Vec3d(0, 0.4, 0), 0);
        assertTrue(alpha.y() < 0);
    }

    @Test
    void yawCommandIsFollowedAndClamped() {
        Vec3d alpha = AttitudeController.angularAcceleration(Quaternion.IDENTITY, Vec3d.ZERO, 50);
        assertTrue(alpha.y() > 0);
        assertTrue(alpha.length() <= AttitudeController.MAX_ANGULAR_ACCELERATION + 1e-9);
    }

    @Test
    void invertedShipStillGetsACorrection() {
        Quaternion inverted = Quaternion.axisAngle(new Vec3d(1, 0, 0), Math.PI);
        assertTrue(AttitudeController.angularAcceleration(inverted, Vec3d.ZERO, 0).length() > 0);
    }

    @Test
    void torqueUsesRotatedInertia() {
        double[] inertia = {10, 0, 0, 0, 20, 0, 0, 0, 30};
        Vec3d torque = AttitudeController.torque(Quaternion.IDENTITY, inertia, new Vec3d(1, 1, 1));
        assertEquals(10, torque.x(), 1e-9);
        assertEquals(20, torque.y(), 1e-9);
        assertEquals(30, torque.z(), 1e-9);
        // Yawed 90°: body X now points along world -Z, so world Z sees the body X inertia.
        Quaternion yawed = Quaternion.axisAngle(new Vec3d(0, 1, 0), Math.PI / 2);
        Vec3d rotated = AttitudeController.torque(yawed, inertia, new Vec3d(0, 0, 1));
        assertEquals(10, rotated.z(), 1e-9);
        assertEquals(0, AttitudeController.torque(yawed, UNIT_INERTIA, Vec3d.ZERO).length(), 1e-12);
    }

    @Test
    void headingFollowsMinecraftConvention() {
        assertEquals(0, AttitudeController.headingDegrees(Quaternion.IDENTITY), 1e-9);
        Quaternion west = Quaternion.axisAngle(new Vec3d(0, 1, 0), -Math.PI / 2);
        assertEquals(90, AttitudeController.headingDegrees(west), 1e-6);
        assertEquals(20, AttitudeController.tiltDegrees(
                Quaternion.axisAngle(new Vec3d(1, 0, 0), Math.toRadians(20))), 1e-6);
    }
}
