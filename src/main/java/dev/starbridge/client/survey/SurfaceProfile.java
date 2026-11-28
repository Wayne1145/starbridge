package dev.starbridge.client.survey;

import java.util.Arrays;

/** Per-block cross sections through a visible surface column. Missing columns remain gaps. */
final class SurfaceProfile {
    static final int RADIUS = 12;
    static final int COUNT = 2 * RADIUS + 1;
    static final int UNKNOWN = Integer.MIN_VALUE;

    enum Shape {
        UNSURVEYED,
        NARROW,
        CHANNEL,
        HOLLOW,
        OPEN
    }

    interface Heights {
        int at(int x, int z);
    }

    final int x, z;
    final int[] eastWest = new int[COUNT], northSouth = new int[COUNT];
    final int bottom, top, eastWidth, northWidth, depth;
    final Shape shape;

    SurfaceProfile(int x, int z, Heights source) {
        this.x = x;
        this.z = z;
        for (int i = 0; i < COUNT; i++) {
            eastWest[i] = source.at(x + i - RADIUS, z);
            northSouth[i] = i == RADIUS ? eastWest[i] : source.at(x, z + i - RADIUS);
        }
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int[] axis : new int[][] {eastWest, northSouth})
            for (int elevation : axis) {
                if (elevation != UNKNOWN) {
                    low = Math.min(low, elevation);
                    high = Math.max(high, elevation);
                }
            }
        bottom = low == Integer.MAX_VALUE ? 0 : low;
        top = Math.max(bottom, high);
        eastWidth = width(eastWest);
        northWidth = width(northSouth);
        if (eastWidth < 0 || northWidth < 0) {
            depth = 0;
            shape = Shape.UNSURVEYED;
            return;
        }
        int west = bank(eastWest, -1), east = bank(eastWest, 1);
        int north = bank(northSouth, -1), south = bank(northSouth, 1);
        int[] banks = {west, east, north, south};
        Arrays.sort(banks);
        depth = Math.max(0, banks[1] - eastWest[RADIUS]);
        int across = Math.max(Math.min(west, east), Math.min(north, south)) - eastWest[RADIUS];
        if (depth >= 10 && eastWidth <= 9 && northWidth <= 9) shape = Shape.NARROW;
        else if (across >= 8 && (eastWidth <= 9) != (northWidth <= 9)) shape = Shape.CHANNEL;
        else shape = depth >= 5 ? Shape.HOLLOW : Shape.OPEN;
    }

    private static int bank(int[] line, int direction) {
        int highest = line[RADIUS];
        for (int i = 1; i <= RADIUS; i++) highest = Math.max(highest, line[RADIUS + direction * i]);
        return highest;
    }

    // Width near the selected floor, measured until the surface rises three blocks or reaches the
    // edge.
    private static int width(int[] line) {
        for (int elevation : line) if (elevation == UNKNOWN) return -1;
        int count = 1;
        for (int direction : new int[] {-1, 1})
            for (int n = 1; n <= RADIUS; n++) {
                if (line[RADIUS + n * direction] > line[RADIUS] + 2) break;
                count++;
            }
        return count;
    }
}
