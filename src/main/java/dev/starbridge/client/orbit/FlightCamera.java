package dev.starbridge.client.orbit;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitalFlight;

/**
 * Pinhole camera facing the orbital +Z axis at zero yaw. All ships use the same coordinate frame.
 */
record FlightCamera(Vec3d right, Vec3d up, Vec3d forward) {
    static FlightCamera at(double yaw, double pitch) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        return new FlightCamera(
                new Vec3d(Math.cos(a), 0, Math.sin(a)),
                new Vec3d(Math.sin(a) * Math.sin(b), Math.cos(b), -Math.cos(a) * Math.sin(b)),
                OrbitalFlight.forward(yaw, pitch));
    }

    record Aim(double side, double vertical, double depth) {
        int x(int center, double focal) {
            return center + (int) Math.round(side * focal / depth);
        }

        int y(int center, double focal) {
            return center - (int) Math.round(vertical * focal / depth);
        }
    }

    Aim transform(Vec3d relative) {
        return new Aim(dot(relative, right), dot(relative, up), dot(relative, forward));
    }

    private static double dot(Vec3d a, Vec3d b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }
}
