package dev.starbridge.flight;

/** A finite, immutable vector. Coordinates are blocks; time-based units are documented by the caller. */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d multiply(double scalar) {
        if (!Double.isFinite(scalar)) {
            throw new IllegalArgumentException("Vector scalar must be finite");
        }
        return new Vec3d(x * scalar, y * scalar, z * scalar);
    }

    public double length() {
        return Math.hypot(Math.hypot(x, y), z);
    }

    public Vec3d normalized() {
        double largest = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (largest == 0) return ZERO;
        // Scale first so even very large finite vectors can be normalized safely.
        double sx = x / largest;
        double sy = y / largest;
        double sz = z / largest;
        double length = Math.hypot(Math.hypot(sx, sy), sz);
        return new Vec3d(sx / length, sy / length, sz / length);
    }

    public Vec3d clampLength(double maximum) {
        if (!Double.isFinite(maximum) || maximum < 0) {
            throw new IllegalArgumentException("Maximum length must be finite and non-negative");
        }
        return length() > maximum ? normalized().multiply(maximum) : this;
    }
}
