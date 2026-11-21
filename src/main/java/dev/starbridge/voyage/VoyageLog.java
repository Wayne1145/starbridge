package dev.starbridge.voyage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-owned observation records and each vessel's most recent departure point on Earth. */
public final class VoyageLog extends SavedData {
    private final Set<UUID> observedMoon = new HashSet<>();
    private final Map<UUID, Vec3> earthBerths = new HashMap<>();
    public static VoyageLog get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(VoyageLog::load, VoyageLog::new, "starbridge_navigation");
    }
    boolean charted(UUID player) { return observedMoon.contains(player); }
    public void chartMoon(UUID player) { if (observedMoon.add(player)) setDirty(); }
    public Vec3 home(UUID core) { return earthBerths.get(core); }
    public void rememberHome(UUID core, Vec3 point) {
        if (!valid(point)) throw new IllegalArgumentException("Invalid home port");
        earthBerths.put(core, point); setDirty();
    }
    private static boolean valid(Vec3 p) {
        return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z)
                && Math.abs(p.x) < 30_000_000 && Math.abs(p.z) < 30_000_000 && Math.abs(p.y) < 8192;
    }
    static VoyageLog load(CompoundTag tag) {
        VoyageLog log = new VoyageLog();
        for (Tag v : tag.getList("Charts", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) v;
            if (row.hasUUID("Player")) log.observedMoon.add(row.getUUID("Player"));
        }
        for (Tag v : tag.getList("Ports", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) v;
            Vec3 point = new Vec3(row.getDouble("X"), row.getDouble("Y"), row.getDouble("Z"));
            if (row.hasUUID("Core") && row.contains("X", Tag.TAG_DOUBLE) && row.contains("Y", Tag.TAG_DOUBLE)
                    && row.contains("Z", Tag.TAG_DOUBLE) && valid(point)) log.earthBerths.put(row.getUUID("Core"), point);
        }
        return log;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag charts = new ListTag(), ports = new ListTag();
        for (UUID player : observedMoon) { CompoundTag row = new CompoundTag(); row.putUUID("Player", player); charts.add(row); }
        earthBerths.forEach((core, point) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("Core", core);
            row.putDouble("X", point.x); row.putDouble("Y", point.y); row.putDouble("Z", point.z); ports.add(row);
        });
        tag.put("Charts", charts); tag.put("Ports", ports); return tag;
    }
}
