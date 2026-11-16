package dev.starbridge.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutopilotControllerTest {
    private static final FlightConfig LIMITS = new FlightConfig(10, 2, 0.5, 0.1, 40);
    private static final double DT = AutopilotController.TICK_SECONDS;

    @Test
    void finiteInputsAndUsableLimitsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new Vec3d(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Vec3d(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlightConfig(0, 2, 1, 0.1, 20));
        assertThrows(IllegalArgumentException.class, () -> new FlightConfig(10, -2, 1, 0.1, 20));
        assertThrows(IllegalArgumentException.class, () -> new FlightConfig(10, 2, 1, 11, 20));
        assertThrows(IllegalArgumentException.class, () -> new FlightConfig(10, 2, 1, 0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Telemetry(Vec3d.ZERO, Vec3d.ZERO, -1));
    }

    @Test
    void navigationBrakesAndArrivesOnceWithoutCrossingTheDestination() {
        AutopilotController controller = new AutopilotController(LIMITS);
        Vec3d destination = new Vec3d(100, 0, 0);
        controller.navigateTo(destination, 0);
        Vec3d position = Vec3d.ZERO;
        Vec3d velocity = Vec3d.ZERO;
        int arrivals = 0;
        boolean sawCruise = false;
        boolean sawApproach = false;
        boolean sawBraking = false;

        for (int tick = 0; tick < 1600; tick++) {
            controller.heartbeat(tick);
            FlightCommand command = controller.update(new Telemetry(position, velocity, tick));
            sawCruise |= command.mode() == FlightMode.CRUISE;
            sawApproach |= command.mode() == FlightMode.APPROACH;
            sawBraking |= command.acceleration().x() < -0.01;
            if (command.event() == FlightCommand.Event.ARRIVED) arrivals++;
            assertTrue(command.acceleration().length() <= LIMITS.maxAcceleration() + 1e-9);
            velocity = velocity.add(command.acceleration().multiply(DT));
            position = position.add(velocity.multiply(DT));
            assertTrue(velocity.length() <= LIMITS.maxSpeed() + 1e-9);
            assertTrue(position.x() <= destination.x() + 1e-9, "Freshly launched ship overshot destination");
        }

        assertTrue(sawCruise);
        assertTrue(sawApproach);
        assertTrue(sawBraking);
        assertEquals(1, arrivals);
        assertEquals(FlightMode.HOLD, controller.mode());
        assertTrue(destination.subtract(position).length() <= LIMITS.positionTolerance());
        assertTrue(velocity.length() <= LIMITS.velocityTolerance());
    }

    @Test
    void proximityAtHighSpeedIsNotArrival() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.navigateTo(Vec3d.ZERO, 0);
        FlightCommand command = controller.update(new Telemetry(Vec3d.ZERO, new Vec3d(8, 0, 0), 0));
        assertEquals(FlightCommand.Event.NONE, command.event());
        assertEquals(FlightMode.APPROACH, command.mode());
        assertEquals(-LIMITS.maxAcceleration(), command.acceleration().x(), 1e-9);
    }

    @Test
    void missingHeartbeatLatchesBrakingAndEmitsExactlyOneFailure() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.navigateTo(new Vec3d(100, 0, 0), 0);
        assertNotEquals(FlightMode.EMERGENCY_STOP,
                controller.update(new Telemetry(Vec3d.ZERO, Vec3d.ZERO, 40)).mode());

        FlightCommand timedOut = controller.update(new Telemetry(Vec3d.ZERO, new Vec3d(4, 0, 0), 41));
        assertEquals(FlightMode.EMERGENCY_STOP, timedOut.mode());
        assertEquals(FlightCommand.Event.HEARTBEAT_LOST, timedOut.event());
        assertTrue(timedOut.acceleration().x() < 0);
        assertTrue(controller.target().isEmpty());

        controller.heartbeat(42);
        FlightCommand stillStopped = controller.update(new Telemetry(Vec3d.ZERO, Vec3d.ZERO, 42));
        assertEquals(FlightMode.EMERGENCY_STOP, stillStopped.mode());
        assertEquals(FlightCommand.Event.NONE, stillStopped.event());

        controller.navigateTo(new Vec3d(100, 0, 0), 43);
        assertEquals(FlightMode.CRUISE, controller.update(new Telemetry(Vec3d.ZERO, Vec3d.ZERO, 43)).mode());
    }

    @Test
    void manualTakeoverReleasesAllAutomaticAccelerationImmediately() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.navigateTo(new Vec3d(100, 0, 0), 0);
        controller.manualTakeover();
        FlightCommand command = controller.update(new Telemetry(Vec3d.ZERO, new Vec3d(10, 3, -2), 1));
        assertEquals(FlightMode.IDLE, command.mode());
        assertEquals(Vec3d.ZERO, command.acceleration());
        assertEquals(Vec3d.ZERO, command.desiredForward());
        assertTrue(controller.target().isEmpty());
    }

    @Test
    void emergencyBrakeRespectsAvailableAccelerationAndDoesNotReverseVelocity() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.emergencyStop();
        Vec3d velocity = new Vec3d(3, 4, 0);
        double lastSpeed = velocity.length();
        for (int tick = 0; tick < 100; tick++) {
            FlightCommand command = controller.update(new Telemetry(Vec3d.ZERO, velocity, tick));
            assertTrue(command.acceleration().length() <= LIMITS.maxAcceleration() + 1e-9);
            velocity = velocity.add(command.acceleration().multiply(DT));
            assertTrue(velocity.length() <= lastSpeed + 1e-9);
            assertTrue(velocity.x() >= -1e-9);
            assertTrue(velocity.y() >= -1e-9);
            lastSpeed = velocity.length();
        }
        assertEquals(0, velocity.length(), 1e-9);
    }

    @Test
    void holdRecoversFromDriftInThreeDimensions() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.holdAt(new Vec3d(5, 10, -8), 0);
        Vec3d position = new Vec3d(5, 10, -8);
        Vec3d velocity = new Vec3d(1, 2, -1);
        for (int tick = 0; tick < 2000; tick++) {
            controller.heartbeat(tick);
            FlightCommand command = controller.update(new Telemetry(position, velocity, tick));
            assertEquals(FlightMode.HOLD, command.mode());
            assertEquals(FlightCommand.Event.NONE, command.event());
            velocity = velocity.add(command.acceleration().multiply(DT));
            position = position.add(velocity.multiply(DT));
        }
        assertTrue(position.subtract(new Vec3d(5, 10, -8)).length() <= LIMITS.positionTolerance());
        assertTrue(velocity.length() <= LIMITS.velocityTolerance());
    }

    @Test
    void backwardTicksAreRejectedInsteadOfExtendingControlLease() {
        AutopilotController controller = new AutopilotController(LIMITS);
        controller.navigateTo(new Vec3d(100, 0, 0), 10);
        assertThrows(IllegalArgumentException.class, () -> controller.heartbeat(9));
        assertThrows(IllegalArgumentException.class, () -> controller.update(new Telemetry(Vec3d.ZERO, Vec3d.ZERO, 9)));
    }

    @Test
    void veryLargeVectorNormalizationDoesNotProduceNan() {
        Vec3d direction = new Vec3d(Double.MAX_VALUE, Double.MAX_VALUE, 0).normalized();
        assertEquals(1, direction.length(), 1e-12);
        assertTrue(Double.isFinite(direction.x()));
        assertEquals(Vec3d.ZERO, Vec3d.ZERO.normalized());
    }
}
