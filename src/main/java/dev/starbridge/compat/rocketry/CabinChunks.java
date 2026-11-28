package dev.starbridge.compat.rocketry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Prepares the cabin footprint while the original machine finishes its job. */
final class CabinChunks {
    private static final TicketType<ChunkPos> APPROACH =
            TicketType.create(
                    "starbridge_original_berth", Comparator.comparingLong(ChunkPos::toLong), 200);

    private CabinChunks() {}

    static BlockPos berthOrigin(ServerLevel level, ShipyardHull hull, int slot) {
        return new BlockPos(
                -24000 - (slot % 256) * 384,
                level.getMaxBuildHeight() - hull.box.sy() - 8,
                24000 + (slot / 256) * 384);
    }

    static void prepare(ServerLevel level, ShipyardHull hull, int slot) {
        BlockPos offset = berthOrigin(level, hull, slot).subtract(hull.box.min());
        prepare(level, hull.box, offset);
    }

    static void prepare(ServerLevel level, ShipyardHull.Box box, BlockPos offset) {
        Set<ChunkPos> positions = new LinkedHashSet<>();
        addFootprint(positions, box, offset);
        var chunks = level.getChunkSource();
        for (var pos : positions) chunks.addRegionTicket(APPROACH, pos, 0, pos);
    }

    private static void addFootprint(Set<ChunkPos> chunks, ShipyardHull.Box box, BlockPos offset) {
        var min = box.min().offset(offset);
        var max = box.max().offset(offset);
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
        }
    }

    /**
     * Queue the old and new hull chunks through the normal region writer, then fence their writes.
     */
    static void checkpoint(ServerLevel level, ShipyardHull.Box box, BlockPos offset) {
        Set<ChunkPos> positions = new LinkedHashSet<>();
        addFootprint(positions, box, BlockPos.ZERO);
        addFootprint(positions, box, offset);
        var regions = level.getChunkSource().chunkMap;
        for (var pos : positions) {
            var chunk = level.getChunk(pos.x, pos.z);
            level.getPoiManager().flush(pos);
            var nbt = ChunkSerializer.write(level, chunk);
            MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, nbt));
            regions.write(pos, nbt);
        }
        // Keep each chunk's dirty flag intact so subsequent player and machine changes still
        // autosave.
        regions.flushWorker();
    }
}
