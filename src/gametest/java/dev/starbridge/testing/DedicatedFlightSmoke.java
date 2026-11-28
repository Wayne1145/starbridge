package dev.starbridge.testing;

import com.google.gson.GsonBuilder;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.benchmark.BenchmarkModule;
import dev.starbridge.benchmark.CompletedBuild;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in disposable ordinary-server flight check. It is entirely inactive unless
 * -Dstarbridge.smokeTest=true is supplied. Never launch it against a real save.
 * Unlike GameTestServer it uses Minecraft's normal server tick pacing.
 */
public final class DedicatedFlightSmoke {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("starbridge_dedicated_smoke",
            Comparator.comparingLong((ChunkPos chunk) -> chunk.toLong()));
    private static final BlockPos ORIGIN = new BlockPos(128, 220, 128);
    private static final int TIMEOUT_TICKS = 1200;
    private static final long OBSERVER_WAIT_NANOS = 600_000_000_000L;
    private static final String OBSERVER_NAME = "StarbridgeTester";
    private final VsShipBackend physics = new VsShipBackend();
    private final Set<ChunkPos> storageTickets = new HashSet<>();
    private final UUID runId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final String startedAt = Instant.now().toString();
    private final boolean requireObserver = Boolean.getBoolean("starbridge.smoke.requirePlayer");
    private final int requestedLength = Integer.getInteger("starbridge.smoke.length", 0);
    private final int requestedMachines = Integer.getInteger("starbridge.smoke.machines", 24);
    private final int externalFePerTick = Integer.getInteger("starbridge.smoke.fePerTick", 100_000);
    private MinecraftServer server;
    private ServerLevel level;
    private ShipCoreBlockEntity sourceCore;
    private UUID coreId;
    private long shipId = -1;
    private int ticks, stageTicks;
    private int initialEnergy;
    private Vec3d launchPosition, navigationStart, navigationTarget, arrivalPosition;
    private Vec3d observerPosition = new Vec3d(135.5, 225, 135.5);
    private boolean fiveBlockMilestone;
    private Double arrivalSpeed, arrivalDistance;
    private double arrivalDrift;
    private long totalExternalFe, assemblyMillis;
    private int actualBlocks, actualBlockEntities;
    private CompletedBuild completedBuild;
    private BenchmarkModule.QueuedBuild queuedBuild;
    private ChunkPos sourceTicketCenter = new ChunkPos(ORIGIN);
    private int sourceTicketRadius = 9;
    private boolean sourceTicketAdded;
    private long tickStartedNanos;
    private final List<Double> tickMillis = new ArrayList<>();
    private Stage stage = Stage.PREPARING;
    private boolean finished;
    private UUID observerId;
    private long observerDeadline;

    private DedicatedFlightSmoke() { }

