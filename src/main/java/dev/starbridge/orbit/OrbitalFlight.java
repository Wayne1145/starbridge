package dev.starbridge.orbit;

import dev.starbridge.flight.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A small shared interplanetary navigation space. Distances are navigation units, time is server
 * ticks.
 */
public final class OrbitalFlight {
    public static final double ENTRY = 180.0, ENTRY_SPEED = 3.2;
    public static final double CONTACT_RANGE = 5000.0;
    /** One fully powered acceleration and braking run between any supported ports. */
    public static final int CRUISE_ALLOWANCE = 1024;

    static final double THRUST = .12, BRAKE = .32, TOP_SPEED = 17.0;

    public record Pose(Vec3d position, Vec3d velocity, double yaw, double pitch) {
        public Pose {
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || Math.abs(pitch) > 90)
                throw new IllegalArgumentException("Invalid attitude");
        }
    }

    public record Controls(int thrust, int turn, int pitch, boolean brake) {
        public static final Controls NEUTRAL = new Controls(0, 0, 0, false);

        public Controls {
            if (Math.abs((long) thrust) > 1
                    || Math.abs((long) turn) > 1
                    || Math.abs((long) pitch) > 1)
                throw new IllegalArgumentException("Invalid flight input");
        }
    }

    public record Step(Pose pose, int energy) {}

    private OrbitalFlight() {}

    /**
     * Fixed coordinates per celestial body, independent of ship, session, and registry iteration
     * order.
     */
    public static Vec3d body(String id) {
        return switch (id) {
            case "home" -> Vec3d.ZERO;
            case "moon" -> new Vec3d(0, 0, 6400);
            case "mars" -> new Vec3d(8800, 740, 14200);
            case "belt" -> new Vec3d(-10500, -980, 15300);
            default -> {
                UUID seed = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
                double phase = (seed.getLeastSignificantBits() >>> 11) * 0x1.0p-53 * 2 * Math.PI;
                double radius = 9000 + (seed.getMostSignificantBits() & 65535) * .4;
                double height = ((seed.getMostSignificantBits() >>> 16) & 4095) - 2048;
                yield new Vec3d(Math.cos(phase) * radius, height, Math.sin(phase) * radius);
            }
        };
    }

    public static Pose launch(String from, String to) {
        Vec3d origin = body(from), ray = body(to).subtract(origin).normalized();
        return new Pose(origin.add(ray.multiply(ENTRY + 25)), Vec3d.ZERO, yaw(ray), pitch(ray));
    }

    public static Vec3d forward(double yaw, double pitch) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b));
    }

    public static double yaw(Vec3d v) {
        return Math.toDegrees(Math.atan2(-v.x(), v.z()));
    }

    public static double pitch(Vec3d v) {
        return Math.toDegrees(Math.atan2(v.y(), Math.hypot(v.x(), v.z())));
    }

    public static double wrap(double d) {
        return Math.IEEEremainder(d, 360.0);
    }

    public static boolean canEnter(Pose pose, Vec3d body) {
        return pose.position.subtract(body).length() <= ENTRY
                && pose.velocity.length() <= ENTRY_SPEED;
    }

    public static Step advance(
            Pose old, Controls input, Vec3d target, boolean cruise, boolean powered) {
        double yaw = wrap(old.yaw + input.turn * 2.1),
                pitch = clamp(old.pitch + input.pitch * 1.6, -85, 85);
        Vec3d delta = target.subtract(old.position);
        Vec3d acceleration = forward(yaw, pitch).multiply(input.thrust * THRUST);
        if (input.brake) acceleration = old.velocity.multiply(-1).clampLength(BRAKE);
        else if (cruise) {
            double distance = delta.length();
            Vec3d heading = delta.normalized();
            if (distance > 1) {
                yaw = wrap(old.yaw + clamp(wrap(yaw(heading) - old.yaw), -2.1, 2.1));
                pitch = clamp(old.pitch + clamp(pitch(heading) - old.pitch, -1.6, 1.6), -85, 85);
            }
            // Leave a full atmospheric approach, rather than overshooting the target at full speed.
            double targetSpeed =
                    Math.min(
                            TOP_SPEED, Math.sqrt(2 * THRUST * Math.max(0, distance - ENTRY * .65)));
            acceleration = heading.multiply(targetSpeed).subtract(old.velocity).clampLength(THRUST);
        }
        int fuel =
                acceleration.length() > 1e-5 && powered
                        ? (int) Math.ceil(acceleration.length() * 12)
                        : 0;
        Vec3d velocity =
                old.velocity.add(fuel > 0 ? acceleration : Vec3d.ZERO).clampLength(TOP_SPEED);
        return new Step(new Pose(old.position.add(velocity), velocity, yaw, pitch), fuel);
    }

    private static double clamp(double d, double low, double high) {
        return Math.max(low, Math.min(high, d));
    }
}
