package dev.starbridge.flight;

/**
 * Engine-independent attitude law: keeps a ship upright and follows a commanded yaw rate.
 *
 * <p>Quaternions are ship-to-world rotations (x, y, z, w). Angular velocity and the returned
 * torque are world-frame (rad/s and N·m). The inertia tensor is in ship/body coordinates and is
 * rotated into the world frame before use. Pure math only: callers own threading and fuel.</p>
 */
public final class AttitudeController {
    public static final double MAX_YAW_RATE = 0.6;
    public static final double MAX_ANGULAR_ACCELERATION = 1.2;
    private static final double LEVEL_GAIN = 1.6;
    private static final double MAX_LEVEL_RATE = 0.5;
    private static final double RATE_GAIN = 3.0;
    private static final Vec3d UP = new Vec3d(0, 1, 0);

    private AttitudeController() {}

    /** Desired world angular acceleration (rad/s²) toward upright flight at {@code yawRate} rad/s. */
    public static Vec3d angularAcceleration(Quaternion rotation, Vec3d omega, double yawRate) {
        if (!Double.isFinite(yawRate)) throw new IllegalArgumentException("Yaw rate must be finite");
        double commandedYaw = Math.max(-MAX_YAW_RATE, Math.min(MAX_YAW_RATE, yawRate));
        Vec3d up = rotation.rotate(UP);
        Vec3d axis = cross(up, UP);
        double sine = axis.length();
        double cosine = dot(up, UP);
        Vec3d levelling;
        if (sine < 1e-9) {
            // Upright needs nothing; fully inverted picks any horizontal axis to roll back.
            levelling = cosine > 0 ? Vec3d.ZERO : new Vec3d(MAX_LEVEL_RATE, 0, 0);
        } else {
            double angle = Math.atan2(sine, cosine);
            levelling = axis.multiply(1.0 / sine).multiply(Math.min(MAX_LEVEL_RATE, LEVEL_GAIN * angle));
        }
        Vec3d desired = new Vec3d(levelling.x(), commandedYaw, levelling.z());
        return desired.subtract(omega).multiply(RATE_GAIN).clampLength(MAX_ANGULAR_ACCELERATION);
    }

    /** World torque for a world angular acceleration, using I_world = R · I_body · Rᵀ. */
    public static Vec3d torque(Quaternion rotation, double[] bodyInertia, Vec3d angularAcceleration) {
        if (bodyInertia == null || bodyInertia.length != 9) throw new IllegalArgumentException("Inertia must be a 3×3 matrix");
        for (double value : bodyInertia) if (!Double.isFinite(value)) throw new IllegalArgumentException("Inertia must be finite");
        double[] r = rotation.matrix();
        // Rotate α into body space, multiply by I_body, rotate back.
        double bx = r[0] * angularAcceleration.x() + r[3] * angularAcceleration.y() + r[6] * angularAcceleration.z();
        double by = r[1] * angularAcceleration.x() + r[4] * angularAcceleration.y() + r[7] * angularAcceleration.z();
        double bz = r[2] * angularAcceleration.x() + r[5] * angularAcceleration.y() + r[8] * angularAcceleration.z();
        double tx = bodyInertia[0] * bx + bodyInertia[1] * by + bodyInertia[2] * bz;
        double ty = bodyInertia[3] * bx + bodyInertia[4] * by + bodyInertia[5] * bz;
        double tz = bodyInertia[6] * bx + bodyInertia[7] * by + bodyInertia[8] * bz;
        return new Vec3d(r[0] * tx + r[1] * ty + r[2] * tz,
                r[3] * tx + r[4] * ty + r[5] * tz,
                r[6] * tx + r[7] * ty + r[8] * tz);
    }

    /** Minecraft-style heading of the ship's +Z axis in degrees (0 = south, 90 = west). */
    public static double headingDegrees(Quaternion rotation) {
        Vec3d forward = rotation.rotate(new Vec3d(0, 0, 1));
        if (Math.abs(forward.x()) < 1e-9 && Math.abs(forward.z()) < 1e-9) return 0;
        double degrees = Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** Tilt from upright in degrees. */
    public static double tiltDegrees(Quaternion rotation) {
        Vec3d up = rotation.rotate(UP);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(up, UP)))));
    }

    private static Vec3d cross(Vec3d a, Vec3d b) {
        return new Vec3d(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static double dot(Vec3d a, Vec3d b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }

    /** A normalized rotation quaternion. */
    public record Quaternion(double x, double y, double z, double w) {
        public static final Quaternion IDENTITY = new Quaternion(0, 0, 0, 1);

        public Quaternion {
            double length = Math.sqrt(x * x + y * y + z * z + w * w);
            if (!Double.isFinite(length) || length < 1e-9) throw new IllegalArgumentException("Quaternion must be finite and non-zero");
            x /= length; y /= length; z /= length; w /= length;
        }

        public static Quaternion axisAngle(Vec3d axis, double radians) {
            Vec3d unit = axis.normalized();
            double half = radians / 2;
            double sine = Math.sin(half);
            return new Quaternion(unit.x() * sine, unit.y() * sine, unit.z() * sine, Math.cos(half));
        }

        public Vec3d rotate(Vec3d v) {
            // v' = v + 2w(q×v) + 2 q×(q×v)
            double cx = y * v.z() - z * v.y(), cy = z * v.x() - x * v.z(), cz = x * v.y() - y * v.x();
            double ccx = y * cz - z * cy, ccy = z * cx - x * cz, ccz = x * cy - y * cx;
            return new Vec3d(v.x() + 2 * (w * cx + ccx), v.y() + 2 * (w * cy + ccy), v.z() + 2 * (w * cz + ccz));
        }

        /** Row-major rotation matrix. */
        double[] matrix() {
            return new double[]{
                    1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                    2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                    2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
        }
    }
}
