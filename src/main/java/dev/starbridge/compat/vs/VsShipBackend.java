package dev.starbridge.compat.vs;

import dev.starbridge.flight.AttitudeController;
import dev.starbridge.flight.Vec3d;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Optional Valkyrien Skies 2.4 integration. No VS, Kotlin or Minecraft classes
 * are resolved until an operation is requested, so the mod can start without VS.
 * Call world-facing operations on the Minecraft server thread.
 *
 * <p>Positions are world blocks/metres, velocity is metres/second, mass is kg,
 * force is newtons, and torque is newton-metres. This class never simulates a
 * missing ship. A missing/unloaded ship is Optional.empty()/false; a missing or
 * incompatible backend is an explicit BackendException.</p>
 */
public final class VsShipBackend implements AutoCloseable {
    private static final String UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String MOD = "org.valkyrienskies.mod.common.ValkyrienSkiesMod";
    private static final String ASSEMBLER = "org.valkyrienskies.mod.common.assembly.ShipAssembler";
    private static final AtomicLong PHYSICS_CALLBACKS = new AtomicLong();
    private static final AtomicLong PHYSICS_FORCES = new AtomicLong();
    private static final AtomicLong PHYSICS_MISSING_BODIES = new AtomicLong();
    private static final AtomicLong PHYSICS_STATIC_BODIES = new AtomicLong();
    private static volatile double lastPhysicsDelta;
    private static volatile String lastPhysicsDimension = "";
    private static volatile String lastBodyState = "";
    private static volatile String lastSubscriptionState = "not subscribed";
    private final ClassLoader classLoader;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<MethodKey, Method> methods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ControlKey, AccelerationRequest> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ControlKey, PhysicsAcknowledgement> physicsAcknowledgements = new ConcurrentHashMap<>();
    private final AtomicLong controlGeneration = new AtomicLong();
    private volatile Bindings bindings;
    private volatile Object physicsListener;
    private volatile Consumer<Object> physicsCallback;
    private volatile Object physicsEventSource;
    private volatile String physicsError;
    private volatile boolean attitudeUnsupported;

    public VsShipBackend() {
        this(VsShipBackend.class.getClassLoader());
    }

