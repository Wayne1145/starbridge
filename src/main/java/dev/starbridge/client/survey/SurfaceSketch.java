package dev.starbridge.client.survey;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.Arrays;

/** Contours from columns the client already has. No chunk requests or underground structure lookups. */
final class SurfaceSketch {
    static final int SIDE = 33, MID = SIDE / 2, UNKNOWN = Integer.MIN_VALUE;
    private final int[] heights = new int[SIDE * SIDE], xs = new int[heights.length], zs = new int[heights.length];
    private final int[] lows = new int[heights.length], high = new int[heights.length];
    private final BlockPos[] lowPoints = new BlockPos[heights.length];
    private final long[] times = new long[heights.length];
    private int cursor;
    SurfaceSketch() { clear(); }
    void clear() { Arrays.fill(heights, UNKNOWN); Arrays.fill(lows, UNKNOWN); Arrays.fill(lowPoints, null); Arrays.fill(times, Long.MIN_VALUE); cursor = 0; }
    private static int slot(int x, int z) { return Math.floorMod(z, SIDE) * SIDE + Math.floorMod(x, SIDE); }

    void tick(ClientLevel world, int centerX, int centerZ, int stride) {
        long now = world.getGameTime();
        // A wide chart samples four by four visible columns per cell. The walking scale covers every column.
        int spacing = Math.max(1, stride / 4);
        int axis = (stride + spacing - 1) / spacing;
        int cellsPerTick = Math.min(80, 512 / (axis * axis));
        for (int count = 0; count < cellsPerTick; count++) {
            int cell = cursor++;
            if (cursor == heights.length) cursor = 0;
            int tx = centerX + cell % SIDE - MID, tz = centerZ + cell / SIDE - MID;
            int index = slot(tx, tz);
            if (times[index] != Long.MIN_VALUE && xs[index] == tx && zs[index] == tz
                    && now >= times[index] && now - times[index] < (heights[index] == UNKNOWN ? 15 : 100)) continue;
            xs[index] = tx; zs[index] = tz; times[index] = now;
            int wx = tx * stride, wz = tz * stride;
            heights[index] = read(world, wx, wz);
            lows[index] = high[index] = heights[index];
            lowPoints[index] = heights[index] == UNKNOWN ? null : new BlockPos(wx, heights[index], wz);
            for (int dz = 0; dz < stride; dz += spacing) for (int dx = 0; dx < stride; dx += spacing) {
                if (dx == 0 && dz == 0) continue;
                int surface = read(world, wx + dx, wz + dz);
                if (surface == UNKNOWN) continue;
                if (lows[index] == UNKNOWN || surface < lows[index]) {
                    lows[index] = surface;
                    lowPoints[index] = new BlockPos(wx + dx, surface, wz + dz);
                }
                high[index] = Math.max(high[index], surface);
            }
        }
    }

    int at(int x, int z) {
        int i = slot(x, z);
        return xs[i] == x && zs[i] == z ? heights[i] : UNKNOWN;
    }
    BlockPos lowPoint(int x, int z) {
        int i = slot(x, z);
        return xs[i] == x && zs[i] == z ? lowPoints[i] : null;
    }

    /** The drop from the third-highest of four measured banks to the lowest exposed column. */
    int rimDrop(int x, int z) {
        var low = lowPoint(x, z);
        if (low == null) return 0;
        int west = at(x - 2, z), east = at(x + 2, z);
        int north = at(x, z - 2), south = at(x, z + 2);
        int lowEastWest = Math.min(west, east), lowNorthSouth = Math.min(north, south);
        // Drop the lowest bank. Three measured banks must still stand above the exposed floor.
        int remainingBank = lowEastWest < lowNorthSouth
                ? Math.min(Math.max(west, east), lowNorthSouth)
                : Math.min(Math.max(north, south), lowEastWest);
        return remainingBank == UNKNOWN ? 0 : Math.max(0, remainingBank - low.getY());
    }

    boolean depression(int x, int z) { return rimDrop(x, z) >= 8; }

    boolean steep(int x, int z) {
        int i = slot(x, z);
        return lowPoint(x, z) != null && high[i] - lows[i] >= 10 && rimDrop(x, z) >= 12;
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
