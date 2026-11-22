package dev.starbridge.client.orbit;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitalFlight;

/** Top-down scope, with the bow upwards regardless of pitch. Positive closure means approaching. */
record TrafficBearing(int x, int y, double range, double closingSpeed, double altitude) {
    static TrafficBearing measure(Vec3d position, Vec3d velocity, double yaw, Vec3d contact, Vec3d otherVelocity, int radius) {
        Vec3d offset = contact.subtract(position);
        var aim = FlightCamera.at(yaw, 0).transform(offset);
        double horizontal = Math.hypot(aim.side(), aim.depth());
        // Expand nearby formations while keeping the full 5,000-unit detection radius on screen.
        double plot = radius * Math.sqrt(Math.min(1, horizontal / OrbitalFlight.CONTACT_RANGE));
        int x = horizontal == 0 ? 0 : (int) Math.round(plot * aim.side() / horizontal);
        int y = horizontal == 0 ? 0 : (int) Math.round(-plot * aim.depth() / horizontal);
        Vec3d relative = velocity.subtract(otherVelocity), direction = offset.normalized();
        double closing = 20 * (relative.x() * direction.x() + relative.y() * direction.y() + relative.z() * direction.z());
        return new TrafficBearing(x, y, offset.length(), closing, offset.y());
    }
}
