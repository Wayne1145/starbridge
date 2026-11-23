package dev.starbridge.orbit;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
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
import java.util.function.Supplier;

/** Keep the fixed cabin intact while the confirmed surface survey prepares the VS storage plot. */
final class LandingStorage implements AutoCloseable {
    private static final VsShipBackend VS = new VsShipBackend();
    private static final int MAX_PRELOADED_CHUNKS = 128;
    private static final TicketType<UUID> TICKET = TicketType.create("starbridge_assembly_storage", Comparator.<UUID>naturalOrder());

    private final ServerLevel source;
    private final UUID core;
    private final VsShipBackend.AssemblyReservation reserved;
    private final Journal journal;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private int requested;
    private int ready;
    private boolean closed;

    private LandingStorage(ServerLevel source, UUID core, StorageBounds hull, Journal journal) {
        this.source = source;
        this.core = core;
        this.journal = journal;
        this.reserved = VS.reserveAssembly(source, hull);
        int width = hull.maxX() - hull.minX() + 1;
        int depth = hull.maxZ() - hull.minZ() + 1;
        int x = reserved.centerX() - width / 2;
        int z = reserved.centerZ() - depth / 2;
        for (int dx = (x >> 4) - 1; dx <= ((x + width - 1) >> 4) + 1; dx++) {
            for (int dz = (z >> 4) - 1; dz <= ((z + depth - 1) >> 4) + 1; dz++) {
                var pos = new ChunkPos(dx, dz);
                if (VS.isShipyardChunk(source, dx, dz)) chunks.add(pos);
            }
        }
        journal.allocated.put(core, new Entry(source.dimension().location().toString(), reserved.shipId()));
        journal.setDirty();
        source.getServer().overworld().getDataStorage().save();
    }

    static LandingStorage start(ServerLevel source, UUID core, StorageBounds hull) {
        // Keep the regular VS allocator on live worlds until the isolated landing checks pass.
        if (!Boolean.getBoolean("starbridge.test.reserveLandingStorage")) return null;
        int width = hull.maxX() - hull.minX() + 1;
        int depth = hull.maxZ() - hull.minZ() + 1;
        int columns = (width + 30) / 16;
        int rows = (depth + 30) / 16;
        if (columns * rows < 16 || (columns + 2) * (rows + 2) > MAX_PRELOADED_CHUNKS
                || !VS.supportsAssemblyReservation()) return null;
        var journal = Journal.get(source.getServer());
        if (journal.allocated.containsKey(core)) return null;
        return new LandingStorage(source, core, hull, journal);
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

    <T> T assemble(Supplier<T> operation) {
        Starbridge.LOGGER.info("Reserved ship storage has {} of {} chunks ready at conversion", ready, chunks.size());
        try {
            return reserved.allocation().duringAssembly(operation);
        } finally {
            Starbridge.LOGGER.info("Reserved ship storage {} used: {}", reserved.shipId(), reserved.allocation().used());
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        var cache = source.getChunkSource();
        for (int i = 0; i < requested; i++) cache.removeRegionTicket(TICKET, chunks.get(i), 0, core);
        try {
            if (!reserved.allocation().used()) VS.discardEmptyAssembly(source, reserved.shipId());
            journal.allocated.remove(core);
            journal.setDirty();
        } catch (RuntimeException failure) {
            Starbridge.LOGGER.warn("Reserved ship {} will be checked after restart", reserved.shipId(), failure);
        }
    }

    static void recover(MinecraftServer server) {
        var journal = Journal.get(server);
        if (journal.recovered) return;
        journal.recovered = true;
        if (!VS.supportsAssemblyReservation()) return;
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
