package dev.starbridge.orbit;

import net.minecraft.world.phys.Vec3;

/** Surface charts use world coordinates. Home charts use block offsets from the recorded mass center. */
public final class LandingMap {
    public static final int RADIUS = 512;

    private LandingMap() {}

    public static boolean contains(int x, int z) {
        return x >= -RADIUS && x <= RADIUS && z >= -RADIUS && z <= RADIUS;
    }

    public static int coordinate(double pixel, int side) {
        double position = Math.max(0, Math.min(side - 1, pixel));
        return (int) Math.round(position * (2 * RADIUS) / (side - 1) - RADIUS);
    }

    public static int pixel(int coordinate, int side) {
        return (int) Math.round((coordinate + RADIUS) * (side - 1d) / (2 * RADIUS));
    }

    static Vec3 homeAnchor(Vec3 recorded, int x, int z) {
        if (!contains(x, z)) throw new IllegalArgumentException("Home berth outside chart");
        return recorded.add(x, 0, z);
    }
}
