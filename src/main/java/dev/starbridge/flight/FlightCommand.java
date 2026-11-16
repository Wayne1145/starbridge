package dev.starbridge.flight;

import java.util.Objects;

/**
 * Requested net world-space acceleration in blocks/second squared, plus a desired unit facing vector.
 * A zero facing vector means preserve heading. The physical adapter must account for gravity, mass,
 * available thrust and fuel. This record neither moves a ship nor bypasses those checks.
 */
public record FlightCommand(
        Vec3d acceleration,
        Vec3d desiredForward,
        FlightMode mode,
        Event event,
        double distanceToTarget) {
    public enum Event { NONE, ARRIVED, HEARTBEAT_LOST }

    public FlightCommand {
        Objects.requireNonNull(acceleration, "acceleration");
        Objects.requireNonNull(desiredForward, "desiredForward");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(event, "event");
        if (!Double.isFinite(acceleration.length()) || !Double.isFinite(desiredForward.length())) {
            throw new IllegalArgumentException("Command vector magnitudes must be finite");
        }
        if (!Double.isFinite(distanceToTarget) || distanceToTarget < 0) {
            throw new IllegalArgumentException("distanceToTarget must be finite and non-negative");
        }
    }
}
