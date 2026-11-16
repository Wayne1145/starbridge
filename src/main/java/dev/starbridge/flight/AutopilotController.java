package dev.starbridge.flight;

import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic point-navigation controller, independent of Minecraft and of a particular ship engine.
 * It has no terrain sensing, collision avoidance, dimension transfer or ownership authority.
 * The server must authorize each caller and supply fresh world-space telemetry once per server tick.
 *
 * <p>Navigation remains active while the owning server task refreshes its heartbeat. This is a task
 * heartbeat, not a requirement for the player's Lua script to call this class every tick. Losing the
 * task heartbeat enters a latched braking mode; a new authorized navigation request is required to
 * resume. Brake commands are requests, so a physical backend must handle insufficient thrust/fuel.
 *
 * <p>The methods are synchronized to keep cancellation and updates consistent. Integrations should
 * still access their Minecraft worlds only on the appropriate server/physics thread.
 */
public final class AutopilotController {
    public static final double TICK_SECONDS = 1.0 / 20.0;

    private final FlightConfig config;
    private FlightMode mode = FlightMode.IDLE;
    private Vec3d target;
    private long lastHeartbeatTick = -1;
    private long latestTick = -1;

    public AutopilotController(FlightConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public AutopilotController() {
        this(FlightConfig.defaults());
    }

    public synchronized void navigateTo(Vec3d destination, long tick) {
        Objects.requireNonNull(destination, "destination");
        observeTick(tick);
        target = destination;
        mode = FlightMode.CRUISE;
        lastHeartbeatTick = tick;
    }

    public synchronized void holdAt(Vec3d position, long tick) {
        Objects.requireNonNull(position, "position");
        observeTick(tick);
        target = position;
        mode = FlightMode.HOLD;
        lastHeartbeatTick = tick;
    }

    public synchronized void heartbeat(long tick) {
        observeTick(tick);
        lastHeartbeatTick = tick;
    }

    /** Latches braking without changing ownership; authorization is the integration's responsibility. */
    public synchronized void emergencyStop() {
        target = null;
        mode = FlightMode.EMERGENCY_STOP;
    }

    /** Releases automatic control immediately. IDLE emits no acceleration or facing request. */
    public synchronized void manualTakeover() {
        target = null;
        mode = FlightMode.IDLE;
        lastHeartbeatTick = -1;
    }

    public synchronized FlightMode mode() {
        return mode;
    }

    public synchronized Optional<Vec3d> target() {
        return Optional.ofNullable(target);
    }

    public FlightConfig config() {
        return config;
    }

    public synchronized FlightCommand update(Telemetry telemetry) {
        Objects.requireNonNull(telemetry, "telemetry");
        observeTick(telemetry.tick());
        FlightCommand.Event event = FlightCommand.Event.NONE;

        if (isAutomatic() && telemetry.tick() - lastHeartbeatTick > config.heartbeatTimeoutTicks()) {
            emergencyStop();
            event = FlightCommand.Event.HEARTBEAT_LOST;
        }

        if (mode == FlightMode.IDLE) {
            return new FlightCommand(Vec3d.ZERO, Vec3d.ZERO, mode, event, 0);
        }
        if (mode == FlightMode.EMERGENCY_STOP) {
            return new FlightCommand(accelerateToward(Vec3d.ZERO, telemetry.velocity()),
                    Vec3d.ZERO, mode, event, 0);
        }

        Vec3d displacement = target.subtract(telemetry.position());
        double distance = displacement.length();
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Distance to target must be finite");
        }

        boolean navigating = mode == FlightMode.CRUISE || mode == FlightMode.APPROACH;
        if (navigating && distance <= config.positionTolerance()
                && telemetry.velocity().length() <= config.velocityTolerance()) {
            mode = FlightMode.HOLD;
            event = FlightCommand.Event.ARRIVED;
        } else if (navigating) {
            double cruiseBrakingDistance = config.maxSpeed() / config.maxAcceleration()
                    * (config.maxSpeed() / 2.0);
            mode = distance <= cruiseBrakingDistance + config.positionTolerance()
                    ? FlightMode.APPROACH : FlightMode.CRUISE;
        }

        // Aim inside the arrival tolerance, so rounding cannot leave a ship forever just outside it.
        double remaining = Math.max(0, distance - config.positionTolerance() * 0.5);
        double accelerationStep = config.maxAcceleration() * TICK_SECONDS;
        // Allow one control tick in the stopping distance rather than assuming instantaneous response.
        double safeSpeed = Math.sqrt(accelerationStep * accelerationStep
                + 2.0 * config.maxAcceleration() * remaining) - accelerationStep;
        double requestedSpeed = Math.min(config.maxSpeed(), Math.max(0, safeSpeed));
        Vec3d desiredVelocity = displacement.normalized().multiply(requestedSpeed);
        Vec3d facing = remaining > 0 ? displacement.normalized() : Vec3d.ZERO;

        return new FlightCommand(accelerateToward(desiredVelocity, telemetry.velocity()),
                facing, mode, event, distance);
    }

    private Vec3d accelerateToward(Vec3d desiredVelocity, Vec3d actualVelocity) {
        return desiredVelocity.subtract(actualVelocity)
                .clampLength(config.maxAcceleration() * TICK_SECONDS)
                .multiply(1.0 / TICK_SECONDS);
    }

    private boolean isAutomatic() {
        return mode == FlightMode.HOLD || mode == FlightMode.CRUISE || mode == FlightMode.APPROACH;
    }

    private void observeTick(long tick) {
        if (tick < 0 || tick < latestTick) {
            throw new IllegalArgumentException("Server ticks must be non-negative and monotonic");
        }
        latestTick = tick;
    }
}
