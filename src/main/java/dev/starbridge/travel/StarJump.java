package dev.starbridge.travel;

import dev.starbridge.content.ContentModule;
import dev.starbridge.content.ExpeditionService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rules for whole-ship star jumps between the Overworld and Survey Moon. The ship core performs
 * the jump; this class only decides where a ship may jump from and where it arrives.
 */
public final class StarJump {
    /** Minimum world Y before a ship may leave the Overworld. */
    public static final double OVERWORLD_EXIT_ALTITUDE = 250;
    /** Minimum world Y before a ship may leave Survey Moon. */
    public static final double MOON_EXIT_ALTITUDE = 150;
    private static final double MOON_ARRIVAL_Y = 170;
    private static final double OVERWORLD_ARRIVAL_Y = 270;
    private static final int BASE_COST = 250_000;

    private StarJump() {}

    public static Optional<ResourceKey<Level>> destination(ResourceKey<Level> from) {
        if (from.equals(Level.OVERWORLD)) return Optional.of(ContentModule.MOON);
        if (from.equals(ContentModule.MOON)) return Optional.of(Level.OVERWORLD);
        return Optional.empty();
    }

    public static double exitAltitude(ResourceKey<Level> from) {
        return from.equals(ContentModule.MOON) ? MOON_EXIT_ALTITUDE : OVERWORLD_EXIT_ALTITUDE;
    }

    /** FE for one jump: a fixed charge plus one FE per 40 kg of ship. */
    public static int cost(double massKg, double energyFactor) {
        double raw = (BASE_COST + Math.max(0, massKg) / 40.0) * energyFactor;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(raw));
    }

    /** Where the ship's reference point arrives. Records the Overworld departure for the return trip. */
    public static Vec3 arrival(ServerLevel from, ServerLevel to, long shipId, Vec3 departure) {
        MinecraftServer server = from.getServer();
        Returns returns = Returns.get(server);
        if (to.dimension().equals(ContentModule.MOON)) {
            if (from.dimension().equals(Level.OVERWORLD)) {
                returns.points.put(shipId, new double[]{departure.x, departure.z});
                returns.setDirty();
            }
            Vec3 outpost = ExpeditionService.outpostRendezvous(to);
            // Arrive high above open ground west of the outpost, clear of its dome.
            return new Vec3(outpost.x - 48, MOON_ARRIVAL_Y, outpost.z);
        }
        double[] point = returns.points.remove(shipId);
        if (point != null) {
            returns.setDirty();
            return new Vec3(point[0], OVERWORLD_ARRIVAL_Y, point[1]);
        }
        BlockPos spawn = to.getSharedSpawnPos();
        return new Vec3(spawn.getX() + 0.5, OVERWORLD_ARRIVAL_Y, spawn.getZ() + 0.5);
    }

    /** Overworld departure columns, so a return jump lands where the ship left. */
    static final class Returns extends SavedData {
        private static final String NAME = "starbridge_jump_returns";
        final Map<Long, double[]> points = new HashMap<>();

        static Returns get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Returns::load, Returns::new, NAME);
        }

        static Returns load(CompoundTag tag) {
            Returns returns = new Returns();
            for (Tag element : tag.getList("Points", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) element;
                double x = entry.getDouble("X"), z = entry.getDouble("Z");
                if (Double.isFinite(x) && Double.isFinite(z)) returns.points.put(entry.getLong("Ship"), new double[]{x, z});
            }
            return returns;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            points.forEach((ship, point) -> {
                CompoundTag entry = new CompoundTag();
                entry.putLong("Ship", ship);
                entry.putDouble("X", point[0]);
                entry.putDouble("Z", point[1]);
                list.add(entry);
            });
            tag.put("Points", list);
            return tag;
        }
    }
}
