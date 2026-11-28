package dev.starbridge.orbit;

import dev.starbridge.flight.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Things that can happen between planets. Everything here is visible in advance and avoidable:
 * the worst outcome is an emergency return to the port of departure, never a lost ship or crew.
 * Pure rules; {@link OrbitModule} applies them each tick.
 */
public final class OrbitEvents {
    /** Hull integrity in percent. */
    public static final double FULL_INTEGRITY = 100, SLOW_INTEGRITY = 30;
    /** Speed (navigation units per tick) at which debris fields can be crossed without damage. */
    public static final double SAFE_DEBRIS_SPEED = 4.0;
    /** Integrity lost per tick for each unit of speed above the safe speed inside a debris field. */
    public static final double DAMAGE_PER_EXCESS_SPEED = 0.06;
    /** Chance per powered tick that a drive fault occurs (about once per ten minutes of thrust). */
    public static final double FAULT_CHANCE = 1.0 / 12_000;
    public static final double FAULT_PERFORMANCE = 0.5;
    /** A beacon is claimed after holding within this range, nearly still, for {@link #BEACON_HOLD_TICKS}. */
    public static final double BEACON_RANGE = 60, BEACON_STILL_SPEED = 0.5;
    public static final int BEACON_HOLD_TICKS = 100;
    public static final double BEACON_CHANCE = 0.35;
    /** Debris and beacons keep this far from either body, so arrivals and departures stay clear. */
    private static final double BODY_CLEARANCE = 600;

    public record Debris(Vec3d center, double radius) {
        public boolean contains(Vec3d point) { return point.subtract(center).length() <= radius; }
    }

    private OrbitEvents() {}

    /** The same 0–3 debris fields every time this route is flown, placed along the direct line. */
    public static List<Debris> debris(String from, String to) {
        Vec3d start = OrbitalFlight.body(from), end = OrbitalFlight.body(to);
        Vec3d line = end.subtract(start);
        double length = line.length();
        var fields = new ArrayList<Debris>();
        if (length < BODY_CLEARANCE * 2 + 200) return fields;
        var random = new SplittableRandom(seed("debris", from, to));
        int count = random.nextInt(4);
        Vec3d side = perpendicular(line);
        Vec3d up = cross(line.normalized(), side);
        for (int i = 0; i < count; i++) {
            double t = 0.2 + random.nextDouble() * 0.6;
            double radius = 120 + random.nextDouble() * 140;
            Vec3d center = start.add(line.multiply(t))
                    .add(side.multiply((random.nextDouble() - 0.5) * 300))
                    .add(up.multiply((random.nextDouble() - 0.5) * 160));
            if (center.subtract(start).length() < BODY_CLEARANCE + radius
                    || center.subtract(end).length() < BODY_CLEARANCE + radius) continue;
            fields.add(new Debris(center, radius));
        }
        return List.copyOf(fields);
    }

    /** Integrity lost this tick at {@code position} moving at {@code speed}. */
    public static double damage(List<Debris> fields, Vec3d position, double speed) {
        if (speed <= SAFE_DEBRIS_SPEED) return 0;
        for (Debris field : fields) {
            if (field.contains(position)) return (speed - SAFE_DEBRIS_SPEED) * DAMAGE_PER_EXCESS_SPEED;
        }
        return 0;
    }

    /** Performance multiplier from hull damage and an unrepaired fault. */
    public static double condition(double integrity, boolean fault) {
        double factor = integrity < SLOW_INTEGRITY ? 0.5 : 1;
        return fault ? factor * FAULT_PERFORMANCE : factor;
    }

    public static boolean faultStrikes(double roll) { return roll < FAULT_CHANCE; }

    /** A distress beacon for this particular flight, or none; offset from the route so reaching it is a detour. */
    public static Optional<Vec3d> beacon(UUID flight, String from, String to) {
        var random = new SplittableRandom(seed("beacon", from + "|" + flight, to));
        if (random.nextDouble() >= BEACON_CHANCE) return Optional.empty();
        Vec3d start = OrbitalFlight.body(from), end = OrbitalFlight.body(to);
        Vec3d line = end.subtract(start);
        if (line.length() < BODY_CLEARANCE * 2 + 200) return Optional.empty();
        double t = 0.3 + random.nextDouble() * 0.4;
        double offset = (400 + random.nextDouble() * 500) * (random.nextBoolean() ? 1 : -1);
        return Optional.of(start.add(line.multiply(t)).add(perpendicular(line).multiply(offset)));
    }

    /** Ticks held at the beacon after this tick; reaching {@link #BEACON_HOLD_TICKS} claims it. */
    public static int holdAtBeacon(int held, Vec3d beacon, Vec3d position, double speed) {
        boolean holding = position.subtract(beacon).length() <= BEACON_RANGE && speed <= BEACON_STILL_SPEED;
        return holding ? held + 1 : 0;
    }

    private static Vec3d perpendicular(Vec3d line) {
        Vec3d direction = line.normalized();
        Vec3d axis = Math.abs(direction.y()) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        return cross(direction, axis).normalized();
    }

    private static Vec3d cross(Vec3d a, Vec3d b) {
        return new Vec3d(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static long seed(String kind, String from, String to) {
        UUID id = UUID.nameUUIDFromBytes((kind + ":" + from + "->" + to).getBytes(StandardCharsets.UTF_8));
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }
}
