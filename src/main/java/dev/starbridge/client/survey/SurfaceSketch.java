package dev.starbridge.client.survey;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.Arrays;

/** Contours from columns the client already has. No chunk requests or underground structure lookups. */
final class SurfaceSketch {
    static final int SIDE = 33, MID = SIDE / 2, UNKNOWN = Integer.MIN_VALUE;
    private final int[] heights = new int[SIDE * SIDE], xs = new int[heights.length], zs = new int[heights.length];
    private final long[] times = new long[heights.length];
    private int cursor;
    SurfaceSketch() { clear(); }
    void clear() { Arrays.fill(heights, UNKNOWN); Arrays.fill(times, Long.MIN_VALUE); cursor = 0; }
    private static int slot(int x, int z) { return Math.floorMod(z, SIDE) * SIDE + Math.floorMod(x, SIDE); }

    void tick(ClientLevel world, int centerX, int centerZ, int stride) {
        long now = world.getGameTime();
        for (int count = 0; count < 80; count++) {
            int cell = cursor++;
            if (cursor == heights.length) cursor = 0;
            int tx = centerX + cell % SIDE - MID, tz = centerZ + cell / SIDE - MID;
            int index = slot(tx, tz);
            if (times[index] != Long.MIN_VALUE && xs[index] == tx && zs[index] == tz
                    && now >= times[index] && now - times[index] < (heights[index] == UNKNOWN ? 15 : 100)) continue;
            xs[index] = tx; zs[index] = tz; times[index] = now;
            heights[index] = read(world, tx * stride, tz * stride);
        }
    }

    int at(int x, int z) {
        int i = slot(x, z);
        return xs[i] == x && zs[i] == z ? heights[i] : UNKNOWN;
    }
    boolean depression(int x, int z) {
        int h = at(x, z);
        if (h == UNKNOWN) return false;
        int banks = 0;
        if (at(x + 2, z) >= h + 8) banks++;
        if (at(x - 2, z) >= h + 8) banks++;
        if (at(x, z + 2) >= h + 8) banks++;
        if (at(x, z - 2) >= h + 8) banks++;
        return banks >= 3;
    }
    private static int read(ClientLevel world, int x, int z) {
        var chunk = world.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return UNKNOWN;
        var sections = chunk.getSections();
        for (int s = sections.length - 1; s >= 0; s--) {
            var section = sections[s];
            if (section.hasOnlyAir()) continue;
            for (int y = 15; y >= 0; y--) {
                if (!section.getBlockState(x & 15, y, z & 15).isAir()) return (chunk.getSectionYFromSectionIndex(s) << 4) + y + 1;
            }
        }
        return UNKNOWN;
    }
}
