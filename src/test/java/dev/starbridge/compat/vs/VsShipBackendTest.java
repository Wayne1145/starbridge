package dev.starbridge.compat.vs;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Adapter-contract tests, not a substitute for running the VS native physics engine. */
class VsShipBackendTest {
    private final AtomicLong time = new AtomicLong();
    private VsShipBackend backend;
    private FakeLevel level;
    private FakeShip ship;

    @BeforeEach
    void setup() throws Exception {
        FakeMod.INSTANCE = new FakeMod();
        backend = new VsShipBackend(FakeUtils.class, FakeMod.class,
                FakeVector.class.getConstructor(double.class, double.class, double.class), time::get);
        level = new FakeLevel("minecraft:dimension:minecraft:overworld");
        ship = new FakeShip(42, level.dimension, 1000);
        level.world.ships.put(ship.id, ship);
    }

    @Test
    void consolePasteUsesTheTemplateGridAcrossNegativeCoordinatesAndMixedExtents() {
        // A 19 x 8 x 300 template with a console offset from the center on all axes.
        var min = new BlockPos(-97, 128, -181);
        var max = new BlockPos(-79, 135, 118);
        var console = new BlockPos(-94, 130, -180);
        var center = VsShipBackend.templateCenter(Set.of(min, max, console));
        assertEquals(new BlockPos(-88, 132, -31), center);
        assertEquals(new BlockPos(-28_665_862, 126, 12_289_898), VsShipBackend.pastedConsole(
                console, center, -28_665_855.5, 128.0, 12_290_047.0));
    }

    @Test
    void absenceIsExplicitAndDoesNotProduceSyntheticTelemetry() {
        VsShipBackend absent = new VsShipBackend(new ClassLoader(null) { });
        assertFalse(absent.probe().available());
        assertThrows(VsShipBackend.BackendException.class, () -> absent.inspect(level, new FakeBlockPos(42)));
    }

    @Test
    void snapshotsUseActualShipDataAndRejectOtherDimensions() {
        var snapshot = backend.inspect(level, new FakeBlockPos(42)).orElseThrow();
        assertEquals(42, snapshot.shipId());
        assertEquals(1000, snapshot.massKg());
        assertEquals(20, snapshot.position().x());
        assertEquals(3, snapshot.velocity().z());
        assertTrue(backend.inspectById(level, 999).isEmpty());
        ship.dimension = "minecraft:dimension:starbridge:survey_moon";
        assertTrue(backend.inspectById(level, 42).isEmpty());
    }

    @Test
    void staticTransitionsUpdateAuthoritativeServerShipRatherThanOnlyPhysicsQueue() {
        assertTrue(backend.setStatic(level, 42, true));
        assertTrue(ship.staticState);
        assertTrue(backend.inspectById(level, 42).orElseThrow().isStatic());
        assertTrue(backend.setStatic(level, 42, false));
        assertFalse(ship.staticState);
        assertFalse(backend.inspectById(level, 42).orElseThrow().isStatic());
        assertFalse(backend.setStatic(level, 999, false));
        ship.dimension = "minecraft:dimension:starbridge:survey_moon";
        assertFalse(backend.parkInGameState(level, 42));
        assertFalse(ship.staticState);
    }

    @Test
    void accelerationRunsOnEveryPhysicsTickAndTracksActualMass() {
        assertTrue(backend.setWorldAcceleration(level, 42, 2, 10, -1, 500));
        tick();
        tick();
        assertEquals(2, ship.forces.size());
        assertEquals(new FakeVector(2000, 10000, -1000), ship.forces.get(0));
        assertSame(ship.position, ship.lastForcePosition);
        ship.mass = 2000;
        tick();
        assertEquals(new FakeVector(4000, 20000, -2000), ship.forces.get(2));
        assertEquals(1, FakeMod.INSTANCE.api.event.listeners.size());
    }

