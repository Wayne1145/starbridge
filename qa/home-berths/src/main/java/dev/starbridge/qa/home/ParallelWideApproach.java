package dev.starbridge.qa.home;

import com.mojang.authlib.GameProfile;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.orbit.OrbitModule;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Use the audited 300-block ship and a second full-size test copy in one server tick. */
final class ParallelWideApproach {
    static final Path MARKER = Path.of("starbridge-parallel-approach.fixture");
    private static final BlockPos OFFSET = new BlockPos(512, 0, 0);
    private static final UUID SECOND_CAPTAIN = UUID.fromString("4abf2c64-e31f-4b0a-9614-96b7174cc30f");
    private static final int FARE = 12_000;
    private final VsShipBackend physics = new VsShipBackend();
    private final List<Voyage> voyages = new ArrayList<>(2);
    private int confirmationTick;
    private int settledTicks;

    private record Voyage(UUID core, UUID rocket, ServerPlayer pilot, Vec3 berth, int energy, int diamonds) {}

    boolean started() { return !voyages.isEmpty(); }

    boolean tick(DedicatedServer server, ServerPlayer originalPilot, ShipCoreBlockEntity original,
                 ServerLevel moon, Map<String, Object> proof) {
        if (voyages.isEmpty()) {
            prepare(server, originalPilot, original);
            confirmationTick = server.getTickCount();
            var assemblyNs = new ArrayList<Long>(2);
            for (var voyage : voyages) {
                var console = CoreRegistry.find(voyage.core()).orElseThrow();
                long started = System.nanoTime();
                CabinApproach.begin(voyage.pilot(), console, OrbitModule.cabinBounds(console).orElseThrow(),
                        moon, voyage.berth(), "moon", FARE);
                assemblyNs.add(System.nanoTime() - started);
            }
            proof.put("simulatedPilots", 2);
            proof.put("clientProcesses", 0);
            proof.put("confirmationTick", confirmationTick);
            proof.put("assemblyNsInConfirmationTick", assemblyNs);
            return false;
        }
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
        proof.put("arrivals", arrived);
        proof.put("settledAtTick", server.getTickCount());
        ApproachTiming.stop();
        return true;
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
