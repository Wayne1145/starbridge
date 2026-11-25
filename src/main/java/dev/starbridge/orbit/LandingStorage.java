package dev.starbridge.orbit;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Warm likely VS destination chunks with the hull still fixed in its cabin. */
final class LandingStorage implements AutoCloseable {
    private static final VsShipBackend VS = new VsShipBackend();
    private static final int MAX_PRELOADED_CHUNKS = 128;
    private static final TicketType<UUID> TICKET = TicketType.create("starbridge_assembly_storage", Comparator.<UUID>naturalOrder());

    private final ServerLevel source;
    private final UUID core;
    private final BlockPos plot;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private int requested;
    private int ready;
    private boolean closed;

    private LandingStorage(ServerLevel source, UUID core, StorageBounds hull, BlockPos plot) {
        this.source = source;
        this.core = core;
        this.plot = plot;
        int width = hull.maxX() - hull.minX() + 1;
        int depth = hull.maxZ() - hull.minZ() + 1;
        int x = plot.getX() - width / 2;
        int z = plot.getZ() - depth / 2;
        for (int dx = (x >> 4) - 1; dx <= ((x + width - 1) >> 4) + 1; dx++) {
            for (int dz = (z >> 4) - 1; dz <= ((z + depth - 1) >> 4) + 1; dz++) {
                var pos = new ChunkPos(dx, dz);
                if (VS.isShipyardChunk(source, dx, dz)) chunks.add(pos);
            }
        }
    }

    static LandingStorage start(ServerLevel source, UUID core, StorageBounds hull, int ordinal) {
        // Enable speculative chunk loading only during isolated landing checks.
        if (!Boolean.getBoolean("starbridge.test.reserveLandingStorage")) return null;
        int width = hull.maxX() - hull.minX() + 1;
        int depth = hull.maxZ() - hull.minZ() + 1;
        int columns = (width + 30) / 16;
        int rows = (depth + 30) / 16;
        if (columns * rows < 16 || (columns + 2) * (rows + 2) > MAX_PRELOADED_CHUNKS
                || !VS.usesPinnedAssembly()) return null;
        return new LandingStorage(source, core, hull, VS.previewAssemblyPlot(source, ordinal));
    }

    void tick(int requestsPerTick) {
        var cache = source.getChunkSource();
        while (ready < requested) {
            var chunk = chunks.get(ready);
            if (cache.getChunkNow(chunk.x, chunk.z) == null) return;
            ready++;
        }
        int end = Math.min(chunks.size(), requested + requestsPerTick);
        while (requested < end) cache.addRegionTicket(TICKET, chunks.get(requested++), 0, core);
    }

    void reportReadiness() {
        Starbridge.LOGGER.info("Predicted VS plot {} has {} of {} chunks ready at conversion", plot.toShortString(), ready, chunks.size());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        var cache = source.getChunkSource();
        for (int i = 0; i < requested; i++) cache.removeRegionTicket(TICKET, chunks.get(i), 0, core);
    }

    /** Clear unfinished entries from builds that pre-created an empty native ship. */
    static void recover(MinecraftServer server) {
        var journal = Journal.get(server);
        if (journal.recovered) return;
        journal.recovered = true;
        if (!VS.usesPinnedAssembly()) return;
        journal.allocated.values().removeIf(entry -> {
            var world = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.world())));
            if (world == null) return false;
            try {
                VS.discardEmptyAssembly(world, entry.ship());
                journal.setDirty();
                return true;
            } catch (RuntimeException failure) {
                Starbridge.LOGGER.warn("Cannot recover reserved ship {}", entry.ship(), failure);
                return false;
            }
        });
    }

    private record Entry(String world, long ship) {}

    private static final class Journal extends SavedData {
        private final Map<UUID, Entry> allocated = new LinkedHashMap<>();
        private boolean recovered;

        private static Journal get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Journal::load, Journal::new, "starbridge_assembly_storage");
        }

        private static Journal load(CompoundTag root) {
            var journal = new Journal();
            for (Tag value : root.getList("Plots", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag) value;
                journal.allocated.put(entry.getUUID("Core"), new Entry(entry.getString("World"), entry.getLong("Ship")));
            }
            return journal;
        }

        @Override public CompoundTag save(CompoundTag root) {
            var plots = new ListTag();
            for (var item : allocated.entrySet()) {
                var entry = new CompoundTag();
                entry.putUUID("Core", item.getKey());
                entry.putString("World", item.getValue().world());
                entry.putLong("Ship", item.getValue().ship());
                plots.add(entry);
            }
            root.put("Plots", plots);
            return root;
        }
    }
}
