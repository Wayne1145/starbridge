package dev.starbridge.benchmark;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Monotonic plot allocation; canceled/interrupted plots are deliberately never reused. */
final class BenchmarkSavedData extends SavedData {
    private int nextPlot = 1;
    final Map<UUID, CompletedBuild> completed = new HashMap<>();
    final Map<UUID, ReturnPoint> returns = new HashMap<>();

    record ReturnPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
        static ReturnPoint of(ServerPlayer player) {
            return new ReturnPoint(player.level().dimension().location().toString(), player.getX(), player.getY(),
                    player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    static BenchmarkSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                BenchmarkSavedData::load, BenchmarkSavedData::new, "starbridge_benchmark");
    }

    BlockPos allocate() {
        if (nextPlot >= 1_000_000) throw new IllegalStateException("Shipyard plot limit reached");
        int plot = nextPlot++;
        setDirty();
        return new BlockPos((plot % 256) * 1024, 80, (plot / 256) * 1024);
    }

    private static BenchmarkSavedData load(CompoundTag tag) {
        BenchmarkSavedData data = new BenchmarkSavedData();
        data.nextPlot = Math.max(1, Math.min(1_000_000, tag.getInt("NextPlot")));
        for (Tag entry : tag.getList("Completed", Tag.TAG_COMPOUND)) {
            CompoundTag buildTag = (CompoundTag) entry;
            if (!buildTag.hasUUID("Owner")) continue;
            CompletedBuild build = CompletedBuild.load(buildTag);
            data.completed.put(build.owner(), build);
        }
        for (Tag item : tag.getList("Returns", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) item;
            if (!entry.hasUUID("Owner")) continue;
            data.returns.put(entry.getUUID("Owner"), new ReturnPoint(entry.getString("Dimension"),
                    entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"),
                    entry.getFloat("Yaw"), entry.getFloat("Pitch")));
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("Schema", 1);
        tag.putInt("NextPlot", nextPlot);
        ListTag builds = new ListTag();
        completed.values().forEach(build -> builds.add(build.save()));
        tag.put("Completed", builds);
        ListTag returnTags = new ListTag();
        returns.forEach((owner, point) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Owner", owner);
            entry.putString("Dimension", point.dimension());
            entry.putDouble("X", point.x());
            entry.putDouble("Y", point.y());
            entry.putDouble("Z", point.z());
            entry.putFloat("Yaw", point.yaw());
            entry.putFloat("Pitch", point.pitch());
            returnTags.add(entry);
        });
        tag.put("Returns", returnTags);
        return tag;
    }
}
