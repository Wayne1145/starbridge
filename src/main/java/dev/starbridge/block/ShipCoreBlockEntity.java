package dev.starbridge.block;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.flight.*;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One owned flight computer and energy-limited vector drive per controlled ship. */
public final class ShipCoreBlockEntity extends BlockEntity {
    private static final VsShipBackend PHYSICS = new VsShipBackend();
    private final AutopilotController autopilot = new AutopilotController();
    private final CoreEnergy energy = new CoreEnergy();
    private final LaunchHandshake launch = new LaunchHandshake(40);
    private final PhysicsHeartbeatGuard physicsHeartbeat = new PhysicsHeartbeatGuard(3_000_000_000L);
    private LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> energy);
    private UUID id = UUID.randomUUID();
    private UUID owner;
    private UUID paidVoyage;
    private int pairedComputer = -1;
    private boolean agentAllowed;
    private String lastError = "";
    private VsShipBackend.Snapshot snapshot;
    private ServerLevel activeLevel;
    private long activeShipId;
    private boolean forceSubmitted;
    private boolean physicsAcknowledged;
    private long launchShipId;
    private ControlSource controlSource = ControlSource.LOCAL;
    private enum ControlSource { LOCAL, COMPUTER, AGENT }
    private static final long DRIVE_LEASE_MILLIS = 250;
    private static final long PHYSICS_ACK_MAX_AGE_MILLIS = 1_000;
    private Vec3d manualAcceleration;
    private long manualUntil;
    private double gravityCompensation = 10.0;
    private boolean gravityOverride;

    public ShipCoreBlockEntity(BlockPos pos, BlockState state) { super(Starbridge.SHIP_CORE_ENTITY.get(), pos, state); }
    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public void setOwner(UUID owner) {
        release();
        this.owner = Objects.requireNonNull(owner, "owner");
        pairedComputer = -1;
        agentAllowed = false;
        setChanged();
    }
    /** Called by the loaded-core registry when copied NBT would shadow a live core. */
    public void assignFreshIdentity(UUID replacement) {
        release();
        id = Objects.requireNonNull(replacement, "replacement");
        pairedComputer = -1;
        agentAllowed = false;
        lastError = "Copied core identity replaced; pair computers and authorize agents again";
        setChanged();
    }
    public boolean authorized(Player player) { return player.getUUID().equals(owner) || player.hasPermissions(2); }
    public int energy() { return energy.getEnergyStored(); }
    /** Replay-safe debit tied to the physical core; saved with its shipyard block entities. */
    public boolean payForPassage(UUID voyage, int amount) {
        Objects.requireNonNull(voyage);
        if (amount <= 0) throw new IllegalArgumentException("Fare must be positive");
        if (voyage.equals(paidVoyage)) return true;
        if (energy.extractEnergy(amount, true) != amount) return false;
        energy.extractEnergy(amount, false);
        paidVoyage = voyage;
        setChanged();
        return true;
    }
    public int pairedComputer() { return pairedComputer; }
    public void pairComputer(int computer) {
        if (computer < -1) throw new IllegalArgumentException("Invalid computer ID");
        if (computer != pairedComputer && controlSource == ControlSource.COMPUTER) release();
        pairedComputer = computer; setChanged();
    }
    public boolean agentAllowed() { return owner != null && agentAllowed && loadedForControl(); }
    public void setAgentAllowed(boolean enabled) {
        if (enabled && owner == null) throw new IllegalStateException("An unowned core cannot grant agent access");
        if (!enabled && controlSource == ControlSource.AGENT) release();
        agentAllowed = enabled; setChanged();
    }
    public void register() { if (level != null && !level.isClientSide) CoreRegistry.register(this); }
    public void setGravityCompensation(double acceleration) {
        if (!Double.isFinite(acceleration) || acceleration < 0 || acceleration > 30) throw new IllegalArgumentException("Gravity compensation must be between 0 and 30 blocks/s²");
        gravityCompensation = acceleration;
        gravityOverride = true;
        setChanged();
    }

    public boolean addFuel(ItemStack stack, boolean creative) {
        int burn = ForgeHooks.getBurnTime(stack, null);
        if (burn <= 0 || energy.getEnergyStored() >= energy.getMaxEnergyStored()) return false;
        int added = energy.receiveEnergy((int) Math.min(200_000L, (long) burn * 100), false);
        if (added > 0 && !creative) stack.shrink(1);
        setChanged();
        return added > 0;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ShipCoreBlockEntity core) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        core.register();
        long tick = serverLevel.getGameTime();
        boolean active = core.launch.pending() || core.autopilot.mode() != FlightMode.IDLE || core.manualAcceleration != null;
        if (!active && tick % 20 != 0) return;
        try {
            core.snapshot = PHYSICS.inspect(serverLevel, pos).orElse(null);
            if (core.snapshot == null) {
                if (active || core.lastError.isEmpty())
                    core.lastError = PHYSICS.probe().available() ? "Core is not on an assembled ship" : PHYSICS.probe().detail();
                core.release();
                return;
            }
            if (!active) { core.releaseOwnedDrive(); return; }
            boolean launchConfirmed = false;
            if (core.launch.pending()) {
                if (core.snapshot.shipId() != core.launchShipId)
                    throw new IllegalStateException("Ship changed during launch; launch cancelled");
                LaunchHandshake.State launchState = core.launch.observe(tick, core.snapshot.isStatic());
                if (launchState == LaunchHandshake.State.TIMED_OUT)
                    throw new IllegalStateException("Launch was not confirmed within 40 ticks; ship returned to static docking");
                launchConfirmed = launchState == LaunchHandshake.State.READY;
            } else if (core.snapshot.isStatic()) {
                throw new IllegalStateException("Ship is statically docked; use /starbridge launch before engaging the drive");
            }
            core.claimDrive(serverLevel);
            Vec3d velocity = vector(core.snapshot.velocity());
            Vec3d acceleration;
            if (core.manualAcceleration != null && tick <= core.manualUntil) {
                acceleration = core.manualAcceleration;
            } else {
                if (core.manualAcceleration != null) {
                    core.manualAcceleration = null;
                    core.autopilot.holdAt(vector(core.snapshot.worldPosition()), tick);
                }
                core.autopilot.heartbeat(tick);
                FlightCommand command = core.autopilot.update(new Telemetry(vector(core.snapshot.worldPosition()), velocity, tick));
                acceleration = command.acceleration();
            }
            core.submitPaidThrust(serverLevel, acceleration.subtract(core.gravity(serverLevel)));
            if (launchConfirmed) core.launch.complete();
            core.lastError = "";
            if (tick % 20 == 0) core.setChanged();
        } catch (RuntimeException | LinkageError failure) {
            if (active || core.lastError.isEmpty())
                core.lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            core.release();
        }
    }

    private static Vec3d vector(VsShipBackend.Vector value) { return new Vec3d(value.x(), value.y(), value.z()); }
    public boolean loadedForControl() {
        return level instanceof ServerLevel server && !isRemoved() && server.hasChunkAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this;
    }
    private void requireFlightReady() { requireFlightReady(false); }
    private void requireFlightReady(boolean allowStaticLaunch) {
        if (!(level instanceof ServerLevel serverLevel) || !loadedForControl()) throw new IllegalStateException("Core is not loaded on a server");
        if (launch.pending()) throw new IllegalStateException("Launch is awaiting physics confirmation; wait or use /starbridge release to cancel");
        register();
        try {
            snapshot = PHYSICS.inspect(serverLevel, worldPosition).orElseThrow(() -> new IllegalStateException("Assemble a Valkyrien Skies ship containing this core first"));
        } catch (LinkageError unavailable) {
            throw new IllegalStateException("Physics backend is incompatible with this server", unavailable);
        }
        if (snapshot.isStatic() && !allowStaticLaunch)
            throw new IllegalStateException("Ship is statically docked; use /starbridge launch first");
        if (energy() <= 0) throw new IllegalStateException("Supply Forge Energy or right-click the core with furnace fuel");
    }
    /**
     * Owner/operator command entry point. Prepare a paid hover request before requesting the
     * native static transition. This does not alter VS loading/voxel freeze safeguards.
     * The normal game tick confirms the transition within 40 ticks and continues HOLD.
     */
    public void launch() {
        requireFlightReady(true);
        if (owner == null) throw new IllegalStateException("An unowned core cannot launch");
        if (!snapshot.isStatic()) throw new IllegalStateException("Ship is already dynamic; use /starbridge hold or navigate");
        ServerLevel serverLevel = (ServerLevel) level;
        Vec3d lift = Vec3d.ZERO.subtract(gravity(serverLevel));
        requireLeaseEnergy(lift); // Failure leaves the original static ship and other controls untouched.
        claimDrive(serverLevel);
        launchShipId = snapshot.shipId();
        launch.begin(serverLevel.getGameTime());
        try {
            controlSource = ControlSource.LOCAL;
            manualAcceleration = null;
            autopilot.holdAt(vector(snapshot.worldPosition()), serverLevel.getGameTime());
            submitPaidThrust(serverLevel, lift);
            if (!PHYSICS.setStatic(serverLevel, launchShipId, false))
                throw new IllegalStateException("Physics backend did not accept the launch transition");
            lastError = "";
        } catch (RuntimeException | LinkageError failure) {
            lastError = "Launch failed; static docking restored where the ship remains loaded";
            release();
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Physics backend could not launch this ship", failure);
        }
    }
    private Vec3d gravity(ServerLevel serverLevel) {
        try { return gravityOverride ? new Vec3d(0, -gravityCompensation, 0) : vector(PHYSICS.gravity(serverLevel)); }
        catch (LinkageError unavailable) { throw new IllegalStateException("Physics gravity API is incompatible with this server", unavailable); }
    }
    private int requireLeaseEnergy(Vec3d thrustAcceleration) {
        double mass = snapshot.massKg();
        if (!(mass > 0) || !Double.isFinite(mass)) throw new IllegalStateException("Physics engine reported invalid mass");
        if (thrustAcceleration.length() > 128) throw new IllegalStateException("Required thrust exceeds the backend acceleration limit");
        // Prepay the whole wall-clock lease, including its tail when game ticks stall.
        double tickCost = 10 + mass * thrustAcceleration.length() / 100_000;
        int cost = (int) Math.min(Integer.MAX_VALUE, Math.ceil(tickCost * DRIVE_LEASE_MILLIS / 50.0));
        if (energy.extractEnergy(cost, true) != cost) throw new IllegalStateException("Insufficient energy; drive disengaged");
        return cost;
    }
    private void submitPaidThrust(ServerLevel serverLevel, Vec3d thrustAcceleration) {
        if (forceSubmitted) {
            physicsAcknowledged = PHYSICS.physicsAcknowledged(serverLevel, snapshot.shipId(), PHYSICS_ACK_MAX_AGE_MILLIS);
            if (!physicsHeartbeat.allowRefresh(System.nanoTime(), physicsAcknowledged)) {
                throw new IllegalStateException("Ship physics stopped acknowledging thrust; drive disengaged before consuming more energy");
            }
        }
        int cost = requireLeaseEnergy(thrustAcceleration);
        energy.extractEnergy(cost, false);
        if (!PHYSICS.setWorldAcceleration(serverLevel, snapshot.shipId(), thrustAcceleration.x(), thrustAcceleration.y(), thrustAcceleration.z(), DRIVE_LEASE_MILLIS))
            throw new IllegalStateException("Physics backend rejected drive force");
        forceSubmitted = true;
        physicsHeartbeat.submitted(System.nanoTime());
    }
    public void navigate(Vec3d target) {
        Objects.requireNonNull(target, "target");
        if (Math.abs(target.x()) > 29_000_000 || Math.abs(target.z()) > 29_000_000 || Math.abs(target.y()) > 8192)
            throw new IllegalArgumentException("Destination is outside flight limits");
        requireFlightReady();
        if (target.subtract(vector(snapshot.worldPosition())).length() > 50_000) throw new IllegalArgumentException("A waypoint must be within 50,000 blocks");
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        manualAcceleration = null;
        autopilot.navigateTo(target, level.getGameTime());
        lastError = "";
    }
    public void hold() {
        requireFlightReady();
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        manualAcceleration = null;
        autopilot.holdAt(vector(snapshot.worldPosition()), level.getGameTime());
        lastError = "";
    }
    /** Brakes using available energy; it cannot create free thrust if the core runs out of fuel. */
    public void stop() {
        if (launch.pending()) {
            release();
            lastError = "Launch cancelled; ship returned to static docking";
            return;
        }
        controlSource = ControlSource.LOCAL;
        manualAcceleration = null;
        autopilot.emergencyStop();
    }
    public void release() {
        if (launch.pending() && activeLevel != null
                && CoreRegistry.owns(activeLevel.dimension().location().toString(), activeShipId, this)) {
            try { PHYSICS.setStatic(activeLevel, activeShipId, true); }
            catch (RuntimeException | LinkageError ignored) { /* Never supply unpaid force if rollback is unavailable. */ }
        }
        launch.cancel();
        releaseOwnedDrive();
        manualAcceleration = null;
        autopilot.manualTakeover();
        controlSource = ControlSource.LOCAL;
    }
    private void claimDrive(ServerLevel serverLevel) {
        if (activeLevel != null && (activeLevel != serverLevel || activeShipId != snapshot.shipId())) releaseOwnedDrive();
        if (!CoreRegistry.claim(serverLevel.dimension().location().toString(), snapshot.shipId(), this))
            throw new IllegalStateException("Another core is already controlling this ship");
        activeLevel = serverLevel;
        activeShipId = snapshot.shipId();
    }
    private void releaseOwnedDrive() {
        if (activeLevel != null && forceSubmitted
                && CoreRegistry.owns(activeLevel.dimension().location().toString(), activeShipId, this)) {
            try { PHYSICS.clearWorldAcceleration(activeLevel, activeShipId); }
            catch (RuntimeException | LinkageError ignored) { /* The bounded physics lease still expires. */ }
        }
        forceSubmitted = false;
        physicsAcknowledged = false;
        physicsHeartbeat.reset();
        activeLevel = null;
        CoreRegistry.release(this);
    }
    public void jog(Vec3d acceleration, int ticks) {
        Objects.requireNonNull(acceleration, "acceleration");
        if (ticks < 1 || ticks > 40) throw new IllegalArgumentException("Manual input lasts 1–40 ticks");
        requireFlightReady();
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        autopilot.manualTakeover();
        manualAcceleration = acceleration.clampLength(3);
        manualUntil = level.getGameTime() + ticks;
    }
    public String shortStatus() { return "FE " + energy() + " · " + (launch.pending() ? "LAUNCHING" : autopilot.mode()) + (lastError.isEmpty() ? "" : " · " + lastError); }
    public void requireComputerAccess(int computer, boolean mutating) {
        if (!loadedForControl()) throw new IllegalStateException("Flight core is unloaded");
        if (owner == null || pairedComputer < 0 || pairedComputer != computer)
            throw new IllegalStateException("Computer is not paired; the ship owner must pair its ID in game");
        if (mutating) requireNoHumanPilot();
    }
    private void requireAgentAccess() {
        if (!agentAllowed()) throw new IllegalStateException("Agent access is not enabled for this loaded core");
        requireNoHumanPilot();
    }
    private void requireNoHumanPilot() {
        if (dev.starbridge.network.PilotModule.isBeingPiloted(id))
            throw new IllegalStateException("A player is piloting this ship; manual control has priority");
    }
    public void navigateFromAgent(Vec3d target) { requireAgentAccess(); navigate(target); controlSource = ControlSource.AGENT; }
    public void holdFromAgent() { requireAgentAccess(); hold(); controlSource = ControlSource.AGENT; }
    public void stopFromAgent() { requireAgentAccess(); stop(); controlSource = ControlSource.AGENT; }
    public void navigateFromComputer(int computer, Vec3d target) { requireComputerAccess(computer, true); navigate(target); controlSource = ControlSource.COMPUTER; }
    public void holdFromComputer(int computer) { requireComputerAccess(computer, true); hold(); controlSource = ControlSource.COMPUTER; }
    public void stopFromComputer(int computer) { requireComputerAccess(computer, true); stop(); controlSource = ControlSource.COMPUTER; }
    public static void startPhysics() {
        if (!PHYSICS.probe().available()) return;
        try { PHYSICS.startPhysicsListener(); }
        catch (RuntimeException | LinkageError failure) { Starbridge.LOGGER.warn("Optional physics listener could not start", failure); }
    }
    public static void shutdownPhysics() {
        for (ShipCoreBlockEntity core : CoreRegistry.all()) core.release();
        try { PHYSICS.close(); }
        catch (RuntimeException | LinkageError ignored) { PHYSICS.clearAllControls(); }
    }
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("id", id.toString());
        status.put("owner", owner == null ? "" : owner.toString());
        status.put("dimension", level == null ? "" : level.dimension().location().toString());
        status.put("energy", energy());
        status.put("capacity", energy.getMaxEnergyStored());
        status.put("mode", launch.pending() ? "LAUNCHING" : manualAcceleration == null ? autopilot.mode().name() : "MANUAL");
        status.put("launchPending", launch.pending());
        status.put("physicsAcknowledged", physicsAcknowledged);
        String backend;
        try { backend = PHYSICS.probe().detail(); }
        catch (RuntimeException | LinkageError unavailable) { backend = "Physics backend unavailable"; }
        status.put("backend", backend);
        status.put("agentAllowed", agentAllowed);
        status.put("pairedComputer", pairedComputer);
        status.put("lastError", lastError);
        status.put("gravityCompensation", gravityCompensation);
        status.put("gravityOverride", gravityOverride);
        status.put("physicsError", PHYSICS.lastPhysicsError().orElse(""));
        if (snapshot != null) {
            status.put("shipId", snapshot.shipId());
            status.put("static", snapshot.isStatic());
            status.put("massKg", snapshot.massKg());
            status.put("x", snapshot.worldPosition().x()); status.put("y", snapshot.worldPosition().y()); status.put("z", snapshot.worldPosition().z());
            status.put("vx", snapshot.velocity().x()); status.put("vy", snapshot.velocity().y()); status.put("vz", snapshot.velocity().z());
        }
        return status;
    }

    @Override public void load(CompoundTag tag) {
        release();
        CoreRegistry.unregister(this);
        snapshot = null;
        super.load(tag);
        if (tag.hasUUID("CoreId")) id = tag.getUUID("CoreId");
        paidVoyage = tag.hasUUID("PaidVoyage") ? tag.getUUID("PaidVoyage") : null;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        pairedComputer = owner != null && tag.contains("Computer") ? Math.max(-1, tag.getInt("Computer")) : -1;
        agentAllowed = owner != null && tag.getBoolean("AgentAllowed");
        gravityCompensation = tag.contains("GravityCompensation") ? tag.getDouble("GravityCompensation") : 10;
        gravityOverride = tag.getBoolean("GravityOverride");
        if (!Double.isFinite(gravityCompensation) || gravityCompensation < 0 || gravityCompensation > 30) gravityCompensation = 10;
        energy.restore(tag.getInt("Energy"));
        // Reloading a world never silently starts a drive.
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID("CoreId", id);
        if (owner != null) tag.putUUID("Owner", owner);
        if (paidVoyage != null) tag.putUUID("PaidVoyage", paidVoyage);
        tag.putInt("Computer", pairedComputer);
        tag.putBoolean("AgentAllowed", agentAllowed);
        tag.putDouble("GravityCompensation", gravityCompensation);
        tag.putBoolean("GravityOverride", gravityOverride);
        tag.putInt("Energy", energy());
    }
    @Override public void setRemoved() { release(); CoreRegistry.unregister(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { release(); CoreRegistry.unregister(this); super.onChunkUnloaded(); }
    @Override public void invalidateCaps() { super.invalidateCaps(); energyCapability.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); energyCapability = LazyOptional.of(() -> energy); }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return capability == ForgeCapabilities.ENERGY ? energyCapability.cast() : super.getCapability(capability, side);
    }
    private final class CoreEnergy extends EnergyStorage {
        private CoreEnergy() { super(2_000_000, 200_000, Integer.MAX_VALUE); }
        private void restore(int value) { energy = Math.max(0, Math.min(capacity, value)); }
        @Override public int receiveEnergy(int requested, boolean simulate) {
            if (requested <= 0) return 0;
            int received = super.receiveEnergy(requested, simulate);
            if (!simulate && received > 0) setChanged();
            return received;
        }
        @Override public int extractEnergy(int requested, boolean simulate) {
            if (requested <= 0) return 0;
            int extracted = super.extractEnergy(requested, simulate);
            if (!simulate && extracted > 0) setChanged();
            return extracted;
        }
    }
}