    public VsShipBackend(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader);
        this.nanoTime = System::nanoTime;
    }

    // A deterministic seam for adapter tests. Production always resolves real VS classes.
    VsShipBackend(Class<?> utils, Class<?> mod, Constructor<?> vectorConstructor, LongSupplier nanoTime) {
        this.classLoader = VsShipBackend.class.getClassLoader();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.bindings = new Bindings(utils, mod, vectorConstructor, Map.of(), null);
    }

    public Probe probe() {
        try {
            resolve();
            return new Probe(true, "Valkyrien Skies API found; live flight still requires a loaded ship.");
        } catch (BackendException exception) {
            return new Probe(false, exception.getMessage());
        }
    }

    /**
     * Subscribe during ServerStartedEvent, after VS is initialized. This creates
     * no flight controls, applies no force and does not load a ship. Shutdown
     * must still call close(); a later start can subscribe again.
     */
    public void startPhysicsListener() {
        ensurePhysicsListener();
    }

    /** Resolve the ship containing a block in VS shipyard coordinates, e.g. a helm BlockPos. */
    public Optional<Snapshot> inspect(Object serverLevel, Object blockPosition) {
        Objects.requireNonNull(serverLevel, "serverLevel");
        Objects.requireNonNull(blockPosition, "blockPosition");
        Object ship = invoke(resolve().utils(), "getLoadedShipManagingPos", serverLevel, blockPosition);
        return ship == null ? Optional.empty() : Optional.of(snapshot(ship));
    }

    /** Look up a loaded ship by stable id, rejecting ships in a different dimension. */
    public Optional<Snapshot> inspectById(Object serverLevel, long shipId) {
        return loadedShip(serverLevel, shipId).map(this::snapshot);
    }

    /** Detect VS's reserved storage area without requiring a loaded ship. */
    public boolean isShipyardChunk(Object serverLevel, int chunkX, int chunkZ) {
        Bindings api = resolve();
        Object world = invoke(api.utils(), "getShipObjectWorld", serverLevel);
        return Boolean.TRUE.equals(invoke(world, "isChunkInShipyard", chunkX, chunkZ, dimensionId(serverLevel)));
    }

    /**
     * Storage-space voxel bounds from the all-ships collection, including ships
     * whose chunks are not ticking yet. Intended for explicit test/loading
     * management; it does not load or force chunks itself.
     */
    public Optional<StorageBounds> storageBoundsById(Object serverLevel, long shipId) {
        Object world = invoke(resolve().utils(), "getShipObjectWorld", serverLevel);
        Object allShips = invoke(world, "getAllShips");
        Object ship = invoke(allShips, "getById", shipId);
        if (ship == null || !dimensionId(serverLevel).equals(String.valueOf(invoke(ship, "getChunkClaimDimension")))) {
            return Optional.empty();
        }
        Object box = invoke(ship, "getShipAABB");
        if (box == null) return Optional.empty();
        return Optional.of(new StorageBounds(
                number(invoke(box, "minX"), "minX").intValue(), number(invoke(box, "minY"), "minY").intValue(),
                number(invoke(box, "minZ"), "minZ").intValue(), number(invoke(box, "maxX"), "maxX").intValue(),
                number(invoke(box, "maxY"), "maxY").intValue(), number(invoke(box, "maxZ"), "maxZ").intValue()));
    }

    /**
     * Read VS's configured dimension gravity. This deliberately has no hardcoded
     * fallback: a dimension missing from VS's resolver needs explicit setup.
     * External mods that override physics gravity without updating the resolver
     * need their own adapter; this method cannot observe such private overrides.
     */
    public Vector gravity(Object serverLevel) {
        String dimension = dimensionId(serverLevel);
        Object resolver = load("org.valkyrienskies.mod.common.config.DimensionParametersResolver");
        Object configured = invoke(resolver, "getDimensionMap");
        if (!(configured instanceof Map<?, ?> map) || !map.containsKey(dimension)) {
            throw new BackendException("No Valkyrien Skies gravity definition for " + dimension + ".");
        }
        return vector(invoke(map.get(dimension), "getGravity"));
    }

    /**
     * Apply acceleration continuously on VS's physics thread until refreshed or
     * expired. Acceleration includes any gravity compensation chosen by the
     * caller. The physics callback multiplies by the current physics-body mass,
     * and applies the force at its centre of mass (no unintended torque).
     * TTL is wall-clock milliseconds (1..2000), not game ticks. Expiry removes
     * thrust even when the game thread has stalled. Fuel and ownership must be
     * checked by the caller before every refresh.
     */
    public boolean setWorldAcceleration(Object serverLevel, long shipId,
                                        double x, double y, double z, long ttlMillis) {
        return setWorldControl(serverLevel, shipId, x, y, z, 0, false, ttlMillis);
    }

    /**
     * As {@link #setWorldAcceleration}, plus optional attitude control evaluated on the physics
     * thread: when {@code stabilize} is true the ship is kept upright and turned at
     * {@code yawRate} rad/s about world Y (see {@link dev.starbridge.flight.AttitudeController}).
     * An attitude fault disables only attitude control; translation thrust continues.
     */
    public boolean setWorldControl(Object serverLevel, long shipId, double x, double y, double z,
                                   double yawRate, boolean stabilize, long ttlMillis) {
        if (!Double.isFinite(yawRate)) throw new IllegalArgumentException("Yaw rate must be finite.");
        Vector acceleration = new Vector(x, y, z);
        if (Math.hypot(Math.hypot(x, y), z) > 128) {
            throw new IllegalArgumentException("Requested acceleration exceeds the 128 m/s² safety limit.");
        }
        if (ttlMillis < 1 || ttlMillis > 2000) {
            throw new IllegalArgumentException("Control TTL must be between 1 and 2000 milliseconds.");
        }
        String dimension = dimensionId(serverLevel);
        ControlKey key = new ControlKey(dimension, shipId);
        if (loadedShip(serverLevel, shipId).isEmpty()) {
            controls.remove(key);
            physicsAcknowledgements.remove(key);
            return false;
        }
        if (!controls.containsKey(key) && controls.size() >= 512) {
            throw new BackendException("The backend control limit of 512 active ships was reached.");
        }
        ensurePhysicsListener();
        controls.compute(key, (ignored, previous) -> {
            long generation = previous == null ? controlGeneration.incrementAndGet() : previous.generation();
            if (previous == null) physicsAcknowledgements.remove(key);
            return new AccelerationRequest(acceleration, nanoTime.getAsLong() + ttlMillis * 1_000_000L, generation,
                    yawRate, stabilize);
        });
        return true;
    }

    public void clearWorldAcceleration(Object serverLevel, long shipId) {
        ControlKey key = new ControlKey(dimensionId(serverLevel), shipId);
        controls.remove(key);
        physicsAcknowledgements.remove(key);
    }

    public void clearAllControls() {
        controls.clear();
        physicsAcknowledgements.clear();
    }

    /** True only after this ship's current control stream reached a successful physics force callback recently. */
    public boolean physicsAcknowledged(Object serverLevel, long shipId, long maxAgeMillis) {
        if (maxAgeMillis < 1 || maxAgeMillis > 60_000) throw new IllegalArgumentException("ACK age must be between 1 and 60000 ms.");
        ControlKey key = new ControlKey(dimensionId(serverLevel), shipId);
        AccelerationRequest current = controls.get(key);
        PhysicsAcknowledgement accepted = physicsAcknowledgements.get(key);
        return current != null && accepted != null && current.generation() == accepted.generation()
                && nanoTime.getAsLong() - accepted.receivedAtNanos() <= maxAgeMillis * 1_000_000L;
    }

    /** A callback fault disables that ship's thrust and remains visible here. */
    public Optional<String> lastPhysicsError() {
        return Optional.ofNullable(physicsError);
    }

    /** Process-wide diagnostic counters, useful for separating game ticks from native physics ticks. */
    public static PhysicsDiagnostics diagnostics() {
        return new PhysicsDiagnostics(PHYSICS_CALLBACKS.get(), PHYSICS_FORCES.get(),
                PHYSICS_MISSING_BODIES.get(), PHYSICS_STATIC_BODIES.get(), lastPhysicsDelta, lastPhysicsDimension, lastBodyState, lastSubscriptionState);
    }

    /** Read-only native pipeline and load diagnostics. Not used to override VS loading or freeze safeguards. */
    public Map<String, Object> runtimeDiagnostics(Object minecraftServer, Object serverLevel, long shipId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            Class<?> provider = load("org.valkyrienskies.mod.common.IShipObjectWorldServerProvider");
            Object pipeline = provider.getMethod("getVsPipeline").invoke(minecraftServer);
            Object currentEvent = invoke(invoke(resolve().mod(), "getApi"), "getPhysTickEvent");
            result.put("currentPhysicsEvent", currentEvent.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(currentEvent)));
            for (String name : List.of("getArePhysicsRunning", "isUsingDummyPhysics", "computePhysTps", "getLoadedVoxelChunks", "getPhysicsBackendType")) {
                result.put(name, String.valueOf(invoke(pipeline, name)));
            }
            Object world = invoke(resolve().utils(), "getShipObjectWorld", serverLevel);
            Object ship = invoke(invoke(world, "getAllShips"), "getById", shipId);
            if (ship != null) {
                for (String name : List.of("areVoxelsFullyLoaded", "getLoadFreezeUntilGameTick", "getKinematicTarget")) {
                    try { result.put(name, String.valueOf(invoke(ship, name))); }
                    catch (RuntimeException unavailable) { result.put(name, "not exposed: " + ship.getClass().getName()); }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            result.put("error", exception.toString());
        }
        return result;
    }

    /** Call on server shutdown. A later setWorldAcceleration re-subscribes. */
    @Override
    public synchronized void close() {
        controls.clear();
        physicsAcknowledgements.clear();
        Object registered = physicsListener;
        physicsListener = null;
        try {
            if (registered != null) {
                invoke(registered, "unregister");
                lastSubscriptionState = "unregistered " + registered.getClass().getName();
            }
        } finally {
            physicsCallback = null;
            physicsEventSource = null;
        }
    }

    private synchronized void ensurePhysicsListener() {
        Object api = invoke(resolve().mod(), "getApi");
        Object event = invoke(api, "getPhysTickEvent");
        if (physicsListener != null && physicsEventSource == event) return;
        boolean rebound = physicsListener != null;
        if (physicsListener != null) invoke(physicsListener, "unregister");
        physicsListener = null;
        physicsAcknowledgements.clear();
        Consumer<Object> callback = this::physicsTick;
        physicsCallback = callback;
        physicsEventSource = event;
        physicsListener = invoke(event, "on", physicsCallback);
        if (physicsListener == null) throw new BackendException("VS did not return a physics listener handle.");
        lastSubscriptionState = (rebound ? "rebound" : "registered") + " emitter=" + event.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(event))
                + " handle=" + physicsListener.getClass().getName() + " callbackRetained=true";
    }

    private void physicsTick(Object event) {
        PHYSICS_CALLBACKS.incrementAndGet();
        if (controls.isEmpty()) return;
        final Object world;
        final String dimension;
        try {
            world = invoke(event, "getWorld");
            dimension = String.valueOf(invoke(world, "getDimension"));
            lastPhysicsDimension = dimension;
            lastPhysicsDelta = number(invoke(event, "getDelta"), "physics delta").doubleValue();
        } catch (RuntimeException | LinkageError exception) {
            physicsError = "Physics event rejected: " + exception.getMessage();
            controls.clear();
            physicsAcknowledgements.clear();
            return;
        }
        long now = nanoTime.getAsLong();
        controls.forEach((key, request) -> {
            if (now - request.expiresAtNanos() >= 0) {
                if (controls.remove(key, request)) physicsAcknowledgements.remove(key);
                return;
            }
            if (!key.dimensionId().equals(dimension)) return;
            try {
                Object ship = invoke(world, "getShipById", key.shipId());
                if (ship == null) {
                    PHYSICS_MISSING_BODIES.incrementAndGet();
                    if (controls.remove(key, request)) physicsAcknowledgements.remove(key);
                    return;
                }
                if (Boolean.TRUE.equals(invoke(ship, "isStatic"))) { PHYSICS_STATIC_BODIES.incrementAndGet(); return; }
                double mass = finite(number(invoke(ship, "getMass"), "physics mass").doubleValue(), "physics mass");
                if (mass <= 0) throw new BackendException("Physics mass must be positive.");
                Vector acceleration = request.acceleration();
                Object force = joml(new Vector(acceleration.x() * mass, acceleration.y() * mass, acceleration.z() * mass));
                Object centreOfMass = invoke(invoke(ship, "getKinematics"), "getPosition");
                invoke(ship, "applyWorldForce", force, centreOfMass);
                if (request.stabilize() && !attitudeUnsupported) applyAttitude(ship, key, request.yawRate());
                if (controls.containsKey(key)) physicsAcknowledgements.put(key,
                        new PhysicsAcknowledgement(request.generation(), nanoTime.getAsLong()));
                PHYSICS_FORCES.incrementAndGet();
                if (PHYSICS_FORCES.get() % 60 == 0) {
                    try {
                        lastBodyState = "id=" + key.shipId() + " pos=" + vector(centreOfMass)
                                + " velocity=" + vector(invoke(ship, "getVelocity"))
                                + " sleeping=" + invoke(ship, "isSleeping")
                                + " kinematic=" + invoke(ship, "getKinematicTarget") + " force=" + force
                                + " nativeInertia=" + invoke(invoke(ship, "getRigidBodyReference$impl"), "e");
                    } catch (RuntimeException unavailable) { lastBodyState = "Diagnostic unavailable: " + unavailable.getMessage(); }
                }
            } catch (RuntimeException | LinkageError exception) {
                if (controls.remove(key, request)) physicsAcknowledgements.remove(key);
                physicsError = "Ship " + key.shipId() + " thrust disabled: " + exception.getMessage();
            }
        });
    }

    /** Physics-thread attitude law; a fault disables attitude control process-wide but never translation. */
    private void applyAttitude(Object ship, ControlKey key, double yawRate) {
        try {
            Object rotation;
            try { rotation = invoke(invoke(ship, "getTransform"), "getShipToWorldRotation"); }
            catch (BackendException missing) { rotation = invoke(invoke(ship, "getKinematics"), "getRotation"); }
            AttitudeController.Quaternion q = new AttitudeController.Quaternion(component(rotation, "x"),
                    component(rotation, "y"), component(rotation, "z"), component(rotation, "w"));
            Vector omega = vector(invoke(ship, "getAngularVelocity"));
            Object m = invoke(ship, "getMomentOfInertia");
            // JOML names elements m<column><row>; build a row-major array.
            double[] inertia = {
                    component(m, "m00"), component(m, "m10"), component(m, "m20"),
                    component(m, "m01"), component(m, "m11"), component(m, "m21"),
                    component(m, "m02"), component(m, "m12"), component(m, "m22")};
            Vec3d alpha = AttitudeController.angularAcceleration(q, new Vec3d(omega.x(), omega.y(), omega.z()), yawRate);
            Vec3d torque = AttitudeController.torque(q, inertia, alpha);
            invoke(ship, "applyWorldTorque", joml(new Vector(torque.x(), torque.y(), torque.z())));
        } catch (RuntimeException | LinkageError exception) {
            attitudeUnsupported = true;
            physicsError = "Attitude control disabled for ship " + key.shipId() + ": " + exception.getMessage();
        }
    }

    /** The dimension that currently stores a ship's blocks, from the all-ships collection (loaded or not). */
    public Optional<String> shipDimension(Object serverLevel, long shipId) {
        Object world = invoke(resolve().utils(), "getShipObjectWorld", serverLevel);
        Object ship = invoke(invoke(world, "getAllShips"), "getById", shipId);
        return ship == null ? Optional.empty() : Optional.of(String.valueOf(invoke(ship, "getChunkClaimDimension")));
    }

    /** VS's identifier for a level's dimension, e.g. {@code minecraft:dimension:minecraft:overworld}. */
    public String vsDimensionId(Object serverLevel) { return dimensionId(serverLevel); }

    /** Current world-space bounding box of a loaded ship, used to find passengers aboard. */
    public Optional<WorldBounds> worldBounds(Object serverLevel, long shipId) {
        return loadedShip(serverLevel, shipId).map(ship -> {
            Object box = invoke(ship, "getWorldAABB");
            return new WorldBounds(component(box, "minX"), component(box, "minY"), component(box, "minZ"),
                    component(box, "maxX"), component(box, "maxY"), component(box, "maxZ"));
        });
    }

    /**
     * Move a whole ship, with its blocks and block entities, to another dimension using VS's own
     * teleport pipeline (the same call as {@code /vs teleport}). Orientation is preserved and
     * velocity cleared. Entities are not moved by VS; callers must carry passengers themselves.
     * Call on the server thread with the ship parked.
     */
    public boolean teleportShip(Object serverLevel, long shipId, Object targetLevel, double x, double y, double z) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Teleport target must be finite.");
        Bindings api = resolve();
        Object world = invoke(api.utils(), "getShipObjectWorld", serverLevel);
        Object ship = invoke(invoke(world, "getAllShips"), "getById", shipId);
        if (ship == null || !dimensionId(serverLevel).equals(String.valueOf(invoke(ship, "getChunkClaimDimension")))) return false;
        Object rotation = invoke(invoke(ship, "getTransform"), "getShipToWorldRotation");
        Object vsCore = invoke(api.utils(), "getVsCore");
        Class<?> coreContract = load("org.valkyrienskies.core.internal.VsiCore");
        // Same defaults as VS's TeleportCommand: scale (bit 5) and position-in-ship (bit 6) omitted.
        Object data = invoke(coreContract, "newShipTeleportData$default", vsCore,
                joml(new Vector(x, y, z)), rotation, joml(new Vector(0, 0, 0)), joml(new Vector(0, 0, 0)),
                dimensionId(targetLevel), null, null, (1 << 5) | (1 << 6), null);
        invoke(vsCore, "teleportShip", world, ship, data);
        ControlKey key = new ControlKey(dimensionId(serverLevel), shipId);
        controls.remove(key);
        physicsAcknowledgements.remove(key);
        return true;
    }

    /**
     * Queue ONE physics-tick force through VS's GameToPhysicsAdapter. This is not
     * a persistent thruster setting: the queue is drained on the next physics
     * tick. A caller must not assume a game tick equals a physics tick.
     */
    public boolean applyWorldForce(Object serverLevel, long shipId, double x, double y, double z) {
        Vector force = new Vector(x, y, z);
        if (loadedShip(serverLevel, shipId).isEmpty()) return false;
        invoke(adapter(serverLevel), "applyWorldForce", shipId, joml(force), null);
        return true;
    }

    /** Queue one physics-tick torque; see applyWorldForce for lifetime semantics. */
    public boolean applyWorldTorque(Object serverLevel, long shipId, double x, double y, double z) {
        Vector torque = new Vector(x, y, z);
        if (loadedShip(serverLevel, shipId).isEmpty()) return false;
        invoke(adapter(serverLevel), "applyWorldTorque", shipId, joml(torque));
        return true;
    }

    /**
     * Change the authoritative ServerShip state, as VS's own set-static command
     * does. A physics-only GTPA toggle is transient: the next game frame can
     * overwrite it with the unchanged ServerShip flag. VS propagates this game
     * state to physics through its ordinary pipeline. Call on the server thread.
     */
    public boolean setStatic(Object serverLevel, long shipId, boolean isStatic) {
        if (loadedShip(serverLevel, shipId).isEmpty()) return false;
        return setStaticInGameState(serverLevel, shipId, isStatic);
    }

    /** Persistently park an identified server ship for test cleanup, even before it is physics-loaded. */
    public boolean parkInGameState(Object serverLevel, long shipId) {
        return setStaticInGameState(serverLevel, shipId, true);
    }

    public boolean setStaticInGameState(Object serverLevel, long shipId, boolean isStatic) {
        Object world = invoke(resolve().utils(), "getShipObjectWorld", serverLevel);
        Object ship = invoke(invoke(world, "getAllShips"), "getById", shipId);
        if (ship == null || !dimensionId(serverLevel).equals(String.valueOf(invoke(ship, "getChunkClaimDimension")))) return false;
        invoke(ship, "setStatic", isStatic);
        return true;
    }

    /**
     * Assemble an explicitly selected set of blocks. VS moves the original
     * blocks into ship storage. The caller owns permission, loaded-region,
     * assembly-blacklist, world-border and size checks and must restrict this
     * operation to an authorized selection. Never call on arbitrary terrain.
     * The returned ship may take several ticks to enter the loaded collection.
     */
    public long assemble(Object serverLevel, Set<?> selectedBlocks, double scale) {
        return assemble(serverLevel, selectedBlocks, scale, false);
    }

    /**
     * Assemble with an explicit parked state. When startStatic is true the
     * returned ServerShip is parked immediately in the same server-thread
     * transaction, before waiting for its loaded/physics representation. This
     * prevents a newly assembled ship falling before its pilot can engage it.
     */
    public long assemble(Object serverLevel, Set<?> selectedBlocks, double scale, boolean startStatic) {
        Objects.requireNonNull(serverLevel, "serverLevel");
        Objects.requireNonNull(selectedBlocks, "selectedBlocks");
        if (selectedBlocks.isEmpty()) throw new IllegalArgumentException("Cannot assemble an empty selection.");
        if (!Double.isFinite(scale) || scale <= 0 || scale > 16) {
            throw new IllegalArgumentException("Assembly scale must be finite and between 0 and 16.");
        }
        resolve();
        Class<?> assembler = load(ASSEMBLER);
        Object ship = invoke(assembler, "assembleToShip", serverLevel, Set.copyOf(selectedBlocks), scale);
        if (ship == null) throw new BackendException("Valkyrien Skies assembly returned no ship.");
        if (startStatic) invoke(ship, "setStatic", true);
        return number(invoke(ship, "getId"), "ship id").longValue();
    }

    private Optional<Object> loadedShip(Object serverLevel, long shipId) {
        Objects.requireNonNull(serverLevel, "serverLevel");
        Bindings api = resolve();
        Object world = invoke(api.utils(), "getShipObjectWorld", serverLevel);
        Object loaded = invoke(world, "getLoadedShips");
        Object ship = invoke(loaded, "getById", shipId);
        if (ship == null) return Optional.empty();
        String expectedDimension = String.valueOf(invoke(api.utils(), "getDimensionId", serverLevel));
        String actualDimension = String.valueOf(invoke(ship, "getChunkClaimDimension"));
        return expectedDimension.equals(actualDimension) ? Optional.of(ship) : Optional.empty();
    }

    private Object adapter(Object serverLevel) {
        Bindings api = resolve();
        Object dimension = invoke(api.utils(), "getDimensionId", serverLevel);
        return invoke(api.mod(), "getOrCreateGTPA", dimension);
    }

    private String dimensionId(Object serverLevel) {
        Objects.requireNonNull(serverLevel, "serverLevel");
        return String.valueOf(invoke(resolve().utils(), "getDimensionId", serverLevel));
    }

    private Snapshot snapshot(Object ship) {
        long id = number(invoke(ship, "getId"), "ship id").longValue();
        Object inertia = invoke(ship, "getInertiaData");
        double mass = finite(number(invoke(inertia, "getMass"), "mass").doubleValue(), "mass");
        if (mass < 0) throw new BackendException("Valkyrien Skies returned negative ship mass.");
        Object transform = invoke(ship, "getTransform");
        Object rotation = invoke(transform, "getShipToWorldRotation");
        Object slug = invoke(ship, "getSlug");
        return new Snapshot(id, slug == null ? "ship-" + id : slug.toString(),
                String.valueOf(invoke(ship, "getChunkClaimDimension")), mass,
                vector(invoke(transform, "getPositionInWorld")), vector(invoke(ship, "getVelocity")),
                vector(invoke(ship, "getAngularVelocity")),
                new Rotation(component(rotation, "x"), component(rotation, "y"),
                        component(rotation, "z"), component(rotation, "w")),
                Boolean.TRUE.equals(invoke(ship, "isStatic")));
    }

    private Vector vector(Object value) {
        return new Vector(component(value, "x"), component(value, "y"), component(value, "z"));
    }

    private double component(Object value, String name) {
        return finite(number(invoke(value, name), name).doubleValue(), name);
    }

    private Object joml(Vector vector) {
        try {
            return resolve().vectorConstructor().newInstance(vector.x(), vector.y(), vector.z());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw failure("Cannot construct the VS force vector", exception);
        }
    }

    private Bindings resolve() {
        Bindings cached = bindings;
        if (cached != null) return cached;
        synchronized (this) {
            if (bindings != null) return bindings;
            try {
                Class<?> utils = load(UTILS);
                Class<?> mod = load(MOD);
                Constructor<?> vector = load("org.joml.Vector3d")
                        .getConstructor(double.class, double.class, double.class);
                // These Kotlin classes also declare client-only method signatures.
                // Do not enumerate getMethods() here: on a dedicated server that
                // could resolve absent Minecraft client classes. Bind only the
                // exact server-side signatures observed in VS 2.4.11 instead.
                Class<?> serverLevel = load("net.minecraft.server.level.ServerLevel");
                Class<?> level = load("net.minecraft.world.level.Level");
                Map<StaticKey, MethodHandle> entryPoints = Map.of(
                        new StaticKey(utils, "getLoadedShipManagingPos"), bindStatic(utils, "getLoadedShipManagingPos",
                                load("org.valkyrienskies.core.api.ships.LoadedServerShip"), serverLevel,
                                load("net.minecraft.core.Vec3i")),
                        new StaticKey(utils, "getShipObjectWorld"), bindStatic(utils, "getShipObjectWorld",
                                load("org.valkyrienskies.core.internal.world.VsiServerShipWorld"), serverLevel),
                        new StaticKey(utils, "getDimensionId"), bindStatic(utils, "getDimensionId", String.class, level),
                        new StaticKey(utils, "getVsCore"), bindStatic(utils, "getVsCore",
                                load("org.valkyrienskies.core.internal.VsiCore")),
                        new StaticKey(mod, "getOrCreateGTPA"), bindStatic(mod, "getOrCreateGTPA",
                                load("org.valkyrienskies.mod.common.util.GameToPhysicsAdapter"), String.class),
                        new StaticKey(mod, "getApi"), bindStatic(mod, "getApi",
                                load("org.valkyrienskies.mod.api_impl.events.VsApiImpl")));
                MethodHandle physicsEventGetter = MethodHandles.publicLookup().findVirtual(
                        load("org.valkyrienskies.mod.api_impl.events.VsApiImpl"), "getPhysTickEvent",
                        MethodType.methodType(load("org.valkyrienskies.core.api.event.ListenableEvent")));
                return bindings = new Bindings(utils, mod, vector, entryPoints, physicsEventGetter);
            } catch (ReflectiveOperationException | LinkageError exception) {
                throw failure("Valkyrien Skies API could not be initialized", exception);
            }
        }
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException exception) {
            throw new BackendException("Valkyrien Skies is absent or incompatible: missing " + name, exception);
        }
    }

    private Object invoke(Object target, String name, Object... arguments) {
        Objects.requireNonNull(target, "Cannot invoke " + name + " on null");
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Bindings current = bindings;
        MethodHandle exact = target instanceof Class<?> && current != null
                ? current.entryPoints().get(new StaticKey(type, name)) : null;
        if (!(target instanceof Class<?>) && name.equals("getPhysTickEvent") && current != null
                && current.physicsEventGetter() != null) {
            exact = current.physicsEventGetter().bindTo(target);
        }
        if (exact != null) {
            try {
                return exact.invokeWithArguments(arguments);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable exception) {
                throw failure("Valkyrien Skies operation failed: " + name, exception);
            }
        }
        List<Class<?>> argumentTypes = Arrays.stream(arguments)
                .map(argument -> argument == null ? Void.class : argument.getClass()).toList();
        MethodKey key = new MethodKey(type, name, argumentTypes);
        Method method = methods.computeIfAbsent(key, ignored -> find(type, name, arguments));
        try {
            Object receiver = Modifier.isStatic(method.getModifiers()) ? null
                    : target instanceof Class<?> ? type.getField("INSTANCE").get(null) : target;
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException exception) {
            throw failure("Valkyrien Skies operation failed: " + name, exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw failure("Valkyrien Skies API mismatch: " + name, exception);
        }
    }

    private static Method find(Class<?> type, String name, Object[] arguments) {
        Method selected = Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name))
                .filter(method -> method.getParameterCount() == arguments.length)
                .filter(method -> matches(method.getParameterTypes(), arguments))
                .min(Comparator.comparingInt(method -> score(method.getParameterTypes(), arguments)))
                .orElseThrow(() -> new BackendException("Valkyrien Skies API mismatch: "
                        + type.getName() + "." + name + " with " + arguments.length + " arguments."));
        if (Modifier.isPublic(selected.getDeclaringClass().getModifiers())) return selected;
        // VS returns lambda/private implementation objects for some public APIs,
        // notably RegisteredListener. Invoke their public interface declaration
        // instead of trying to make a hidden implementation class accessible.
        Method contract = publicContract(type, name, selected.getParameterTypes());
        if (contract != null) return contract;
        throw new BackendException("No accessible public contract for " + type.getName() + "." + name);
    }

    private static Method publicContract(Class<?> type, String name, Class<?>[] parameters) {
        for (Class<?> contract : type.getInterfaces()) {
            if (Modifier.isPublic(contract.getModifiers())) {
                try { return contract.getMethod(name, parameters); }
                catch (NoSuchMethodException ignored) { /* Try its parents. */ }
            }
            Method inherited = publicContract(contract, name, parameters);
            if (inherited != null) return inherited;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : publicContract(parent, name, parameters);
    }

    private static MethodHandle bindStatic(Class<?> type, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException, IllegalAccessException {
        return MethodHandles.publicLookup().findStatic(type, name, MethodType.methodType(result, parameters));
    }

    private static boolean matches(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            if (arguments[index] == null) {
                if (parameters[index].isPrimitive()) return false;
            } else if (!boxed(parameters[index]).isInstance(arguments[index])) return false;
        }
        return true;
    }

    private static int score(Class<?>[] parameters, Object[] arguments) {
        int score = 0;
        for (int index = 0; index < parameters.length; index++) {
            if (arguments[index] != null && boxed(parameters[index]) != arguments[index].getClass()) score++;
        }
        return score;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Number number(Object value, String field) {
        if (value instanceof Number number) return number;
        throw new BackendException("Valkyrien Skies returned non-numeric " + field + ".");
    }

    private static double finite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite.");
        return value;
    }

    private static BackendException failure(String message, Throwable exception) {
        return new BackendException(message + ": " + exception.getClass().getSimpleName()
                + (exception.getMessage() == null ? "" : " (" + exception.getMessage() + ")"), exception);
    }

    public record Probe(boolean available, String detail) { }
    public record PhysicsDiagnostics(long callbacks, long appliedForces, long missingBodies,
                                     long staticBodies, double lastDelta, String lastDimension, String lastBodyState, String subscription) { }
    public record StorageBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
    public record WorldBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean contains(double x, double y, double z, double margin) {
            return x >= minX - margin && x <= maxX + margin && y >= minY - margin && y <= maxY + margin
                    && z >= minZ - margin && z <= maxZ + margin;
        }
    }
    public record Vector(double x, double y, double z) {
        public Vector {
            finite(x, "x"); finite(y, "y"); finite(z, "z");
        }
    }
    public record Rotation(double x, double y, double z, double w) {
        public Rotation {
            finite(x, "x"); finite(y, "y"); finite(z, "z"); finite(w, "w");
        }
    }
    public record Snapshot(long shipId, String name, String dimensionId, double massKg,
                           Vector worldPosition, Vector velocity, Vector angularVelocity,
                           Rotation rotation, boolean isStatic) {
        public double mass() { return massKg; }
        public Vector position() { return worldPosition; }
    }
    public static final class BackendException extends RuntimeException {
        public BackendException(String message) { super(message); }
        public BackendException(String message, Throwable cause) { super(message, cause); }
    }
    private record Bindings(Class<?> utils, Class<?> mod, Constructor<?> vectorConstructor,
                            Map<StaticKey, MethodHandle> entryPoints, MethodHandle physicsEventGetter) { }
    private record StaticKey(Class<?> type, String name) { }
    private record MethodKey(Class<?> type, String name, List<Class<?>> arguments) { }
    private record ControlKey(String dimensionId, long shipId) { }
    private record AccelerationRequest(Vector acceleration, long expiresAtNanos, long generation,
                                       double yawRate, boolean stabilize) { }
    private record PhysicsAcknowledgement(long generation, long receivedAtNanos) { }
}
