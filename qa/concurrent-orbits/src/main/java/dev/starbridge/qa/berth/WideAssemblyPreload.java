package dev.starbridge.qa.berth;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Warm the two known, independently verified storage plots in the disposable wide test world. */
final class WideAssemblyPreload {
    static final boolean ENABLED = ConcurrentBerths.WIDE && Files.isRegularFile(Path.of("wide-assembly-preload.fixture"));
    private static final TicketType<ChunkPos> TICKET = TicketType.create("starbridge_wide_fixture", Comparator.comparingLong(ChunkPos::toLong));
    private final List<ChunkPos> plots = new ArrayList<>();
    private final ServerLevel world;
    private final int started;
    private int requested;
    private int ready;

    WideAssemblyPreload(ServerLevel world, int tick) {
        this.world = world;
        started = tick;
        for (int minX : new int[]{-28669961, -28665865}) {
            for (int x = (minX >> 4) - 1; x <= ((minX + 19) >> 4) + 1; x++) {
                for (int z = (12289897 >> 4) - 1; z <= (12290197 >> 4) + 1; z++) {
                    plots.add(new ChunkPos(x, z));
                }
            }
        }
    }

    boolean tick(int tick) {
        if (tick - started > 1000) throw new IllegalStateException("The two isolated storage plots did not preload");
        while (ready < requested) {
            var pos = plots.get(ready);
            if (world.getChunkSource().getChunkNow(pos.x, pos.z) == null) return false;
            ready++;
        }
        int limit = Math.min(plots.size(), requested + 4);
        while (requested < limit) {
            var pos = plots.get(requested++);
            world.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
        }
        return ready == plots.size();
    }

    void release() {
        for (int i = 0; i < requested; i++) {
            var pos = plots.get(i);
            world.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        }
    }
}
