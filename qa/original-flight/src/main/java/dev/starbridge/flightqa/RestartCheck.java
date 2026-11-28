package dev.starbridge.flightqa;

import com.google.gson.JsonParser;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.transit.HullTransit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Observes a real, durable homeward debit on either side of a fresh server process. */
public final class RestartCheck {
    private static final Path CUT = Path.of("restart-cut.json"),
            ARM = Path.of("starbridge-crash-restart.fixture");
    private static final VsShipBackend VS = new VsShipBackend();
    private static int connected, stable;
    private static boolean complete;

    public static void beforeJournalCommit() throws Exception {
        if (!MissionSignals.JOINED || !Files.isRegularFile(ARM) || Files.exists(CUT)) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isDedicatedServer() || !server.isSameThread()) return;
        var pending = legs(server);
        if (pending.size() != 1) return;
        var leg = pending.getCompound(0);
        if (!"home".equals(leg.getString("Route")) || leg.getBoolean("Committed")) return;
        var itinerary =
                JsonParser.parseString(Files.readString(Path.of("flight-live.json")))
                        .getAsJsonObject();
        var proof = leg.getCompound("FareProof");
        int fare = leg.getInt("Fare"), before = proof.getInt("Energy");
        var tx = leg.getUUID("Id");
        var corePos = BlockPos.of(leg.getLong("CorePos"));
        var core = (ShipCoreBlockEntity) server.overworld().getBlockEntity(corePos);
        check(
                core != null && core.energy() == before - fare && fare == 12000,
                "Debit not present at the pre-commit boundary");
        check(
                tx.equals(core.saveWithoutMetadata().getUUID("PaidVoyage")),
                "Physical receipt differs from the open passage");
        var registers = leg.getList("Crew", Tag.TAG_COMPOUND);
        check(
                registers.size() == 2
                        && !registers.getCompound(0).getBoolean("Done")
                        && !registers.getCompound(1).getBoolean("Done"),
                "Riders already marked delivered");
        var mark = new LinkedHashMap<String, Object>();
        mark.put("process", ProcessHandle.current().pid());
        mark.put("tick", server.getTickCount());
        mark.put("transaction", tx);
        mark.put("rocket", core.originalRocket());
        mark.put("core", core.id());
        mark.put("ship", leg.getLong("Ship"));
        mark.put("x", corePos.getX());
        mark.put("y", corePos.getY());
        mark.put("z", corePos.getZ());
        mark.put("destination", leg.getString("Destination"));
        mark.put("source", leg.getString("Source"));
        mark.put("committed", leg.getBoolean("Committed"));
        mark.put(
                "riders",
                List.of(
                        registers.getCompound(0).getUUID("Player"),
                        registers.getCompound(1).getUUID("Player")));
        mark.put("delivered", 0);
        mark.put("energyBefore", before);
        mark.put("energyAfter", core.energy());
        mark.put("fare", fare);
        mark.put("chest", contents(chest(server.overworld(), corePos)));
        mark.put("furnaceMaterialCount", smelting(server.overworld(), corePos));
        mark.put("originalReward", itinerary.get("originalReward"));
        mark.put("outboundReceipt", itinerary.get("moonReceipt"));
        mark.put("outboundFE", itinerary.get("moonFE"));
        mark.put("itinerary", itinerary.get("events"));
        MachineWitness.capture(server,"cut");
        MissionSignals.write(CUT, mark);
        if (!new CountDownLatch(1).await(35, TimeUnit.SECONDS)) {
            Files.writeString(
                    Path.of("restart-cut-not-observed"),
                    "No external restart observed while this server tick waited");
            throw new IllegalStateException(
                    "The test controller did not terminate its dedicated process at the boundary");
        }
    }

    static boolean afterRestart(MinecraftServer server) throws Exception {
        if (!Boolean.getBoolean("starbridge.resumeQa")) return false;
        if (complete) return true;
        if (!MissionSignals.JOINED || !Files.isRegularFile(ARM) || !Files.isRegularFile(CUT))
            throw new IllegalStateException("Missing test restart state");
        if (server.getTickCount() % 10 != 0) return true;
        var cut = JsonParser.parseString(Files.readString(CUT)).getAsJsonObject();
        check(
                cut.get("process").getAsLong() != ProcessHandle.current().pid(),
                "Process was not restarted");
        var pilot = server.getPlayerList().getPlayerByName(MissionSignals.PILOT);
        var engineer = server.getPlayerList().getPlayerByName(MissionSignals.ENGINEER);
        if (pilot == null || engineer == null) return true;
        if (connected == 0) connected = server.getTickCount();
        var corePos =
                new BlockPos(
                        cut.get("x").getAsInt(), cut.get("y").getAsInt(), cut.get("z").getAsInt());
        var id = UUID.fromString(cut.get("core").getAsString());
        var tx = UUID.fromString(cut.get("transaction").getAsString());
        var dest = server.overworld();
        var source = server.getAllLevels().iterator();
        ServerLevel old = null;
        while (source.hasNext()) {
            var next = source.next();
            if (next.dimension().location().toString().equals(cut.get("source").getAsString()))
                old = next;
        }
        var open = legs(server);
        boolean pending = HullTransit.inProgress(server, id);
        MachineWitness.capture(server,"reopened");
        var report = new LinkedHashMap<String, Object>();
        report.put("previousProcess", cut.get("process"));
        report.put("newProcess", ProcessHandle.current().pid());
        report.put("serverAge", server.getTickCount());
        report.put("journalLegs", open.size());
        report.put("pending", pending);
        report.put("captainPosition", pilot.position().toString());
        report.put("engineerPosition", engineer.position().toString());
        report.put("captainWorld", pilot.serverLevel().dimension().location().toString());
        report.put("engineerWorld", engineer.serverLevel().dimension().location().toString());
        boolean good = false;
        if (old != null && dest.getBlockEntity(corePos) instanceof ShipCoreBlockEntity core) {
            var oldCore = old.getBlockEntity(corePos);
            var oldChest = old.getBlockEntity(corePos.offset(MissionSignals.CHEST_FROM_CORE));
            var oldFurnace = old.getBlockEntity(corePos.offset(MissionSignals.FURNACE_FROM_CORE));
            var chest = chest(dest, corePos);
            var slots = contents(chest);
            var rocket = UUID.fromString(cut.get("rocket").getAsString());
            var live = core.saveWithoutMetadata();
            boolean single = oldCore == null && oldChest == null && oldFurnace == null;
            boolean aboard =
                    pilot.isAlive()
                            && engineer.isAlive()
                            && pilot.serverLevel() == dest
                            && engineer.serverLevel() == dest
                            && HullTransit.isAboard(pilot, core)
                            && HullTransit.isAboard(engineer, core);
            var wantedRiders = new HashSet<String>();
            cut.getAsJsonArray("riders").forEach(a -> wantedRiders.add(a.getAsString()));
            boolean originalCrew =
                    wantedRiders.equals(
                            Set.of(pilot.getUUID().toString(), engineer.getUUID().toString()));
            boolean cargo =
                    MissionSignals.JSON.toJsonTree(slots).equals(cut.get("chest"))
                            && smelting(dest, corePos)
                                    == cut.get("furnaceMaterialCount").getAsInt();
            boolean receipt =
                    live.hasUUID("PaidVoyage")
                            && tx.equals(live.getUUID("PaidVoyage"))
                            && core.energy() == cut.get("energyAfter").getAsInt()
                            && cut.get("energyBefore").getAsInt() - core.energy()
                                    == cut.get("fare").getAsInt();
            boolean hull =
                    id.equals(core.id())
                            && rocket.equals(core.originalRocket())
                            && VS.belongsTo(dest, cut.get("ship").getAsLong())
                            && !VS.belongsTo(old, cut.get("ship").getAsLong());
            report.put("sameCrewAliveAboard", aboard && originalCrew);
            report.put("sameNativeShipAndCore", hull);
            report.put("sourceCargoAbsent", single);
            report.put("exactChestAndFurnace", cargo);
            report.put("preRestartCargo", cut.get("chest"));
            report.put("reopenedCargo", slots);
            report.put("legacyReward", cut.get("originalReward"));
            report.put("furnaceMaterials", smelting(dest, corePos));
            report.put("beforeFare", cut.get("energyBefore"));
            report.put("finalFE", core.energy());
            report.put(
                    "receipt",
                    live.hasUUID("PaidVoyage") ? live.getUUID("PaidVoyage").toString() : "");
            report.put("paidExactlyOnce", receipt);
            good =
                    single
                            && aboard
                            && originalCrew
                            && cargo
                            && receipt
                            && hull
                            && !pending
                            && open.isEmpty();
        } else report.put("loadedCore", false);
        stable = good ? stable + 10 : 0;
        report.put("stableTicks", stable);
        if (stable >= 100 || server.getTickCount() - connected > 1000) {
            report.put("pass", stable >= 100);
            MissionSignals.write(Path.of("recovery-result.json"), report);
            MissionSignals.write(Path.of("flight-result.json"), report);
            complete = true;
            server.halt(false);
        } else MissionSignals.write(Path.of("recovery-live.json"), report);
        return true;
    }

    private static ListTag legs(MinecraftServer server) throws Exception {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve("data/starbridge_passages.dat");
        var root = NbtIo.read(path.toFile());
        return root == null ? new ListTag() : root.getList("Legs", Tag.TAG_COMPOUND);
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos core) {
        return (ChestBlockEntity) level.getBlockEntity(core.offset(MissionSignals.CHEST_FROM_CORE));
    }

    private static List<String> contents(ChestBlockEntity chest) {
        var slots = new ArrayList<String>();
        if (chest == null) return slots;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            var item = chest.getItem(i);
            if (!item.isEmpty()) slots.add(i + " " + item.save(new CompoundTag()));
        }
        return slots;
    }

    private static int smelting(ServerLevel level, BlockPos core) {
        var be = level.getBlockEntity(core.offset(MissionSignals.FURNACE_FROM_CORE));
        return be instanceof AbstractFurnaceBlockEntity oven
                ? oven.getItem(0).getCount() + oven.getItem(2).getCount()
                : -1;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
