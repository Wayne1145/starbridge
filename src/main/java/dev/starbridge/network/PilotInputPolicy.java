package dev.starbridge.network;

import dev.starbridge.flight.Vec3d;

/** Pure validation shared by the packet handler and its tests. Units are blocks and seconds. */
public final class PilotInputPolicy {
    public static final double MAX_ACCELERATION = 3.0;
    public static final double MAX_DISTANCE = 512.0;
    public static final int INPUT_INTERVAL_TICKS = 2;

    private PilotInputPolicy() {}

    public static Vec3d acceleration(double x, double y, double z) {
        return new Vec3d(x, y, z).clampLength(MAX_ACCELERATION);
    }

    /** Minecraft yaw zero faces +Z; positive right input points toward -X at that heading. */
    public static Vec3d cameraAcceleration(double yawDegrees, int forward, int right, int vertical) {
        if (!Double.isFinite(yawDegrees) || forward < -1 || forward > 1 || right < -1 || right > 1 || vertical < -1 || vertical > 1)
            throw new IllegalArgumentException("Invalid camera input");
        double yaw = Math.toRadians(yawDegrees % 360);
        return new Vec3d(-Math.sin(yaw) * forward - Math.cos(yaw) * right,
                vertical, Math.cos(yaw) * forward - Math.sin(yaw) * right)
                .normalized().multiply(MAX_ACCELERATION);
    }

    public static boolean withinRange(double px, double py, double pz, double sx, double sy, double sz) {
        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)
                || !Double.isFinite(sx) || !Double.isFinite(sy) || !Double.isFinite(sz)) return false;
        return Math.hypot(Math.hypot(px - sx, py - sy), pz - sz) <= MAX_DISTANCE;
    }

    public static boolean acceptsTick(long now, long previous) {
        return now >= previous && now - previous >= INPUT_INTERVAL_TICKS;
    }
}
