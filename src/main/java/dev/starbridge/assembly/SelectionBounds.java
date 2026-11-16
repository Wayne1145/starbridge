package dev.starbridge.assembly;

/** Inclusive assembly selection with overflow-safe limits, independent of Minecraft. */
public record SelectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static final int MAX_AXIS = 300;
    public static final int MAX_VOLUME = 2_000_000;
    public static final int MAX_BLOCKS = 150_000;

    public SelectionBounds {
        long x = (long) maxX - minX + 1;
        long y = (long) maxY - minY + 1;
        long z = (long) maxZ - minZ + 1;
        if (x < 1 || y < 1 || z < 1 || x > MAX_AXIS || y > MAX_AXIS || z > MAX_AXIS) {
            throw new IllegalArgumentException("Each selection axis must be between 1 and 300 blocks inclusive.");
        }
        if (x * y * z > MAX_VOLUME) throw new IllegalArgumentException("Selection volume exceeds 2,000,000 cells.");
    }

    public static SelectionBounds between(int ax, int ay, int az, int bx, int by, int bz) {
        return new SelectionBounds(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }
    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public int volume() { return width() * height() * depth(); }
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public int x(int index) { check(index); return minX + index % width(); }
    public int y(int index) { check(index); return minY + index / (width() * depth()); }
    public int z(int index) { check(index); return minZ + (index / width()) % depth(); }
    private void check(int index) {
        if (index < 0 || index >= volume()) throw new IndexOutOfBoundsException(index);
    }
}
