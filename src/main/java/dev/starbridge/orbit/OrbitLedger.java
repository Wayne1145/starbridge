package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.flight.Vec3d;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates and berths survive restart. An occupied helm, pressed key or unsupervised autopilot
 * never does.
 */
final class OrbitLedger extends SavedData {
    final Map<UUID, Leg> legs = new HashMap<>();
    final Map<UUID, Cabin> cabins = new HashMap<>();

    record Cabin(String dimension, BlockPos core, BlockPos min, BlockPos max) {
        boolean matches(ShipCoreBlockEntity actual) {
            return actual.getLevel() instanceof ServerLevel level
                    && level.dimension().location().toString().equals(dimension)
                    && core.equals(actual.getBlockPos());
        }

        StorageBounds bounds() {
            return new StorageBounds(
                    min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        }
    }

    static final class Leg {
        final UUID id, captain;
        /** Swapped only by an emergency return, which turns the ship back to its port of departure. */
        String from, to;
        OrbitalFlight.Pose pose;
        int reserve = -1;
        /** Ship rating captured at launch: drive cost multiplier and orbital performance. */
        double costFactor = 1, performance = 1;
        /** Hull integrity in percent; an unrepaired drive fault halves performance. */
        double integrity = OrbitEvents.FULL_INTEGRITY;
        boolean fault, emergency;
        /** Ticks held at this flight's distress beacon; claimed once. */
        int beaconHeld;
        boolean beaconClaimed;
        /** Distinguishes one flight from the next on the same route, e.g. for its distress beacon. */
        UUID voyage = UUID.randomUUID();
        HelmIssuer issuer = HelmIssuer.HELM;
        boolean cruise, braking, suspended = true;

        Leg(UUID id, UUID captain, String from, String to, OrbitalFlight.Pose pose) {
            this.id = id;
            this.captain = captain;
            this.from = from;
            this.to = to;
            this.pose = pose;
        }
    }

    private static double bounded(double value, double low, double high) {
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : low;
    }

    static OrbitLedger get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(OrbitLedger::load, OrbitLedger::new, "starbridge_orbits");
    }

    static OrbitLedger load(CompoundTag root) {
        var data = new OrbitLedger();
        for (Tag entry : root.getList("Legs", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) entry;
            String from = tag.getString("From"), to = tag.getString("To");
            if (!tag.hasUUID("Ship")
                    || !tag.hasUUID("Captain")
                    || from.isBlank()
                    || to.isBlank()
                    || from.length() > 96
                    || to.length() > 96
                    || from.equals(to)) continue;
            try {
                var pose =
                        new OrbitalFlight.Pose(
                                vector(tag, "p"),
                                vector(tag, "v"),
                                tag.getDouble("Yaw"),
                                tag.getDouble("Pitch"));
                var leg = new Leg(tag.getUUID("Ship"), tag.getUUID("Captain"), from, to, pose);
                if (tag.contains("Reserve", Tag.TAG_INT))
                    leg.reserve = Math.max(-1, tag.getInt("Reserve"));
                if (tag.contains("CostFactor")) leg.costFactor = bounded(tag.getDouble("CostFactor"), 0.1, ShipRating.MAX_MASS_FACTOR);
                if (tag.contains("Performance")) leg.performance = bounded(tag.getDouble("Performance"), ShipRating.MIN_PERFORMANCE, ShipRating.MAX_PERFORMANCE);
                if (tag.contains("Integrity")) leg.integrity = bounded(tag.getDouble("Integrity"), 0, OrbitEvents.FULL_INTEGRITY);
                leg.fault = tag.getBoolean("Fault");
                leg.emergency = tag.getBoolean("Emergency");
                leg.beaconClaimed = tag.getBoolean("BeaconClaimed");
                if (tag.hasUUID("Voyage")) leg.voyage = tag.getUUID("Voyage");
                data.legs.put(leg.id, leg);
            } catch (IllegalArgumentException ignored) {
                /* A malformed pose cannot grant control of another vessel. */
            }
        }
        for (Tag entry : root.getList("Cabins", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) entry;
            if (!tag.hasUUID("Ship")
                    || !tag.contains("Core", Tag.TAG_LONG)
                    || !tag.contains("Min", Tag.TAG_LONG)
                    || !tag.contains("Max", Tag.TAG_LONG)) continue;
            var cabin =
                    new Cabin(
                            tag.getString("Dimension"),
                            BlockPos.of(tag.getLong("Core")),
                            BlockPos.of(tag.getLong("Min")),
                            BlockPos.of(tag.getLong("Max")));
            try {
                HullSurvey.validate(cabin.bounds());
                data.cabins.put(tag.getUUID("Ship"), cabin);
            } catch (IllegalArgumentException ignored) {
                /* Never allocate a region from invalid persisted bounds. */
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        var savedLegs = new ListTag();
        for (var leg : legs.values()) {
            var tag = new CompoundTag();
            tag.putUUID("Ship", leg.id);
            tag.putUUID("Captain", leg.captain);
            tag.putString("From", leg.from);
            tag.putString("To", leg.to);
            tag.putInt("Reserve", leg.reserve);
            tag.putDouble("CostFactor", leg.costFactor);
            tag.putDouble("Performance", leg.performance);
            tag.putDouble("Integrity", leg.integrity);
            tag.putBoolean("Fault", leg.fault);
            tag.putBoolean("Emergency", leg.emergency);
            tag.putBoolean("BeaconClaimed", leg.beaconClaimed);
            tag.putUUID("Voyage", leg.voyage);
            putVector(tag, "p", leg.pose.position());
            putVector(tag, "v", leg.pose.velocity());
            tag.putDouble("Yaw", leg.pose.yaw());
            tag.putDouble("Pitch", leg.pose.pitch());
            savedLegs.add(tag);
        }
        var savedCabins = new ListTag();
        cabins.forEach(
                (id, cabin) -> {
                    var tag = new CompoundTag();
                    tag.putUUID("Ship", id);
                    tag.putString("Dimension", cabin.dimension);
                    tag.putLong("Core", cabin.core.asLong());
                    tag.putLong("Min", cabin.min.asLong());
                    tag.putLong("Max", cabin.max.asLong());
                    savedCabins.add(tag);
                });
        root.put("Legs", savedLegs);
        root.put("Cabins", savedCabins);
        return root;
    }

    private static Vec3d vector(CompoundTag tag, String key) {
        return new Vec3d(
                tag.getDouble(key + "x"), tag.getDouble(key + "y"), tag.getDouble(key + "z"));
    }

    private static void putVector(CompoundTag tag, String key, Vec3d v) {
        tag.putDouble(key + "x", v.x());
        tag.putDouble(key + "y", v.y());
        tag.putDouble(key + "z", v.z());
    }
}
