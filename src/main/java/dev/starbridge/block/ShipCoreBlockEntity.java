package dev.starbridge.block;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.content.ContentModule;
import dev.starbridge.content.LifeSupport;
import dev.starbridge.flight.*;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.text.Lang;
import dev.starbridge.travel.StarJump;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One owned flight computer and energy-limited vector drive per controlled ship. */
public final class ShipCoreBlockEntity extends BlockEntity {
    private static final VsShipBackend PHYSICS = new VsShipBackend();
    private static final long DRIVE_LEASE_MILLIS = 250;
    private static final long PHYSICS_ACK_MAX_AGE_MILLIS = 1_000;
    private static final double BASE_MANUAL_ACCELERATION = 3.0;
    private static final int LIFE_SUPPORT_FE_PER_PLAYER_SECOND = 20;
    private AutopilotController autopilot = new AutopilotController();
    private final CoreEnergy energy = new CoreEnergy();
    private final LaunchHandshake launch = new LaunchHandshake(40);
    private final PhysicsHeartbeatGuard physicsHeartbeat = new PhysicsHeartbeatGuard(3_000_000_000L);
    private final DriveLeaseMeter leaseMeter = new DriveLeaseMeter(DRIVE_LEASE_MILLIS * 1_000_000L);
    private final EnumMap<ShipUpgrade, Integer> upgrades = new EnumMap<>(ShipUpgrade.class);
    private LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> energy);
    private UUID id = UUID.randomUUID();
    private UUID owner;
    private int pairedComputer = -1;
    private boolean agentAllowed;
    private String lastError = "";
    private String lastErrorText = "";
    private VsShipBackend.Snapshot snapshot;
    private ServerLevel activeLevel;
    private long activeShipId;
    private boolean forceSubmitted;
    private boolean physicsAcknowledged;
    private long launchShipId;
    private ControlSource controlSource = ControlSource.LOCAL;
    private enum ControlSource { LOCAL, COMPUTER, AGENT }
    private Vec3d manualAcceleration;
    private double manualYawRate;
    private long manualUntil;
    private double gravityCompensation = 10.0;
    private boolean gravityOverride;
    private int lifeSupported;

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
        fail(Lang.error("copied_identity", "Copied core identity replaced; pair computers and authorize agents again"));
        setChanged();
    }
    public boolean authorized(Player player) { return player.getUUID().equals(owner) || player.hasPermissions(2); }
    public int energy() { return energy.getEnergyStored(); }
    public int capacity() { return energy.getMaxEnergyStored(); }
    public int pairedComputer() { return pairedComputer; }
    public int upgradeLevel(ShipUpgrade type) { return upgrades.getOrDefault(type, 0); }
    public void pairComputer(int computer) {
        if (computer < -1) throw Lang.invalid("invalid_computer", "Invalid computer ID");
        if (computer != pairedComputer && controlSource == ControlSource.COMPUTER) release();
        pairedComputer = computer; setChanged();
    }
    public boolean agentAllowed() { return owner != null && agentAllowed && loadedForControl(); }
    public void setAgentAllowed(boolean enabled) {
        if (enabled && owner == null) throw Lang.error("unowned_agent", "An unowned core cannot grant agent access");
        if (!enabled && controlSource == ControlSource.AGENT) release();
        agentAllowed = enabled; setChanged();
    }
    public void register() { if (level != null && !level.isClientSide) CoreRegistry.register(this); }
    public void setGravityCompensation(double acceleration) {
        if (!Double.isFinite(acceleration) || acceleration < 0 || acceleration > 30)
            throw Lang.invalid("gravity_range", "Gravity compensation must be between 0 and 30 blocks/s²");
        gravityCompensation = acceleration;
        gravityOverride = true;
        setChanged();
    }

    // ---------------------------------------------------------------- energy & modules

    /** Furnace fuel. Sneak to insert the whole stack. Returns FE added. */
    public int addFuel(ItemStack stack, boolean creative, boolean wholeStack) {
        int burn = ForgeHooks.getBurnTime(stack, null);
        if (burn <= 0) return 0;
        int perItem = (int) Math.min(Integer.MAX_VALUE, (long) burn * 100);
        int added = 0;
        int count = wholeStack ? stack.getCount() : 1;
        for (int i = 0; i < count && !stack.isEmpty(); i++) {
            if (capacity() - energy() < perItem / 2) break;
            added += energy.insert(perItem);
            if (!creative) {
                ItemStack remainder = stack.getCraftingRemainingItem();
                stack.shrink(1);
                if (!remainder.isEmpty() && stack.isEmpty()) break;
            }
        }
        if (added > 0) setChanged();
        return added;
    }

    /** Inserts one fuel item; true if any energy was added. */
    public boolean addFuel(ItemStack stack, boolean creative) { return addFuel(stack, creative, false) > 0; }

    /** Charges directly from a packaged FE source, such as a star fuel cell. */
    public int charge(int amount) {
        int added = energy.insert(amount);
        if (added > 0) setChanged();
        return added;
    }

    public boolean installUpgrade(ShipUpgrade type) {
        int current = upgradeLevel(type);
        if (current >= type.maxLevel()) return false;
        upgrades.put(type, current + 1);
        applyUpgrades();
        setChanged();
        return true;
    }

    private void applyUpgrades() {
        energy.resize(ShipUpgrade.capacity(upgradeLevel(ShipUpgrade.CAPACITOR)));
        FlightConfig base = FlightConfig.defaults();
        double factor = ShipUpgrade.thrustFactor(upgradeLevel(ShipUpgrade.THRUSTER));
        FlightConfig scaled = new FlightConfig(base.maxSpeed() * factor, base.maxAcceleration() * factor,
                base.positionTolerance(), base.velocityTolerance(), base.heartbeatTimeoutTicks());
        if (!scaled.equals(autopilot.config())) {
            // A new limit applies from the next command; never swap controllers mid-flight.
            if (autopilot.mode() != FlightMode.IDLE) release();
            autopilot = new AutopilotController(scaled);
        }
    }

    public double maxManualAcceleration() {
        return BASE_MANUAL_ACCELERATION * ShipUpgrade.thrustFactor(upgradeLevel(ShipUpgrade.THRUSTER));
    }

    private double energyFactor() { return ShipUpgrade.energyFactor(upgradeLevel(ShipUpgrade.EFFICIENCY)); }

    // ---------------------------------------------------------------- tick

    public static void tick(Level level, BlockPos pos, BlockState state, ShipCoreBlockEntity core) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        core.register();
        long tick = serverLevel.getGameTime();
        boolean active = core.launch.pending() || core.autopilot.mode() != FlightMode.IDLE || core.manualAcceleration != null;
        if (!active && tick % 20 != 0) return;
        try {
            core.snapshot = PHYSICS.inspect(serverLevel, pos).orElse(null);
            if (core.snapshot == null) {
                if (active || core.lastError.isEmpty()) {
                    if (PHYSICS.probe().available()) core.fail(Lang.error("not_on_ship", "Core is not on an assembled ship"));
                    else core.setError(PHYSICS.probe().detail(), PHYSICS.probe().detail());
                }
                core.release();
                return;
            }
            if (tick % 20 == 0) core.supplyLifeSupport(serverLevel, tick);
            if (!active) { core.releaseOwnedDrive(); return; }
            boolean launchConfirmed = false;
            if (core.launch.pending()) {
                if (core.snapshot.shipId() != core.launchShipId)
                    throw Lang.error("ship_changed", "Ship changed during launch; launch cancelled");
                LaunchHandshake.State launchState = core.launch.observe(tick, core.snapshot.isStatic());
                if (launchState == LaunchHandshake.State.TIMED_OUT)
                    throw Lang.error("launch_timeout", "Launch was not confirmed within 40 ticks; ship returned to static docking");
                launchConfirmed = launchState == LaunchHandshake.State.READY;
            } else if (core.snapshot.isStatic()) {
                throw Lang.error("docked", "Ship is statically docked; use /starbridge launch before engaging the drive");
            }
            core.claimDrive(serverLevel);
            Vec3d velocity = vector(core.snapshot.velocity());
            Vec3d acceleration;
            double yawRate = 0;
            if (core.manualAcceleration != null && tick <= core.manualUntil) {
                acceleration = core.manualAcceleration;
                yawRate = core.manualYawRate;
            } else {
                if (core.manualAcceleration != null) {
                    core.manualAcceleration = null;
                    core.manualYawRate = 0;
                    core.autopilot.holdAt(vector(core.snapshot.worldPosition()), tick);
                }
                core.autopilot.heartbeat(tick);
                FlightCommand command = core.autopilot.update(new Telemetry(vector(core.snapshot.worldPosition()), velocity, tick));
                acceleration = command.acceleration();
            }
            core.submitPaidThrust(serverLevel, acceleration.subtract(core.gravity(serverLevel)), yawRate);
            if (launchConfirmed) core.launch.complete();
            core.clearError();
            if (tick % 20 == 0) core.setChanged();
        } catch (RuntimeException | LinkageError failure) {
            if (active || core.lastError.isEmpty()) core.fail(failure);
            core.release();
        }
    }

    /** Powered hull air: players inside the ship's bounds can breathe on airless worlds. */
    private void supplyLifeSupport(ServerLevel serverLevel, long tick) {
        lifeSupported = 0;
        if (!serverLevel.dimension().equals(ContentModule.MOON) || snapshot == null || energy() <= 0) return;
        VsShipBackend.WorldBounds bounds;
        try { bounds = PHYSICS.worldBounds(serverLevel, snapshot.shipId()).orElse(null); }
        catch (RuntimeException | LinkageError unavailable) { return; }
        if (bounds == null) return;
        for (ServerPlayer player : serverLevel.players()) {
            if (!bounds.contains(player.getX(), player.getY(), player.getZ(), 1.5)) continue;
            if (energy.extractEnergy(LIFE_SUPPORT_FE_PER_PLAYER_SECOND, false) < LIFE_SUPPORT_FE_PER_PLAYER_SECOND) break;
            LifeSupport.grant(player, tick);
            lifeSupported++;
        }
    }

    private static Vec3d vector(VsShipBackend.Vector value) { return new Vec3d(value.x(), value.y(), value.z()); }
    public boolean loadedForControl() {
        return level instanceof ServerLevel server && !isRemoved() && server.hasChunkAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this;
    }
    private void requireFlightReady() { requireFlightReady(false); }
    private void requireFlightReady(boolean allowStaticLaunch) {
        if (!(level instanceof ServerLevel serverLevel) || !loadedForControl())
            throw Lang.error("not_loaded", "Core is not loaded on a server");
        if (launch.pending())
            throw Lang.error("launch_pending", "Launch is awaiting physics confirmation; wait or use /starbridge release to cancel");
        register();
        try {
            snapshot = PHYSICS.inspect(serverLevel, worldPosition).orElseThrow(() ->
                    Lang.error("assemble_first", "Assemble a Valkyrien Skies ship containing this core first"));
        } catch (LinkageError unavailable) {
            throw Lang.error("backend_incompatible", "Physics backend is incompatible with this server");
        }
        if (snapshot.isStatic() && !allowStaticLaunch)
            throw Lang.error("docked", "Ship is statically docked; use /starbridge launch first");
        if (energy() <= 0) throw Lang.error("no_energy", "Supply Forge Energy or right-click the core with furnace fuel");
    }
    /**
     * Owner/operator command entry point. Prepare a paid hover request before requesting the
     * native static transition. This does not alter VS loading/voxel freeze safeguards.
     * The normal game tick confirms the transition within 40 ticks and continues HOLD.
     */
    public void launch() {
        requireFlightReady(true);
        if (owner == null) throw Lang.error("unowned_launch", "An unowned core cannot launch");
        if (!snapshot.isStatic()) throw Lang.error("already_dynamic", "Ship is already dynamic; use /starbridge hold or navigate");
        ServerLevel serverLevel = (ServerLevel) level;
        Vec3d lift = Vec3d.ZERO.subtract(gravity(serverLevel));
        requireLeaseEnergy(lift, DRIVE_LEASE_MILLIS * 1_000_000L); // Failure leaves the original static ship untouched.
        claimDrive(serverLevel);
        launchShipId = snapshot.shipId();
        launch.begin(serverLevel.getGameTime());
        try {
            controlSource = ControlSource.LOCAL;
            manualAcceleration = null;
            autopilot.holdAt(vector(snapshot.worldPosition()), serverLevel.getGameTime());
            submitPaidThrust(serverLevel, lift, 0);
            if (!PHYSICS.setStatic(serverLevel, launchShipId, false))
                throw Lang.error("launch_rejected", "Physics backend did not accept the launch transition");
            clearError();
        } catch (RuntimeException | LinkageError failure) {
            release();
            fail(Lang.error("launch_failed", "Launch failed; static docking restored where the ship remains loaded"));
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw Lang.error("backend_incompatible", "Physics backend could not launch this ship");
        }
    }
    private Vec3d gravity(ServerLevel serverLevel) {
        try { return gravityOverride ? new Vec3d(0, -gravityCompensation, 0) : vector(PHYSICS.gravity(serverLevel)); }
        catch (LinkageError unavailable) { throw Lang.error("gravity_incompatible", "Physics gravity API is incompatible with this server"); }
    }
    /** FE per game tick of thrust at this acceleration. Hovering a 50-block hull costs a few FE/t. */
    private double tickCost(Vec3d thrustAcceleration) {
        double mass = snapshot.massKg();
        if (!(mass > 0) || !Double.isFinite(mass)) throw Lang.error("invalid_mass", "Physics engine reported invalid mass");
        if (thrustAcceleration.length() > 128)
            throw Lang.error("thrust_limit", "Required thrust exceeds the backend acceleration limit");
        return (4 + mass * thrustAcceleration.length() / 100_000) * energyFactor();
    }
    private int requireLeaseEnergy(Vec3d thrustAcceleration, long coveredNanos) {
        int cost = (int) Math.min(Integer.MAX_VALUE, Math.ceil(tickCost(thrustAcceleration) * coveredNanos / 50_000_000.0));
        if (energy.extractEnergy(cost, true) != cost) throw Lang.error("insufficient_energy", "Insufficient energy; drive disengaged");
        return cost;
    }
    private void submitPaidThrust(ServerLevel serverLevel, Vec3d thrustAcceleration, double yawRate) {
        if (forceSubmitted) {
            physicsAcknowledged = PHYSICS.physicsAcknowledged(serverLevel, snapshot.shipId(), PHYSICS_ACK_MAX_AGE_MILLIS);
            if (!physicsHeartbeat.allowRefresh(System.nanoTime(), physicsAcknowledged)) {
                throw Lang.error("physics_stopped", "Ship physics stopped acknowledging thrust; drive disengaged before consuming more energy");
            }
        }
        long now = System.nanoTime();
        int cost = requireLeaseEnergy(thrustAcceleration, leaseMeter.uncoveredNanos(now));
        energy.extractEnergy(cost, false);
        if (!PHYSICS.setWorldControl(serverLevel, snapshot.shipId(), thrustAcceleration.x(), thrustAcceleration.y(),
                thrustAcceleration.z(), yawRate, true, DRIVE_LEASE_MILLIS))
            throw Lang.error("force_rejected", "Physics backend rejected drive force");
        leaseMeter.paid(now);
        forceSubmitted = true;
        physicsHeartbeat.submitted(now);
    }
    public void navigate(Vec3d target) {
        Objects.requireNonNull(target, "target");
        if (Math.abs(target.x()) > 29_000_000 || Math.abs(target.z()) > 29_000_000 || Math.abs(target.y()) > 8192)
            throw Lang.invalid("out_of_limits", "Destination is outside flight limits");
        requireFlightReady();
        if (target.subtract(vector(snapshot.worldPosition())).length() > 50_000)
            throw Lang.invalid("waypoint_too_far", "A waypoint must be within 50,000 blocks");
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        manualAcceleration = null;
        autopilot.navigateTo(target, level.getGameTime());
        clearError();
    }
    public void hold() {
        requireFlightReady();
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        manualAcceleration = null;
        autopilot.holdAt(vector(snapshot.worldPosition()), level.getGameTime());
        clearError();
    }
    /** Brakes using available energy; it cannot create free thrust if the core runs out of fuel. */
    public void stop() {
        if (launch.pending()) {
            release();
            fail(Lang.error("launch_cancelled", "Launch cancelled; ship returned to static docking"));
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
        manualYawRate = 0;
        autopilot.manualTakeover();
        controlSource = ControlSource.LOCAL;
    }
    private void claimDrive(ServerLevel serverLevel) {
        if (activeLevel != null && (activeLevel != serverLevel || activeShipId != snapshot.shipId())) releaseOwnedDrive();
        if (!CoreRegistry.claim(serverLevel.dimension().location().toString(), snapshot.shipId(), this))
            throw Lang.error("other_core", "Another core is already controlling this ship");
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
        leaseMeter.reset();
        activeLevel = null;
        CoreRegistry.release(this);
    }
    /** Manual translation (blocks/s², camera-relative from the pilot console) plus a yaw rate in rad/s. */
    public void jog(Vec3d acceleration, double yawRate, int ticks) {
        Objects.requireNonNull(acceleration, "acceleration");
        if (!Double.isFinite(yawRate)) throw Lang.invalid("bad_input", "Rejected non-finite pilot input");
        if (ticks < 1 || ticks > 40) throw Lang.invalid("manual_ticks", "Manual input lasts 1–40 ticks");
        requireFlightReady();
        claimDrive((ServerLevel) level);
        controlSource = ControlSource.LOCAL;
        autopilot.manualTakeover();
        manualAcceleration = acceleration.clampLength(maxManualAcceleration());
        manualYawRate = Math.max(-AttitudeController.MAX_YAW_RATE, Math.min(AttitudeController.MAX_YAW_RATE, yawRate));
        manualUntil = level.getGameTime() + ticks;
    }
    public void jog(Vec3d acceleration, int ticks) { jog(acceleration, 0, ticks); }

    // ---------------------------------------------------------------- star jump

    /** Why a jump is not possible right now, or null when the ship may jump. */
    public @Nullable Lang.LocalizedException jumpBlocker() {
        if (!(level instanceof ServerLevel serverLevel) || snapshot == null) return Lang.error("assemble_first", "Assemble a Valkyrien Skies ship containing this core first");
        ResourceKey<Level> destination = StarJump.destination(serverLevel.dimension()).orElse(null);
        if (destination == null) return Lang.error("jump_no_route", "No star route from this dimension");
        if (serverLevel.getServer().getLevel(destination) == null) return Lang.error("jump_no_destination", "The destination dimension is not loaded on this server");
        if (snapshot.isStatic()) return Lang.error("docked", "Ship is statically docked; use /starbridge launch first");
        double altitude = StarJump.exitAltitude(serverLevel.dimension());
        if (snapshot.worldPosition().y() < altitude)
            return Lang.error("jump_altitude", "Climb to Y %s before jumping", (int) altitude);
        int cost = StarJump.cost(snapshot.massKg(), energyFactor());
        if (energy() < cost) return Lang.error("jump_energy", "A star jump needs %s FE", cost);
        return null;
    }

    /**
     * Jump the whole ship, with every player aboard, between the Overworld and Survey Moon. The
     * ship is parked first so it arrives static; relaunch it at the destination. Requires a star
     * chart, enough altitude and energy. VS moves the blocks; players are carried by this method.
     */
    public void jump(ServerPlayer initiator) {
        requireFlightReady();
        if (owner == null) throw Lang.error("unowned_launch", "An unowned core cannot launch");
        if (!hasStarChart(initiator)) throw Lang.error("jump_chart", "Carry a star chart to plot a jump");
        Lang.LocalizedException blocker = jumpBlocker();
        if (blocker != null) throw blocker;
        ServerLevel from = (ServerLevel) level;
        ServerLevel to = from.getServer().getLevel(StarJump.destination(from.dimension()).orElseThrow());
        long shipId = snapshot.shipId();
        Vec3 origin = new Vec3(snapshot.worldPosition().x(), snapshot.worldPosition().y(), snapshot.worldPosition().z());
        VsShipBackend.WorldBounds bounds = PHYSICS.worldBounds(from, shipId)
                .orElseThrow(() -> Lang.error("assemble_first", "Assemble a Valkyrien Skies ship containing this core first"));
        List<ServerPlayer> passengers = new ArrayList<>();
        for (ServerPlayer player : from.players())
            if (bounds.contains(player.getX(), player.getY(), player.getZ(), 3)) passengers.add(player);
        if (!passengers.contains(initiator))
            throw Lang.error("jump_board", "Board the ship before jumping");

        int cost = StarJump.cost(snapshot.massKg(), energyFactor());
        Vec3 arrival = StarJump.arrival(from, to, shipId, origin);
        release();
        if (!PHYSICS.setStatic(from, shipId, true))
            throw Lang.error("jump_failed", "The physics engine refused the jump; the ship was not moved");
        energy.extractEnergy(cost, false);
        setChanged();
        try {
            if (!PHYSICS.teleportShip(from, shipId, to, arrival.x, arrival.y, arrival.z))
                throw Lang.error("jump_failed", "The physics engine refused the jump; the ship was not moved");
        } catch (RuntimeException | LinkageError failure) {
            energy.insert(cost);
            throw Lang.error("jump_failed", "The physics engine refused the jump; the ship was not moved");
        }
        for (ServerPlayer passenger : passengers) {
            Vec3 offset = passenger.position().subtract(origin);
            passenger.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false, true));
            passenger.teleportTo(to, arrival.x + offset.x, arrival.y + offset.y + 0.25, arrival.z + offset.z,
                    passenger.getYRot(), passenger.getXRot());
            passenger.fallDistance = 0;
            to.playSound(null, passenger.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1, 0.6F);
            passenger.sendSystemMessage(Lang.msg(to.dimension().equals(ContentModule.MOON) ? "jump_arrived_moon" : "jump_arrived_home"));
        }
    }

    private static boolean hasStarChart(ServerPlayer player) {
        if (player.getAbilities().instabuild) return true;
        return player.getInventory().contains(new ItemStack(ContentModule.STAR_CHART.get()));
    }

    // ---------------------------------------------------------------- status & remote control

    public String shortStatus() {
        return "FE " + energy() + "/" + capacity() + " · " + (launch.pending() ? "LAUNCHING" : autopilot.mode())
                + (lastErrorText.isEmpty() ? "" : " · " + lastErrorText);
    }
    public String lastErrorKey() { return lastError; }
    public void requireComputerAccess(int computer, boolean mutating) {
        if (!loadedForControl()) throw Lang.error("computer_unloaded", "Flight core is unloaded");
        if (owner == null || pairedComputer < 0 || pairedComputer != computer)
            throw Lang.error("computer_unpaired", "Computer is not paired; the ship owner must pair its ID in game");
        if (mutating) requireNoHumanPilot();
    }
    private void requireAgentAccess() {
        if (!agentAllowed()) throw Lang.error("agent_disabled", "Agent access is not enabled for this loaded core");
        requireNoHumanPilot();
    }
    private void requireNoHumanPilot() {
        if (dev.starbridge.network.PilotModule.isBeingPiloted(id))
            throw Lang.error("human_pilot", "A player is piloting this ship; manual control has priority");
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
    private void fail(Throwable failure) { setError(Lang.keyOf(failure), failure.getMessage() == null ? Lang.keyOf(failure) : failure.getMessage()); }
    private void setError(String key, String text) { lastError = key; lastErrorText = text; }
    private void clearError() { lastError = ""; lastErrorText = ""; }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("id", id.toString());
        status.put("owner", owner == null ? "" : owner.toString());
        status.put("dimension", level == null ? "" : level.dimension().location().toString());
        status.put("energy", energy());
        status.put("capacity", capacity());
        status.put("mode", launch.pending() ? "LAUNCHING" : manualAcceleration == null ? autopilot.mode().name() : "MANUAL");
        status.put("launchPending", launch.pending());
        status.put("physicsAcknowledged", physicsAcknowledged);
        String backend;
        try { backend = PHYSICS.probe().detail(); }
        catch (RuntimeException | LinkageError unavailable) { backend = "Physics backend unavailable"; }
        status.put("backend", backend);
        status.put("agentAllowed", agentAllowed);
        status.put("pairedComputer", pairedComputer);
        status.put("lastError", lastErrorText);
        status.put("lastErrorKey", lastError);
        status.put("gravityCompensation", gravityCompensation);
        status.put("gravityOverride", gravityOverride);
        status.put("physicsError", PHYSICS.lastPhysicsError().orElse(""));
        for (ShipUpgrade type : ShipUpgrade.values()) status.put("upgrade_" + type.id(), upgradeLevel(type));
        status.put("lifeSupportPlayers", lifeSupported);
        if (snapshot != null) {
            status.put("shipId", snapshot.shipId());
            status.put("static", snapshot.isStatic());
            status.put("massKg", snapshot.massKg());
            status.put("x", snapshot.worldPosition().x()); status.put("y", snapshot.worldPosition().y()); status.put("z", snapshot.worldPosition().z());
            status.put("vx", snapshot.velocity().x()); status.put("vy", snapshot.velocity().y()); status.put("vz", snapshot.velocity().z());
            AttitudeController.Quaternion rotation = rotation();
            status.put("heading", AttitudeController.headingDegrees(rotation));
            status.put("tilt", AttitudeController.tiltDegrees(rotation));
            status.put("jumpCost", StarJump.cost(snapshot.massKg(), energyFactor()));
            Lang.LocalizedException blocker = jumpBlocker();
            status.put("jumpReady", blocker == null);
            status.put("jumpBlocker", blocker == null ? "" : blocker.key());
        }
        return status;
    }

    private AttitudeController.Quaternion rotation() {
        VsShipBackend.Rotation r = snapshot.rotation();
        try { return new AttitudeController.Quaternion(r.x(), r.y(), r.z(), r.w()); }
        catch (IllegalArgumentException degenerate) { return AttitudeController.Quaternion.IDENTITY; }
    }

    @Override public void load(CompoundTag tag) {
        release();
        CoreRegistry.unregister(this);
        snapshot = null;
        super.load(tag);
        if (tag.hasUUID("CoreId")) id = tag.getUUID("CoreId");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        pairedComputer = owner != null && tag.contains("Computer") ? Math.max(-1, tag.getInt("Computer")) : -1;
        agentAllowed = owner != null && tag.getBoolean("AgentAllowed");
        gravityCompensation = tag.contains("GravityCompensation") ? tag.getDouble("GravityCompensation") : 10;
        gravityOverride = tag.getBoolean("GravityOverride");
        if (!Double.isFinite(gravityCompensation) || gravityCompensation < 0 || gravityCompensation > 30) gravityCompensation = 10;
        upgrades.clear();
        CompoundTag modules = tag.getCompound("Upgrades");
        for (ShipUpgrade type : ShipUpgrade.values()) {
            int value = Math.max(0, Math.min(type.maxLevel(), modules.getInt(type.id())));
            if (value > 0) upgrades.put(type, value);
        }
        applyUpgrades();
        energy.restore(tag.getInt("Energy"));
        // Reloading a world never silently starts a drive.
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID("CoreId", id);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putInt("Computer", pairedComputer);
        tag.putBoolean("AgentAllowed", agentAllowed);
        tag.putDouble("GravityCompensation", gravityCompensation);
        tag.putBoolean("GravityOverride", gravityOverride);
        tag.putInt("Energy", energy());
        CompoundTag modules = new CompoundTag();
        upgrades.forEach((type, value) -> modules.putInt(type.id(), value));
        tag.put("Upgrades", modules);
    }
    @Override public void setRemoved() { release(); CoreRegistry.unregister(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { release(); CoreRegistry.unregister(this); super.onChunkUnloaded(); }
    @Override public void invalidateCaps() { super.invalidateCaps(); energyCapability.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); energyCapability = LazyOptional.of(() -> energy); }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return capability == ForgeCapabilities.ENERGY ? energyCapability.cast() : super.getCapability(capability, side);
    }
    private final class CoreEnergy extends EnergyStorage {
        private CoreEnergy() { super(ShipUpgrade.BASE_CAPACITY, 200_000, Integer.MAX_VALUE); }
        private void restore(int value) { energy = Math.max(0, Math.min(capacity, value)); }
        private void resize(int newCapacity) { capacity = newCapacity; energy = Math.min(energy, capacity); }
        /** Internal charging (fuel, cells), not limited by the per-tick external transfer rate. */
        private int insert(int amount) {
            if (amount <= 0) return 0;
            int added = (int) Math.min(amount, (long) capacity - energy);
            energy += added;
            if (added > 0) setChanged();
            return added;
        }
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