/** Tracks only native launch acknowledgement, never simulates or overrides physical readiness. */
final class LaunchHandshake {
    enum State { WAITING, READY, TIMED_OUT }
    private final long maximumWaitTicks;
    private long startedTick;
    private boolean pending;

    LaunchHandshake(long maximumWaitTicks) {
        if (maximumWaitTicks < 1) throw new IllegalArgumentException("Launch wait must be positive");
        this.maximumWaitTicks = maximumWaitTicks;
    }

    void begin(long tick) {
        if (tick < 0 || pending) throw new IllegalStateException("Launch already pending or server tick invalid");
        startedTick = tick;
        pending = true;
    }

    State observe(long tick, boolean isStatic) {
        if (!pending) throw new IllegalStateException("No launch is pending");
        if (tick < startedTick || tick - startedTick > maximumWaitTicks) return State.TIMED_OUT;
        if (!isStatic) return State.READY;
        return tick - startedTick >= maximumWaitTicks ? State.TIMED_OUT : State.WAITING;
    }

    boolean pending() { return pending; }
    /** Only complete after dynamic acknowledgement and a successfully paid hover refresh. */
    void complete() { pending = false; }
    void cancel() { pending = false; }
}

/** Wall-clock guard: a submitted request is not proof that a native physics body received force. */
final class PhysicsHeartbeatGuard {
    private final long startupGraceNanos;
    private long firstSubmissionNanos;
    private boolean started;
    private boolean everAcknowledged;

    PhysicsHeartbeatGuard(long startupGraceNanos) {
        if (startupGraceNanos <= 0) throw new IllegalArgumentException("Physics startup grace must be positive");
        this.startupGraceNanos = startupGraceNanos;
    }

    /** Repeated commands or successful queue writes cannot extend the initial acknowledgement budget. */
    void submitted(long nowNanos) {
        if (!started) {
            firstSubmissionNanos = nowNanos;
            started = true;
        }
    }

    boolean allowRefresh(long nowNanos, boolean hasRecentShipAcknowledgement) {
        if (!started) return true;
        if (hasRecentShipAcknowledgement) {
            everAcknowledged = true;
            return true;
        }
        if (everAcknowledged) return false;
        long elapsed = nowNanos - firstSubmissionNanos;
        return elapsed >= 0 && elapsed < startupGraceNanos;
    }

    void reset() {
        started = false;
        everAcknowledged = false;
    }
}
