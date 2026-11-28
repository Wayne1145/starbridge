package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Snapshots real chunks on the server thread, then waits for Minecraft's own ordered region writers between ticks. */
final class PassageStorage {
    private static final String STAMP = "starbridge:passage_checkpoint";
    // Chunk reads may run in a worker. Keeping the stamp on the actual loaded chunk also preserves it on vanilla autosave.
    private static final Map<LevelChunk, UUID> stamps = Collections.synchronizedMap(new WeakHashMap<>());
    private final ServerLevel source, destination;
    private final UUID passage;
    private final int firstX, firstZ, columns, rows;
    private int cursor;
    private final ArrayList<CompletableFuture<Void>> writes = new ArrayList<>();
    private CompletableFuture<Void> barrier;

    PassageStorage(ServerLevel source, ServerLevel destination, StorageBounds bounds, UUID passage) {
        this.passage = passage;
        this.source = source;
        this.destination = destination;
        firstX = bounds.minX() >> 4;
        firstZ = bounds.minZ() >> 4;
        columns = (bounds.maxX() >> 4) - firstX + 1;
        rows = (bounds.maxZ() >> 4) - firstZ + 1;
    }

    boolean advance() {
        if (barrier == null) {
            // Both sides stay ticketed. Bound time spent serializing even when several decks cross chunk boundaries.
            long deadline = System.nanoTime() + 3_000_000L;
            for (int budget = 0; budget < 4 && cursor < columns * rows * 2; budget++) {
                ServerLevel level = (cursor & 1) == 0 ? destination : source;
                int index = cursor / 2;
                LevelChunk chunk = level.getChunkSource().getChunkNow(firstX + index % columns, firstZ + index / columns);
                if (chunk == null) return false;
                writes.add(save(level, chunk));
                cursor++;
                if (System.nanoTime() >= deadline) break;
            }
            if (cursor < columns * rows * 2) return false;
            var stored = CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new));
            var out = worker(source);
            var in = worker(destination);
            // Keep the journal pending until both region files (including the exact debit) are durable.
            // Never block the Minecraft thread or invoke a save of unrelated world chunks.
            barrier = stored.thenCompose(ignored -> CompletableFuture.allOf(out.synchronize(true), in.synchronize(true)));
        }
        if (!barrier.isDone()) return false;
        barrier.join(); // Already completed; propagates a disk error so the caller can retry with the receipt intact.
        return true;
    }

    private static IOWorker worker(ServerLevel level) {
        if (level.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker worker) return worker;
        throw new IllegalStateException("Missing region writer for " + level.dimension().location());
    }

    static void loaded(ChunkDataEvent.Load event) {
        if (event.getChunk() instanceof LevelChunk chunk && event.getData().hasUUID(STAMP))
            stamps.put(chunk,event.getData().getUUID(STAMP));
    }

    static void saving(ChunkDataEvent.Save event) {
        if (event.getChunk() instanceof LevelChunk chunk) {
            UUID current=stamps.get(chunk);
            if (current!=null) event.getData().putUUID(STAMP,current);
        }
    }

    static boolean witnessed(ServerLevel from,ServerLevel to,StorageBounds b,UUID passage) {
        for(int x=b.minX()>>4;x<=b.maxX()>>4;x++) for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++) {
            LevelChunk outgoing=from.getChunkSource().getChunkNow(x,z), incoming=to.getChunkSource().getChunkNow(x,z);
            if (outgoing==null || incoming==null || !passage.equals(stamps.get(outgoing)) || !passage.equals(stamps.get(incoming))) return false;
        }
        return true;
    }

    private CompletableFuture<Void> save(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        // This UUID and the physical inventory are one region record. A partially written transfer cannot certify every column.
        stamps.put(chunk,passage);
        tag.putUUID(STAMP,passage);
        MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, tag));
        // Leave the normal dirty bit and structure/POI bookkeeping to ChunkMap's regular save cycle.
        return worker(level).store(pos, tag);
    }
}
