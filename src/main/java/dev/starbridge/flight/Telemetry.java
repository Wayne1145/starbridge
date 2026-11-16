package dev.starbridge.flight;

import java.util.Objects;

/** Server-authoritative world position (blocks), linear velocity (blocks/second), and monotonic server tick. */
public record Telemetry(Vec3d position, Vec3d velocity, long tick) {
    public Telemetry {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        if (!Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("Velocity magnitude must be finite");
        }
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
    }
}
