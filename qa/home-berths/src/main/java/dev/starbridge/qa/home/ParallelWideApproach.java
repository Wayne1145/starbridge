package dev.starbridge.qa.home;

import com.mojang.authlib.GameProfile;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.orbit.OrbitalFlight;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.qa.home.landing.ApproachTiming;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.CabinApproach;
import dev.starbridge.transit.HullTransit;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Approach two separately surveyed lunar berths with the audited ship and a second full-size test copy. */
final class ParallelWideApproach {
    static final Path MARKER = Path.of("starbridge-parallel-approach.fixture");
    static final Path SURVEY_MARKER = Path.of("starbridge-parallel-survey.fixture");
    private static final BlockPos OFFSET = new BlockPos(512, 0, 0);
    private static final UUID SECOND_CAPTAIN = UUID.fromString("4abf2c64-e31f-4b0a-9614-96b7174cc30f");
    private static final int FARE = 12_000;
    private final VsShipBackend physics = new VsShipBackend();
    private final List<Voyage> voyages = new ArrayList<>(2);
    private final boolean useSurvey = Files.isRegularFile(SURVEY_MARKER);
    private final Map<UUID, Object> submitted = new LinkedHashMap<>();
    private final Map<UUID, Integer> retainedSurface = new LinkedHashMap<>();
    private int nextSurveyTick;
    private int confirmationTick;
    private int settledTicks;
    private int concurrentWarmTicks;
    private Map<?, ?> storageWarmers;

    private record Voyage(UUID core, UUID rocket, ServerPlayer pilot, Vec3 berth, int energy, int diamonds) {}

    boolean started() { return !voyages.isEmpty(); }

    boolean tick(DedicatedServer server, ServerPlayer originalPilot, ShipCoreBlockEntity original,
                 ServerLevel moon, Map<String, Object> proof) throws Exception {
        if (voyages.isEmpty()) {
            prepare(server, originalPilot, original);
            proof.put("simulatedPilots", 2);
            proof.put("clientProcesses", 0);
            if (useSurvey) {
                for (var voyage : voyages) startSurvey(server, voyage);
                nextSurveyTick = server.getTickCount() + 90;
                return false;
            }
            confirmationTick = server.getTickCount();
            var assemblyNs = new ArrayList<Long>(2);
            for (var voyage : voyages) {
                var console = CoreRegistry.find(voyage.core()).orElseThrow();
                long started = System.nanoTime();
                CabinApproach.begin(voyage.pilot(), console, OrbitModule.cabinBounds(console).orElseThrow(),
                        moon, voyage.berth(), "moon", FARE);
                assemblyNs.add(System.nanoTime() - started);
            }
            proof.put("confirmationTick", confirmationTick);
            proof.put("assemblyNsInConfirmationTick", assemblyNs);
            return false;
        }
        if (useSurvey && !surveyedApproaches(server, proof)) return false;
        List<Map<String, Object>> arrived = new ArrayList<>(2);
        for (var voyage : voyages) {
            var core = CoreRegistry.find(voyage.core()).orElse(null);
            if (core == null || core.getLevel() != moon || voyage.pilot().serverLevel() != moon
                    || HullTransit.inProgress(server, voyage.core())) return false;
            require(voyage.rocket().equals(core.originalRocket()) && core.owner().equals(voyage.pilot().getUUID())
                    && HullTransit.isAboard(voyage.pilot(), core), "Original identity and pilot arrived together");
            require(core.energy() == voyage.energy() - FARE && core.saveWithoutMetadata().hasUUID("PaidVoyage"),
                    "One passage debit per ship");
            var cargo = chest(moon, core);
            require(cargo.getItem(26).is(Items.DIAMOND) && cargo.getItem(26).getCount() == voyage.diamonds()
                    && cargo.getItem(0).isEmpty(), "Distinct inventories survived independent landings");
            var body = physics.inspect(moon, core.getBlockPos()).orElseThrow();
            var location = body.worldPosition();
            Map<String, Object> arrival = new LinkedHashMap<>();
            arrival.put("core", voyage.core());
            arrival.put("rocket", voyage.rocket());
            arrival.put("pilot", voyage.pilot().getUUID());
            arrival.put("ship", body.shipId());
            arrival.put("corePosition", List.of(core.getBlockPos().getX(), core.getBlockPos().getY(), core.getBlockPos().getZ()));
            arrival.put("worldPosition", List.of(location.x(), location.y(), location.z()));
            arrival.put("fareReceipt", core.saveWithoutMetadata().getUUID("PaidVoyage"));
            arrival.put("preFare", voyage.energy());
            arrival.put("postFare", core.energy());
            arrival.put("diamonds", voyage.diamonds());
            arrived.add(arrival);
        }
        if (++settledTicks < 10) return false;
        require(!arrived.get(0).get("ship").equals(arrived.get(1).get("ship")), "Two distinct physical ships");
        require(!arrived.get(0).get("fareReceipt").equals(arrived.get(1).get("fareReceipt")), "Two distinct fare receipts");
        if (useSurvey) {
            require(retainedSurface.size() == 2, "Both surveys kept their surface chunks through physical startup");
            proof.put("retainedSurfaceChunks", retainedSurface);
            if (storageWarmers != null) proof.put("simultaneousStorageWarmTicks", concurrentWarmTicks);
        }
        proof.put("arrivals", arrived);
        proof.put("settledAtTick", server.getTickCount());
        ApproachTiming.stop();
        return true;
    }

