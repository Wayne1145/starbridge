package dev.starbridge.compat.vs;

import com.mojang.datafixers.util.Either;
import dev.starbridge.Starbridge;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.CompletableFuture;

/** Keep successful FULL chunk lookups for one synchronous large ship assembly. */
public final class AssemblyChunkFutures implements AutoCloseable {
    private static final int MAX_CACHED_CHUNKS = 256;
    private static final ThreadLocal<AssemblyChunkFutures> ACTIVE = new ThreadLocal<>();

    private final ServerChunkCache source;
    private final AssemblyChunkFutures previous;
    private final Long2ObjectOpenHashMap<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> chunks =
            new Long2ObjectOpenHashMap<>();
    private int futureLookups;
    private int futureReuses;

    private AssemblyChunkFutures(ServerChunkCache source) {
        this.source = source;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    static AssemblyChunkFutures open(ServerChunkCache source) {
        return new AssemblyChunkFutures(source);
    }

    public static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> find(
            ServerChunkCache source, int x, int z) {
        var active = ACTIVE.get();
        if (active == null || active.source != source) return null;
        active.futureLookups++;
        var future = active.chunks.get(ChunkPos.asLong(x, z));
        if (future != null) active.futureReuses++;
        return future;
    }

    public static void remember(ServerChunkCache source, int x, int z,
                                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future) {
        var active = ACTIVE.get();
        if (active == null || active.source != source || active.chunks.size() >= MAX_CACHED_CHUNKS) return;
        if (!future.isDone() || future.isCompletedExceptionally()) return;
        var result = future.getNow(null);
        if (result != null && result.left().orElse(null) instanceof LevelChunk) {
            active.chunks.put(ChunkPos.asLong(x, z), future);
        }
    }

    /** Follow the vanilla cache invalidation when the visible holders or their tickets change. */
    public static void invalidate(ServerChunkCache source) {
        for (var active = ACTIVE.get(); active != null; active = active.previous) {
            if (active.source == source) active.chunks.clear();
        }
    }

    @Override
    public void close() {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
        Starbridge.LOGGER.info("Large ship assembly reused {} of {} full chunk futures", futureReuses, futureLookups);
    }
}
