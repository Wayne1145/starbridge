package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keep machinery on occupied decks active even when it is beyond a pilot's simulation distance. */
final class CabinChunks {
    private static final int ADDITIONS_PER_TICK = 4;
    private static final TicketType<UUID> CABIN =
            TicketType.create("starbridge_occupied_cabin", UUID::compareTo);
    private static final Map<UUID, Lease> leases = new LinkedHashMap<>();
    private static int roundRobin;

    private static final class Lease {
        final UUID id;
        final ServerLevel level;
        final StorageBounds box;
        final List<ChunkPos> chunks;
        int claimed;

        Lease(UUID id, ServerLevel level, StorageBounds box, BlockPos core) {
            this.id = id;
            this.level = level;
            this.box = box;
            chunks = new ArrayList<>();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++)
                    chunks.add(new ChunkPos(x, z));
            var bridge = new ChunkPos(core);
            chunks.sort(Comparator.comparingInt(bridge::getChessboardDistance));
        }

        boolean occupied() {
            for (var player : level.players()) {
                var p = player.blockPosition();
                if (player.isAlive()
                        && !player.isSpectator()
                        && p.getX() >= box.minX()
                        && p.getX() <= box.maxX()
                        && p.getZ() >= box.minZ()
                        && p.getZ() <= box.maxZ()
                        && p.getY() >= box.minY()
                        && p.getY() <= box.maxY() + 2) return true;
            }
            return false;
        }

        boolean warmNext() {
            if (claimed == chunks.size()) return false;
            level.getChunkSource().addRegionTicket(CABIN, chunks.get(claimed++), 1, id, true);
            return true;
        }

        void release() {
            for (int i = 0; i < claimed; i++)
                level.getChunkSource().removeRegionTicket(CABIN, chunks.get(i), 1, id, true);
        }
    }

    private CabinChunks() {}

    static void occupy(ShipCoreBlockEntity core, ServerLevel level, StorageBounds box) {
        HullSurvey.validate(box);
        var lease = leases.get(core.id());
        if (lease != null && lease.level == level && lease.box.equals(box)) return;
        release(core.id());
        leases.put(core.id(), new Lease(core.id(), level, box, core.getBlockPos()));
    }

    static void tick(int tick) {
        if (leases.isEmpty()) return;
        if (tick % 20 == 0) {
            leases.values()
                    .removeIf(
                            lease -> {
                                if (lease.occupied()) return false;
                                lease.release();
                                return true;
                            });
        }
        var current = List.copyOf(leases.values());
        if (current.isEmpty()) return;
        int budget = ADDITIONS_PER_TICK;
        for (int i = 0; i < current.size() && budget > 0; i++) {
            if (current.get(Math.floorMod(roundRobin++, current.size())).warmNext()) budget--;
        }
    }

    static void release(UUID id) {
        var lease = leases.remove(id);
        if (lease != null) lease.release();
    }

    static void clear() {
        leases.clear();
        roundRobin = 0;
    }
}