    private void startSurvey(DedicatedServer server, Voyage voyage) throws Exception {
        var core = CoreRegistry.find(voyage.core()).orElseThrow();
        if (!OrbitModule.active(core)) OrbitModule.begin(voyage.pilot(), core, "home", "moon", false);
        var ledgerClass = Class.forName("dev.starbridge.orbit.OrbitLedger");
        var get = ledgerClass.getDeclaredMethod("get", net.minecraft.server.MinecraftServer.class);
        get.setAccessible(true);
        var legs = (Map<?, ?>) field(get.invoke(null, server), "legs");
        var leg = legs.get(voyage.core());
        var pose = leg.getClass().getDeclaredField("pose");
        pose.setAccessible(true);
        pose.set(leg, new OrbitalFlight.Pose(OrbitalFlight.body("moon").add(new Vec3d(0, 0, 100)), Vec3d.ZERO, 0, 0));
        OrbitModule.open(voyage.pilot(), core);
        send(voyage, OrbitWire.Action.SURVEY);
    }

    private boolean surveyedApproaches(DedicatedServer server, Map<String, Object> proof) throws Exception {
        if (confirmationTick == 0) {
            var jobs = Class.forName("dev.starbridge.orbit.LandingControl").getDeclaredField("jobs");
            jobs.setAccessible(true);
            var surveys = (Map<?, ?>) jobs.get(null);
            boolean bothClear = true;
            for (var voyage : voyages) {
                Object survey = surveys.get(voyage.core());
                if (survey == null || !Boolean.TRUE.equals(field(survey, "lastClear"))) {
                    bothClear = false;
                } else {
                    submitted.put(voyage.core(), survey);
                }
            }
            if (!bothClear) {
                // The open landing screen keeps its survey alive while the shared queue advances.
                if (server.getTickCount() >= nextSurveyTick) {
                    for (var voyage : voyages) send(voyage, OrbitWire.Action.SURVEY);
                    nextSurveyTick = server.getTickCount() + 90;
                }
                return false;
            }
            confirmationTick = server.getTickCount();
            for (var voyage : voyages) {
                send(voyage, OrbitWire.Action.LAND);
                require(Boolean.TRUE.equals(field(submitted.get(voyage.core()), "confirm")), "Both confirmations reached the server");
            }
            proof.put("confirmationTick", confirmationTick);
            proof.put("serverActions", List.of("SURVEY", "CLEAR", "LAND"));
            return false;
        }
        observeConcurrentWarmers(server);
        for (var voyage : voyages) {
            var core = CoreRegistry.find(voyage.core()).orElse(null);
            if (core == null || OrbitModule.active(core) || retainedSurface.containsKey(voyage.core())) continue;
            var survey = submitted.get(voyage.core());
            int held = (int) field(survey, "held");
            require(held > 0 && held == ((List<?>) field(survey, "chunks")).size(), "Full confirmed surface remained ticketed");
            retainedSurface.put(voyage.core(), held);
        }
        return true;
    }

    private void observeConcurrentWarmers(DedicatedServer server) throws Exception {
        if (storageWarmers == null) {
            var get = CabinApproach.class.getDeclaredMethod("get", net.minecraft.server.MinecraftServer.class);
            get.setAccessible(true);
            var warming = field(get.invoke(null, server), "warming");
            if (!(warming instanceof Map<?, ?> jobs)) return;
            storageWarmers = jobs;
        }
        if (storageWarmers.size() != 2) return;
        for (var voyage : voyages) {
            var job = storageWarmers.get(voyage.core());
            if (job == null || (int) field(job, "claimed") == 0) return;
        }
        concurrentWarmTicks++;
    }

