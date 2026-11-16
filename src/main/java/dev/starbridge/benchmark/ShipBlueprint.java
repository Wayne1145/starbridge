package dev.starbridge.benchmark;

import java.util.HashMap;
import java.util.Map;

/** A lazy block plan: air is represented by null, never by stored coordinates. */
final class ShipBlueprint {
    enum Cell { HULL, ACCENT, DECK, GLASS, LIGHT, LADDER, CORE, CHEST, FURNACE, RAIL }

    final int length;
    final int width;
    final int height;
    final int machineCount;
    final boolean platform;
    final int coreIndex;
    private final Map<Integer, Cell> machines = new HashMap<>();

    private ShipBlueprint(int length, int machineCount, boolean platform) {
        this.platform = platform;
        this.length = platform ? 9 : length;
        this.width = platform ? 9 : Math.min(64, length / 6 + 16);
        this.height = platform ? 3 : length / 20 + 18;
        this.machineCount = platform ? 0 : machineCount;
        int bridgeDeck = ((height - 4) / 7) * 7;
        coreIndex = platform ? -1 : index(length - 18, bridgeDeck + 1, width / 2);
        if (platform) return;
        int count = 0;
        outer: for (int y = 1; y < height - 2; y += 7) {
            for (int x = 16; x < length - 26; x += 3) {
                for (int z = 3; z < width - 3; z += 4) {
                    if (Math.abs(z - width / 2) <= 3 || Math.abs(z - width / 2) >= halfWidth(x) - 2) continue;
                    if (count >= machineCount) break outer;
                    machines.put(index(x, y, z), count % 2 == 0 ? Cell.CHEST : Cell.FURNACE);
                    count++;
                }
            }
        }
        if (count != machineCount) throw new IllegalArgumentException("Blueprint has insufficient machine slots");
    }

    static ShipBlueprint ship(int length, int machines) {
        if (length != 100 && length != 200 && length != 300) throw new IllegalArgumentException("Length must be 100, 200 or 300");
        if (machines < 0 || machines > 200) throw new IllegalArgumentException("Machine count must be 0..200");
        return new ShipBlueprint(length, machines, false);
    }

    static ShipBlueprint platform() { return new ShipBlueprint(9, 0, true); }
    int volume() { return length * width * height; }
    int x(int index) { return index % length; }
    int y(int index) { return (index / length) % height; }
    int z(int index) { return index / (length * height); }
    private int index(int x, int y, int z) { return x + y * length + z * length * height; }

    private int halfWidth(int x) {
        int half = width / 2 - 1;
        int stern = Math.max(7, (int) Math.ceil(half * Math.min(1.0, (x + 10.0) / 22.0)));
        int bow = Math.max(3, (int) Math.ceil(half * Math.min(1.0, (length - x + 3.0) / 34.0)));
        return Math.min(stern, bow);
    }

    Cell cell(int index) {
        int x = x(index), y = y(index), z = z(index);
        if (platform) {
            if (y == 0) return Cell.DECK;
            if (y == 1 && (x == 0 || x == 8 || z == 0 || z == 8)) return Cell.RAIL;
            return null;
        }
        int distance = Math.abs(z - width / 2);
        int half = halfWidth(x);
        if (distance > half) return null;
        boolean side = distance == half || x == 0 || x == length - 1;
        boolean roof = y == height - 1;
        int bridgeDeck = ((height - 4) / 7) * 7;
        if (side || roof || y == 0) {
            if (x > length - 28 && y > bridgeDeck && (side || roof)) return Cell.GLASS;
            if (side && x > 15 && x < length - 28 && x % 12 < 8 && (y % 7 == 3 || y % 7 == 4)) return Cell.GLASS;
            return side && y % 7 == 0 ? Cell.ACCENT : Cell.HULL;
        }
        if (index == coreIndex) return Cell.CORE;
        Cell machine = machines.get(index);
        if (machine != null) return machine;
        if (x == 10 && z == width / 2 - 1) return Cell.HULL;
        if (x == 10 && z == width / 2) return Cell.LADDER;
        if (y % 7 == 0 && y <= height - 4) {
            if (x % 12 == 0 && distance == 4) return Cell.LIGHT;
            return Cell.DECK;
        }
        return null;
    }
}
