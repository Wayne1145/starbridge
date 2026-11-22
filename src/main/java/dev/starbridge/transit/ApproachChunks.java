package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Load physical storage a few chunks at a time before the native portal reads both worlds. */
final class ApproachChunks {
    private static final int BATCH = 4;
    private static final int WAIT_TICKS = 100;
    private static final TicketType<UUID> TICKET = TicketType.create(
            "starbridge_approach_storage", UUID::compareTo, WAIT_TICKS + 20);

    private record StorageChunk(ServerLevel level, ChunkPos pos) {
        boolean loaded() {
            return level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
        }
    }

    final UUID ship;
    private final List<StorageChunk> chunks = new ArrayList<>();
    private final int started;
    private final ServerBossEvent progress = new ServerBossEvent(
            Component.translatable("nav.starbridge.approach.loading"),
            BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
    private int claimed;
    private int loaded;

    static boolean helps(StorageBounds bounds) {
        int width = (bounds.maxX() >> 4) - (bounds.minX() >> 4) + 1;
        int length = (bounds.maxZ() >> 4) - (bounds.minZ() >> 4) + 1;
        int footprint = width * length;
        return footprint >= 16 && footprint <= 64;
    }

    ApproachChunks(UUID ship, ServerLevel source, ServerLevel target, StorageBounds bounds,
                   List<ServerPlayer> crew) {
        this.ship = ship;
        started = source.getServer().getTickCount();
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                var pos = new ChunkPos(x, z);
                chunks.add(new StorageChunk(source, pos));
                chunks.add(new StorageChunk(target, pos));
            }
        }
        progress.setProgress(0);
        crew.forEach(progress::addPlayer);
    }

    void tick() {
        // Limit outstanding loads as well as the number requested in one tick.
        while (loaded < claimed && chunks.get(loaded).loaded()) loaded++;
        progress.setProgress((float) loaded / chunks.size());
        if (loaded < claimed) return;
        int limit = Math.min(claimed + BATCH, chunks.size());
        while (claimed < limit) {
            var chunk = chunks.get(claimed++);
            chunk.level.getChunkSource().addRegionTicket(TICKET, chunk.pos, 0, ship);
        }
    }

    boolean ready(int tick) {
        // The normal portal path can still load these chunks if the preparation times out.
        return tick - started >= WAIT_TICKS
                || (claimed == chunks.size() && chunks.stream().allMatch(StorageChunk::loaded));
    }

    void release() {
        progress.removeAllPlayers();
        for (int i = 0; i < claimed; i++) {
            var chunk = chunks.get(i);
            chunk.level.getChunkSource().removeRegionTicket(TICKET, chunk.pos, 0, ship);
        }
    }
}