    @Test
    void expiredAccelerationStopsWithoutAnotherGameTick() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 250);
        tick();
        time.set(250_000_000L);
        tick();
        tick();
        assertEquals(1, ship.forces.size());
    }

    @Test
    void physicsAcknowledgementsRequireAnActualRecentCallbackAndAreClearedOnRelease() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 1000);
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
        tick();
        assertTrue(backend.physicsAcknowledged(level, 42, 100));
        backend.setWorldAcceleration(level, 42, 1, 10, 0, 1000);
        assertTrue(backend.physicsAcknowledged(level, 42, 100), "A normal refresh retains the active stream's recent ACK");
        time.set(101_000_000L);
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
        tick();
        assertTrue(backend.physicsAcknowledged(level, 42, 100));
        backend.clearWorldAcceleration(level, 42);
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 1000);
        assertFalse(backend.physicsAcknowledged(level, 42, 100), "New control streams cannot inherit a previous task's ACK");
        tick();
        backend.clearAllControls();
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
    }

    @Test
    void clearAndUnloadRevokeThrust() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        backend.clearWorldAcceleration(level, 42);
        tick();
        assertTrue(ship.forces.isEmpty());
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        level.world.ships.remove(42L);
        tick();
        level.world.ships.put(42L, ship);
        tick();
        assertTrue(ship.forces.isEmpty(), "An unloaded ship must not regain stale thrust when loaded again.");
    }

    @Test
    void invalidInputsCannotReachThePhysicsThread() {
        assertThrows(IllegalArgumentException.class,
                () -> backend.setWorldAcceleration(level, 42, Double.NaN, 0, 0, 500));
        assertThrows(IllegalArgumentException.class,
                () -> backend.setWorldAcceleration(level, 42, 129, 0, 0, 500));
        assertThrows(IllegalArgumentException.class,
                () -> backend.setWorldAcceleration(level, 42, 0, 0, 0, 2001));
        assertThrows(IllegalArgumentException.class,
                () -> backend.setWorldAcceleration(level, 42, 0, 0, 0, 0));
        assertFalse(backend.setWorldAcceleration(level, 999, 0, 10, 0, 500));
        tick();
        assertTrue(ship.forces.isEmpty());
    }

    @Test
    void physicsFailureRevokesThrustAndIsReportedWithoutEscapingCallback() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        ship.fail = true;
        assertDoesNotThrow(this::tick);
        assertTrue(backend.lastPhysicsError().orElseThrow().contains("42"));
        ship.fail = false;
        tick();
        assertTrue(ship.forces.isEmpty());
    }

    @Test
    void shutdownUnregistersAndNextServerCanSubscribeAgain() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        backend.close();
        assertTrue(FakeMod.INSTANCE.api.event.listeners.isEmpty());
        tick();
        assertTrue(ship.forces.isEmpty());
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        tick();
        assertEquals(1, ship.forces.size());
        assertEquals(1, FakeMod.INSTANCE.api.event.listeners.size());
    }

    @Test
    void aReplacedPhysicsEventSourceIsReboundWithoutLeavingAnOrphanListener() {
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        tick();
        var oldEvent = FakeMod.INSTANCE.api.event;
        FakeMod.INSTANCE = new FakeMod();
        backend.setWorldAcceleration(level, 42, 0, 10, 0, 500);
        assertTrue(oldEvent.listeners.isEmpty());
        assertEquals(1, FakeMod.INSTANCE.api.event.listeners.size());
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
        tick();
        assertTrue(backend.physicsAcknowledged(level, 42, 100));
        assertEquals(2, ship.forces.size());
    }

    @Test
    void listenerCanStartBeforeAnyControlsAndRestartAfterServerShutdown() {
        long before = VsShipBackend.diagnostics().callbacks();
        backend.startPhysicsListener();
        backend.startPhysicsListener();
        assertEquals(1, FakeMod.INSTANCE.api.event.listeners.size());
        tick();
        assertEquals(before + 1, VsShipBackend.diagnostics().callbacks());
        assertTrue(ship.forces.isEmpty());
        assertFalse(backend.physicsAcknowledged(level, 42, 100));
        backend.close();
        assertTrue(FakeMod.INSTANCE.api.event.listeners.isEmpty());
        backend.startPhysicsListener();
        tick();
        assertEquals(before + 2, VsShipBackend.diagnostics().callbacks());
        assertTrue(ship.forces.isEmpty());
    }

    private void tick() {
        FakeMod.INSTANCE.api.event.emit(new FakePhysicsEvent(level.world));
    }

    public static final class FakeUtils {
        public static FakeShip getLoadedShipManagingPos(FakeLevel level, FakeBlockPos pos) {
            return level.world.ships.get(pos.shipId());
        }
        public static FakeWorld getShipObjectWorld(FakeLevel level) { return level.world; }
        public static String getDimensionId(FakeLevel level) { return level.dimension; }
    }

    /** Non-static methods intentionally exercise Kotlin object's INSTANCE handling. */
    public static final class FakeMod {
        public static FakeMod INSTANCE = new FakeMod();
        final FakeApi api = new FakeApi();
        public FakeApi getApi() { return api; }
    }
    public static final class FakeApi {
        final FakeListenableEvent event = new FakeListenableEvent();
        public FakeListenableEvent getPhysTickEvent() { return event; }
    }
    public static final class FakeListenableEvent {
        final List<Consumer<Object>> listeners = new ArrayList<>();
        public FakeListener on(Consumer<Object> consumer) {
            listeners.add(consumer);
            return new FakeRegistration(listeners, consumer);
        }
        void emit(Object event) { List.copyOf(listeners).forEach(listener -> listener.accept(event)); }
    }
    public interface FakeListener { void unregister(); }
    private record FakeRegistration(List<Consumer<Object>> listeners, Consumer<Object> consumer) implements FakeListener {
        public void unregister() { listeners.remove(consumer); }
    }
    public record FakePhysicsEvent(FakeWorld world) {
        public FakeWorld getWorld() { return world; }
        public double getDelta() { return 1.0 / 60; }
    }
    public static final class FakeLevel {
        final String dimension;
        final FakeWorld world;
        FakeLevel(String dimension) { this.dimension = dimension; world = new FakeWorld(dimension); }
    }
    public record FakeBlockPos(long shipId) { }
    public static final class FakeWorld {
        final String dimension;
        final Map<Long, FakeShip> ships = new HashMap<>();
        FakeWorld(String dimension) { this.dimension = dimension; }
        public FakeWorld getLoadedShips() { return this; }
        public FakeWorld getAllShips() { return this; }
        public FakeShip getById(long id) { return ships.get(id); }
        public FakeShip getShipById(long id) { return ships.get(id); }
        public String getDimension() { return dimension; }
    }
    public static final class FakeShip {
        final long id;
        String dimension;
        double mass;
        boolean fail;
        boolean staticState;
        final FakeVector position = new FakeVector(20, 80, 40);
        final List<FakeVector> forces = new ArrayList<>();
        FakeVector lastForcePosition;
        FakeShip(long id, String dimension, double mass) { this.id = id; this.dimension = dimension; this.mass = mass; }
        public long getId() { return id; }
        public String getSlug() { return "test-ship"; }
        public String getChunkClaimDimension() { return dimension; }
        public FakeInertia getInertiaData() { return new FakeInertia(mass); }
        public FakeShip getTransform() { return this; }
        public FakeShip getKinematics() { return this; }
        public FakeVector getPositionInWorld() { return position; }
        public FakeVector getPosition() { return position; }
        public FakeVector getVelocity() { return new FakeVector(1, 2, 3); }
        public FakeVector getAngularVelocity() { return new FakeVector(0, 0, 0); }
        public FakeRotation getShipToWorldRotation() { return new FakeRotation(0, 0, 0, 1); }
        public boolean isStatic() { return staticState; }
        public void setStatic(boolean state) { staticState = state; }
        public double getMass() { return mass; }
        public void applyWorldForce(FakeVector force, FakeVector position) {
            if (fail) throw new IllegalStateException("test body unavailable");
            forces.add(force);
            lastForcePosition = position;
        }
    }
    public record FakeInertia(double mass) { public double getMass() { return mass; } }
    public record FakeVector(double x, double y, double z) { }
    public record FakeRotation(double x, double y, double z, double w) { }
}
