package dev.starbridge.flight;

/**
 * Flight limits in blocks/second, blocks/second squared, blocks, blocks/second, and server ticks.
 * A speed limit constrains the requested trajectory: an already overspeed ship must brake physically.
 */
public record FlightConfig(
        double maxSpeed,
        double maxAcceleration,
        double positionTolerance,
        double velocityTolerance,
        long heartbeatTimeoutTicks) {
    public FlightConfig {
        positiveFinite(maxSpeed, "maxSpeed");
        positiveFinite(maxAcceleration, "maxAcceleration");
        positiveFinite(positionTolerance, "positionTolerance");
        if (!Double.isFinite(velocityTolerance) || velocityTolerance < 0 || velocityTolerance > maxSpeed) {
            throw new IllegalArgumentException("velocityTolerance must be finite and between zero and maxSpeed");
        }
        if (heartbeatTimeoutTicks < 1) {
            throw new IllegalArgumentException("heartbeatTimeoutTicks must be positive");
        }
    }

    public static FlightConfig defaults() {
        return new FlightConfig(12, 3, 1, 0.15, 100);
    }

    private static void positiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