    private static void send(Voyage voyage, OrbitWire.Action action) throws Exception {
        var control = OrbitModule.class.getDeclaredMethod("control", ServerPlayer.class, OrbitWire.Control.class);
        control.setAccessible(true);
        control.invoke(null, voyage.pilot(), new OrbitWire.Control(voyage.core(), action, 0, 0, 0,
                (int) Math.floor(voyage.berth().x), (int) Math.floor(voyage.berth().z)));
    }

    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private void prepare(DedicatedServer server, ServerPlayer originalPilot, ShipCoreBlockEntity original) {
        var level = originalPilot.serverLevel();
        var bounds = OrbitModule.cabinBounds(original).orElseThrow();
        int occupied = copyCabin(level, bounds, original.getBlockPos());
        require(occupied == 19_522, "Complete original wide hull copied: " + occupied);
        var other = (ShipCoreBlockEntity) level.getBlockEntity(original.getBlockPos().offset(OFFSET));
        require(other != null, "Second flight console placed");
        var connection = new WideApproachCheck.NoClient();
        new EmbeddedChannel(connection);
        var copilot = new ServerPlayer(server, level, new GameProfile(SECOND_CAPTAIN, "WideSecondPilot"));
        server.getPlayerList().placeNewPlayer(connection, copilot);
        require(!server.getPlayerList().isOp(copilot.getGameProfile())
                && copilot.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Second pilot is ordinary survival");
        other.setOwner(copilot.getUUID());
        other.bindOriginalRocket(UUID.randomUUID());
        other.getCapability(ForgeCapabilities.ENERGY).resolve().orElseThrow().receiveEnergy(90_000, false);
        other.register();
        OrbitModule.bindCabin(other, new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()).offset(OFFSET),
                new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()).offset(OFFSET));
        var feet = originalPilot.position().add(OFFSET.getX(), OFFSET.getY(), OFFSET.getZ());
        copilot.teleportTo(level, feet.x, feet.y, feet.z, originalPilot.getYRot(), originalPilot.getXRot());
        var firstCargo = chest(level, original);
        var secondCargo = chest(level, other);
        require(firstCargo.getItem(0).is(Items.DIAMOND) && firstCargo.getItem(0).getCount() == 17
                && secondCargo.getItem(0).is(Items.DIAMOND) && secondCargo.getItem(0).getCount() == 17,
                "Both copies start with the original cargo");
        firstCargo.setItem(26, firstCargo.removeItemNoUpdate(0));
        secondCargo.setItem(26, secondCargo.removeItemNoUpdate(0));
        secondCargo.getItem(26).shrink(6);
        firstCargo.setChanged();
        secondCargo.setChanged();
        voyages.add(new Voyage(original.id(), original.originalRocket(), originalPilot, new Vec3(.5, 0, -.5), original.energy(), 17));
        voyages.add(new Voyage(other.id(), other.originalRocket(), copilot, new Vec3(512.5, 0, -.5), other.energy(), 11));
    }

    private static int copyCabin(ServerLevel level, StorageBounds bounds, BlockPos originalConsole) {
        int count = 0;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (var from : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            var state = level.getBlockState(from);
            if (state.isAir()) continue;
            var to = from.offset(OFFSET);
            require(level.isEmptyBlock(to), "Second cabin storage was empty at " + to);
            require(level.setBlock(to, state, flags), "Copied cabin block at " + to);
            count++;
            if (from.equals(originalConsole)) continue;
            var machine = level.getBlockEntity(from);
            if (machine == null) continue;
            var copy = level.getBlockEntity(to);
            require(copy != null, "Copied cabin machine at " + to);
            var tag = machine.saveWithoutMetadata();
            copy.load(tag);
            copy.setChanged();
        }
        return count;
    }

    private static ChestBlockEntity chest(ServerLevel level, ShipCoreBlockEntity core) {
        if (level.getBlockEntity(core.getBlockPos().offset(0, -3, -1)) instanceof ChestBlockEntity chest) return chest;
        throw new IllegalStateException("Expected the cargo chest below the flight console");
    }

    private static void require(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