    public static void register() {
        if (Boolean.getBoolean("starbridge.smokeTest")) MinecraftForge.EVENT_BUS.register(new DedicatedFlightSmoke());
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("starbridge.smokeTest")) return;
        server = event.getServer();
        if (!(server instanceof DedicatedServer)) {
            // No generation or shutdown is permitted in a user's integrated client.
            Starbridge.LOGGER.error("STARBRIDGE_SMOKE_REFUSED: smokeTest requires a disposable ordinary dedicated server");
            server = null;
            return;
        }
        level = requestedLength == 0 ? server.overworld() : server.getLevel(BenchmarkModule.SHIPYARD);
        observerDeadline = System.nanoTime() + OBSERVER_WAIT_NANOS;
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_START run={} backend={} fixture={} normalServerTicks=true requireObserver={}",
                runId, physics.probe(), ORIGIN, requireObserver);
        if (requireObserver) Starbridge.LOGGER.info("STARBRIDGE_SMOKE_WAITING_FOR_OBSERVER name={} timeoutSeconds=600; flight deadline starts only after this observer joins", OBSERVER_NAME);
        try {
            check(requestedLength == 0 || requestedLength == 100 || requestedLength == 200 || requestedLength == 300,
                    "smoke.length must be 0, 100, 200 or 300");
            check(requestedMachines >= 0 && requestedMachines <= 200, "smoke.machines must be between 0 and 200");
            check(externalFePerTick >= 0 && externalFePerTick <= 200_000, "smoke.fePerTick must be between 0 and 200000");
            check(level != null, "Smoke fixture dimension is unavailable");
            if (!physics.probe().available()) throw new IllegalStateException(physics.probe().detail());
            if (requestedLength == 0) addSourceTicket();
            else if (!requireObserver) queueLargeFixture();
        } catch (RuntimeException | LinkageError exception) { finish(false, exception.toString()); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void tickStarted(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && server != null && !finished
                && (!requireObserver || observerId != null)) tickStartedNanos = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || finished) return;
        try {
            if (!observerReady()) return;
            ticks++;
            stageTicks++;
            if (ticks > TIMEOUT_TICKS) throw new IllegalStateException("Timed out after 1200 normal server ticks in " + stage);
            if (ticks % 100 == 0) logProgress();
            if (stage == Stage.PREPARING) {
                if (!terrainReady()) return;
                buildAndAssemble();
                transition(Stage.WAITING_FOR_CORE);
                return;
            }
            if (stage == Stage.GENERATING) {
                completedBuild = BenchmarkModule.getLastBuild(owner).orElse(null);
                if (completedBuild == null) {
                    check(BenchmarkModule.progress(owner).isPresent(), "Benchmark generation ended without a completed fixture");
                    return;
                }
                actualBlocks = completedBuild.blockCount();
                actualBlockEntities = completedBuild.blockEntityCount();
                check(level.getBlockEntity(completedBuild.core()) instanceof ShipCoreBlockEntity, "Generated fixture core is missing");
                prepareCore((ShipCoreBlockEntity) level.getBlockEntity(completedBuild.core()));
                assembleFixture(new HashSet<>(completedBuild.positions()));
                BenchmarkModule.forgetLastBuild(owner);
                transition(Stage.WAITING_FOR_CORE);
                return;
            }
            ticketStorage();
            var snapshot = physics.inspectById(level, shipId).orElse(null);
            ShipCoreBlockEntity core = coreId == null ? null : CoreRegistry.find(coreId).orElse(null);
            if (snapshot == null || core == null || !core.loadedForControl()) return;
            check(core != sourceCore, "Assembly must create the moved core instance");
            check(owner.equals(core.owner()), "Assembly must preserve ownership");
            check(core.id().equals(coreId), "Assembly must preserve core identity");
            if (requestedLength > 0) {
                var power = core.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new IllegalStateException("Fixture core has no Forge Energy input"));
                totalExternalFe += power.receiveEnergy(externalFePerTick, false);
            }

            if (stage == Stage.WAITING_FOR_CORE) {
                check(snapshot.isStatic(), "A newly assembled player ship must remain parked until launch");
                launchPosition = position(snapshot);
                initialEnergy = core.energy();
                core.launch();
                transition(Stage.LAUNCHING);
                return;
            }

            if (stage == Stage.LAUNCHING) {
                check(String.valueOf(core.status().get("lastError")).isEmpty(), "Launch error: " + core.status().get("lastError"));
                check(!Boolean.FALSE.equals(core.status().get("launchPending")) || !"IDLE".equals(core.status().get("mode")),
                        "Launch returned to IDLE without an active handshake; native launch did not complete");
                if (snapshot.isStatic() || !"HOLD".equals(core.status().get("mode"))) return;
                transition(Stage.HOLDING);
                return;
            }
            check(!snapshot.isStatic(), "Launched fixture unexpectedly became static");
            check(String.valueOf(core.status().get("lastError")).isEmpty(), "Drive error: " + core.status().get("lastError"));
            check(core.energy() > 0, "Fixture ran out of energy");
            check(Math.abs(snapshot.worldPosition().y() - launchPosition.y()) < 8,
                    "Powered flight exceeded the eight-block altitude tolerance");
            if (stage == Stage.HOLDING) {
                if (stageTicks < 20) return;
                check(position(snapshot).subtract(launchPosition).length() < 2, "Hold drift exceeded two blocks");
                navigationStart = position(snapshot);
                navigationTarget = navigationStart.add(new Vec3d(8, 0, 0));
                core.navigate(navigationTarget);
                transition(Stage.NAVIGATING);
                return;
            }
            if (stage == Stage.NAVIGATING || stage == Stage.SETTLING) {
                double distance = position(snapshot).subtract(navigationTarget).length();
                double speed = Math.hypot(Math.hypot(snapshot.velocity().x(), snapshot.velocity().y()), snapshot.velocity().z());
                if (!fiveBlockMilestone && snapshot.worldPosition().x() - navigationStart.x() >= 5) {
                    fiveBlockMilestone = true;
                    Starbridge.LOGGER.info("STARBRIDGE_SMOKE_TRAVEL_PASS run={} distanceX={}", runId,
                            snapshot.worldPosition().x() - navigationStart.x());
                }
                boolean arrived = fiveBlockMilestone && distance <= 1.25 && speed <= 0.3
                        && "HOLD".equals(core.status().get("mode"));
                if (stage == Stage.NAVIGATING && arrived) {
                    arrivalPosition = position(snapshot);
                    arrivalDrift = 0;
                    arrivalSpeed = speed;
                    arrivalDistance = distance;
                    transition(Stage.SETTLING);
                } else if (stage == Stage.SETTLING) {
                    if (!arrived) { transition(Stage.NAVIGATING); return; }
                    arrivalDrift = Math.max(arrivalDrift, position(snapshot).subtract(arrivalPosition).length());
                    arrivalSpeed = speed;
                    arrivalDistance = distance;
                    if (stageTicks >= 20) {
                        check((long) initialEnergy + totalExternalFe - core.energy() > 0, "Real movement must consume energy");
                        finish(true, "Native ship naturally reached its eight-block waypoint: error=" + distance
                                + ", speed=" + speed + ", stable HOLD for 20 ticks, drift=" + arrivalDrift + ".");
                    }
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            finish(false, exception.getMessage() == null ? exception.toString() : exception.getMessage());
        } finally {
            recordTick();
        }
    }

    private boolean observerReady() {
        var players = server.getPlayerList().getPlayers();
        if (!requireObserver) {
            check(players.isEmpty(), "No-player smoke mode forbids connected players.");
            return true;
        }
        check(players.size() <= 1, "Observer smoke mode permits exactly one test player; another player connected.");
        if (players.isEmpty()) {
            check(observerId == null, "Required observer disconnected during the smoke test.");
            check(System.nanoTime() - observerDeadline < 0, "Timed out after ten minutes waiting for StarbridgeTester.");
            return false;
        }
        ServerPlayer player = players.get(0);
        check(OBSERVER_NAME.equals(player.getGameProfile().getName()), "Unexpected player connected to isolated smoke server.");
        if (observerId == null) {
            observerId = player.getUUID();
            if (requestedLength > 0 && queuedBuild == null) queueLargeFixture();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.teleportTo(level, observerPosition.x(), observerPosition.y(), observerPosition.z(), 135, 25);
            player.onUpdateAbilities();
            Starbridge.LOGGER.info("STARBRIDGE_SMOKE_OBSERVER_READY run={} name={} id={} position={} creativeFlying=true",
                    runId, OBSERVER_NAME, observerId, observerPosition);
        } else {
            check(observerId.equals(player.getUUID()), "Observer identity changed during the smoke test.");
            check(player.serverLevel() == level, "Observer left the fixture dimension.");
            double observerRange = Math.max(64, requestedLength);
            check(player.position().distanceToSqr(observerPosition.x(), observerPosition.y(), observerPosition.z()) <= observerRange * observerRange,
                    "Observer moved too far from the smoke fixture.");
        }
        return true;
    }

    private void queueLargeFixture() {
        queuedBuild = BenchmarkModule.queueFixture(server, owner, requestedLength, requestedMachines);
        int widthHint = Math.min(64, requestedLength / 6 + 16);
        int heightHint = requestedLength / 20 + 18;
        // A 300-block hull places its bridge/core roughly 132 blocks from the
        // geometric centre. Observe near the actual core, as an onboard pilot
        // would, rather than relying on distant loaded chunks being ticking.
        observerPosition = new Vec3d(queuedBuild.core().getX() + 0.5,
                queuedBuild.origin().getY() + heightHint + 6, queuedBuild.core().getZ() + 0.5);
        // Keep the terrain ticket centred on the entire fixture, independently
        // of the player's observation position near the bridge.
        sourceTicketCenter = new ChunkPos(queuedBuild.origin().offset(requestedLength / 2, 0, widthHint / 2));
        sourceTicketRadius = Math.max(9, (requestedLength + 31) / 32 + 2);
        addSourceTicket();
        transition(Stage.GENERATING);
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_GENERATING run={} owner={} length={} machines={} origin={}",
                runId, owner, requestedLength, requestedMachines, queuedBuild.origin());
    }

    private void addSourceTicket() {
        level.getChunkSource().addRegionTicket(TICKET, sourceTicketCenter, sourceTicketRadius, sourceTicketCenter);
        sourceTicketAdded = true;
    }

    private boolean terrainReady() {
        ChunkPos center = new ChunkPos(ORIGIN);
        for (int x = center.x - 8; x <= center.x + 8; x++) {
            for (int z = center.z - 8; z <= center.z + 8; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        return true;
    }

    private void buildAndAssemble() {
        Set<BlockPos> positions = new HashSet<>();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) positions.add(ORIGIN.offset(x, 0, z));
        BlockPos corePosition = ORIGIN.offset(3, 1, 3);
        positions.add(corePosition);
        for (BlockPos position : positions) {
            check(level.getWorldBorder().isWithinBounds(position), "Fixture is outside world border");
            check(level.getBlockState(position).isAir(), "Fixture refuses to overwrite an existing block at " + position);
        }
        for (BlockPos position : positions) {
            var state = position.equals(corePosition) ? Starbridge.SHIP_CORE.get().defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
            check(level.setBlockAndUpdate(position, state), "Fixture block placement failed at " + position);
        }
        check(level.getBlockEntity(corePosition) instanceof ShipCoreBlockEntity, "Fixture flight core is missing");
        actualBlocks = positions.size();
        actualBlockEntities = 1;
        prepareCore((ShipCoreBlockEntity) level.getBlockEntity(corePosition));
        assembleFixture(positions);
    }

    private void prepareCore(ShipCoreBlockEntity fixtureCore) {
        sourceCore = fixtureCore;
        sourceCore.setOwner(owner);
        for (int i = 0; i < 10; i++) sourceCore.addFuel(new ItemStack(Items.COAL_BLOCK), false);
        check(sourceCore.energy() == 2_000_000, "Fixture must start with a full two-million-FE supply");
        coreId = sourceCore.id();
        sourceCore.register();
    }

    private void assembleFixture(Set<BlockPos> positions) {
        long started = System.nanoTime();
        shipId = physics.assemble(level, positions, 1, true);
        assemblyMillis = (System.nanoTime() - started) / 1_000_000L;
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_ASSEMBLED run={} ship={} core={} blocks={} millis={} parked=true",
                runId, shipId, coreId, positions.size(), assemblyMillis);
    }

    private void ticketStorage() {
        var bounds = physics.storageBoundsById(level, shipId).orElse(null);
        if (bounds == null) return;
        int maximumExtent = requestedLength == 0 ? 16 : requestedLength + 2;
        check((long) bounds.maxX() - bounds.minX() <= maximumExtent && (long) bounds.maxZ() - bounds.minZ() <= maximumExtent,
                "Fixture claimed an unexpectedly large storage region");
        for (int x = bounds.minX() >> 4; x <= (bounds.maxX() >> 4); x++) {
            for (int z = bounds.minZ() >> 4; z <= (bounds.maxZ() >> 4); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (storageTickets.add(chunk)) level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
            }
        }
    }

    private void transition(Stage next) {
        stage = next; stageTicks = 0;
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_STAGE run={} stage={} tick={}", runId, stage, ticks);
    }

    private void logProgress() {
        Object status = coreId == null ? "no fixture" : CoreRegistry.find(coreId).map(ShipCoreBlockEntity::status).orElse(Map.of());
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_DIAG run={} stage={} ticks={} core={} externalFe={} observerPosition={} native={} pipeline={}",
                runId, stage, ticks, status, totalExternalFe, observerPosition, VsShipBackend.diagnostics(),
                shipId < 0 ? Map.of() : physics.runtimeDiagnostics(server, level, shipId));
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        recordTick();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId.toString());
        result.put("passed", passed);
        result.put("detail", detail);
        result.put("stage", stage.name());
        result.put("serverTicks", ticks);
        result.put("startedAt", startedAt);
        result.put("completedAt", Instant.now().toString());
        result.put("shipId", shipId);
        result.put("coreId", coreId == null ? null : coreId.toString());
        result.put("requirePlayer", requireObserver);
        result.put("observerPresent", observerId != null && server != null
                && server.getPlayerList().getPlayer(observerId) != null);
        result.put("observerId", observerId == null ? null : observerId.toString());
        result.put("observerName", observerId == null ? null : OBSERVER_NAME);
        result.put("observerPlacement", requestedLength == 0 ? "near-small-fixture" : "above-flight-core");
        result.put("observerPosition", observerPosition);
        result.put("sourceCorePosition", queuedBuild == null ? null : Map.of("x", queuedBuild.core().getX(),
                "y", queuedBuild.core().getY(), "z", queuedBuild.core().getZ()));
        result.put("observerWaitSecondsLimit", requireObserver ? 600 : 0);
        result.put("noPlayerMode", !requireObserver);
        result.put("requestedLength", requestedLength);
        result.put("requestedMachines", requestedLength == 0 ? 0 : requestedMachines);
        result.put("actualBlocks", actualBlocks);
        result.put("actualBlockEntities", actualBlockEntities);
        result.put("assemblyMillis", assemblyMillis);
        result.put("generationMillis", completedBuild == null ? null : completedBuild.elapsedMillis());
        result.put("totalExternalFe", totalExternalFe);
        result.put("externalPowerPerTickLimit", requestedLength == 0 ? 0 : externalFePerTick);
        result.put("runtimeEnvironment", Map.of(
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "javaVersion", System.getProperty("java.version", "unknown"),
                "javaVmName", System.getProperty("java.vm.name", "unknown"),
                "maxHeapBytes", Runtime.getRuntime().maxMemory()));
        result.put("navigationTarget", navigationTarget);
        result.put("fiveBlockMilestoneObserved", fiveBlockMilestone);
        result.put("arrivalDistance", arrivalDistance);
        result.put("arrivalSpeed", arrivalSpeed);
        result.put("arrivalDrift", arrivalDrift);
        result.put("arrivalStableTicks", stage == Stage.SETTLING ? stageTicks : 0);
        result.put("tickTimings", timingSummary());
        result.put("physics", VsShipBackend.diagnostics());
        if (level != null && shipId >= 0) {
            try { result.put("ship", physics.inspectById(level, shipId).orElse(null)); }
            catch (RuntimeException unavailable) { result.put("shipReadError", unavailable.toString()); }
            try { result.put("pipeline", physics.runtimeDiagnostics(server, level, shipId)); }
            catch (RuntimeException unavailable) { result.put("pipelineReadError", unavailable.toString()); }
        }
        if (coreId != null) CoreRegistry.find(coreId).ifPresent(core -> {
            try { result.put("core", core.status()); }
            catch (RuntimeException | LinkageError exception) { result.put("coreReadError", exception.toString()); }
            result.put("energyConsumed", Math.max(0L, (long) initialEnergy + totalExternalFe - core.energy()));
            try { core.release(); }
            catch (RuntimeException | LinkageError exception) { result.put("releaseError", exception.toString()); }
        });
        try {
            if (level != null && shipId >= 0) result.put("parkedDuringCleanup", physics.parkInGameState(level, shipId));
        } catch (RuntimeException | LinkageError exception) { result.put("cleanupError", exception.toString()); }
        try { releaseTickets(); }
        catch (RuntimeException | LinkageError exception) { result.put("ticketCleanupError", exception.toString()); }
        Path resultPath = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("starbridge-smoke-result.json");
        try {
            Path temporary = resultPath.resolveSibling("starbridge-smoke-result-" + runId + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            Files.move(temporary, resultPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) { Starbridge.LOGGER.error("Smoke result could not be written to {}", resultPath, exception); }
        Starbridge.LOGGER.info("STARBRIDGE_SMOKE_{} run={} detail={} result={}", passed ? "PASS" : "FAIL", runId, detail, resultPath);
        if (server != null) server.halt(false);
    }

    private void releaseTickets() {
        if (level == null) return;
        if (sourceTicketAdded) level.getChunkSource().removeRegionTicket(TICKET, sourceTicketCenter, sourceTicketRadius, sourceTicketCenter);
        for (ChunkPos chunk : storageTickets) level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
        storageTickets.clear();
        physics.close();
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        if (server == null || event.getServer() != server) return;
        if (!finished) finish(false, "Smoke server was stopped before the flight check completed");
    }

    private static Vec3d position(VsShipBackend.Snapshot snapshot) {
        var position = snapshot.worldPosition();
        return new Vec3d(position.x(), position.y(), position.z());
    }
    private void recordTick() {
        if (tickStartedNanos == 0) return;
        tickMillis.add((System.nanoTime() - tickStartedNanos) / 1_000_000.0);
        tickStartedNanos = 0;
    }
    private Map<String, Object> timingSummary() {
        if (tickMillis.isEmpty()) return Map.of("samples", 0);
        var sorted = tickMillis.stream().sorted().toList();
        double mean = tickMillis.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
        return Map.of("samples", sorted.size(), "meanMillis", mean, "p95Millis", p95,
                "maxMillis", sorted.get(sorted.size() - 1),
                "scope", "Forge ServerTick START-to-END wall time during smoke; includes generation/assembly/test work, excludes tick pacing sleep");
    }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private enum Stage { PREPARING, GENERATING, WAITING_FOR_CORE, LAUNCHING, HOLDING, NAVIGATING, SETTLING }
}
