package dev.starbridge.client.orbit;

import static org.junit.jupiter.api.Assertions.*;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitalFlight;

import org.junit.jupiter.api.Test;

class FlightCameraTest {
    @Test
    void sharedWorldTargetRemainsInFrontAcrossYawWrapAndAcrossDeckPitch() {
        for (double heading : new double[] {-179.9, 0, 52, 179.9}) {
            for (double pitch : new double[] {-75, 0, 52}) {
                var camera = FlightCamera.at(heading, pitch);
                Vec3d target = OrbitalFlight.forward(heading, pitch).multiply(2000);
                var centered = camera.transform(target);
                assertEquals(160, centered.x(160, 100));
                assertEquals(90, centered.y(90, 100));
                assertEquals(2000, centered.depth(), 1e-8);
                assertTrue(camera.transform(target.multiply(-1)).depth() < 0);
                assertTrue(
                        camera.transform(target.add(camera.right().multiply(400))).x(160, 100)
                                > 160);
                assertTrue(camera.transform(target.add(camera.up().multiply(400))).y(90, 100) < 90);
            }
        }
    }
}
