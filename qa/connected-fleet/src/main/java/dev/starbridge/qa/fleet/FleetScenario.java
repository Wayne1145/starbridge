package dev.starbridge.qa.fleet;

import com.google.gson.GsonBuilder;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;
import dev.starbridge.voyage.VoyageLog;
import net.minecraft.world.level.storage.LevelResource;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

/**
 * Disposable loopback-only test of real Forge network sessions boarding three native, restored
 * hulls.
 */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public final class FleetScenario {
    private static final int[] TARGETS = {10, 20, 30};
    private static final int[] DURATIONS = {1200, 2400, 12000};
    private static final int[] LENGTHS = flightDecks();
    private static final double MIN_WALL_TPS = 18.0;

    private static int[] flightDecks() {
        return switch (Integer.getInteger("starbridge.fleet.length", 100)) {
            case 100 -> new int[]{100, 200, 300};
            case 200 -> new int[]{200, 100, 300};
            case 300 -> new int[]{300, 100, 200};
            default -> throw new IllegalArgumentException("Expected a 100, 200 or 300-block flight deck");
        };
    }
    private static final Path REQUEST = Path.of("fleet-peer-request.txt");
    private static final Set<UUID> passengers = new HashSet<>();
    private static final List<Deck> decks = new ArrayList<>();
    private static final List<Map<String, Object>> completed = new ArrayList<>();
    private static final Map<String, Object> flight = new LinkedHashMap<>();
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static final Set<ChunkPos> tickets = new HashSet<>();
    private static final List<Double> ms = new ArrayList<>();
    private static final List<Long> heap = new ArrayList<>();
    private static final List<String> events = new ArrayList<>();
    private static long tickStart, measuringSince, gcStart;
    private static int stage,
            queued,
            tick,
            waitTicks,
            lastProducts,
            worldReady,
            returnAfter,
            flightStarted;
    private static boolean measuring, done, atMoon, chartOpened, returnChart;
    private static final Map<String, Fare> fares = new HashMap<>();
    private record Fare(UUID leg, int before, int charge) {}
    private static final int[] previousOutput = {-1, -1, -1};
    private static final int[] totalOutput = {0, 0, 0};
    private static final VsShipBackend VS = new VsShipBackend();

    private record Deck(
            int length,
            UUID core,
            UUID rocket,
            BlockPos min,
            BlockPos bridge,
            int occupied,
            int machines,
            List<BlockPos> chests) {}

    private FleetScenario() {}

    private static boolean opted() {
        return Files.isRegularFile(Path.of("starbridge-native-fleet.fixture"));
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void handle(TickEvent.ServerTickEvent e) {
        if (done || !e.getServer().isDedicatedServer() || !opted()) return;
        if (e.phase == TickEvent.Phase.START) {
            tickStart = System.nanoTime();
            return;
        }
        var server = e.getServer();
        if (++tick < 45) return;
        try {
            if (decks.size() < 3) {
                initializeDeck(server, LENGTHS[decks.size()], decks.size());
                return;
            }
            if (worldReady == 0) {
                worldReady = tick;
                report.put(
                        "fleet",
                        decks.stream()
                                .map(
                                        d ->
                                                Map.of(
                                                        "length",
                                                        d.length,
                                                        "rocket",
                                                        d.rocket.toString(),
                                                        "core",
                                                        d.core.toString(),
                                                        "stations",
                                                        d.chests.size(),
                                                        "fixedBridge",
                                                        d.bridge.toShortString()))
                                .toList());
                report.put("pilotShipLength", LENGTHS[0]);
                report.put("fixtureForcedChunks", tickets.size());
                report.put("javaProcessors", Runtime.getRuntime().availableProcessors());
                report.put("maxHeap", Runtime.getRuntime().maxMemory());
                report.put("stages", completed);
                report.put("flight", flight);
                report.put("events", events);
                log(
                        "Three original hull snapshots restored, stock checked and working cargo"
                                + " holds provisioned");
                stage = Files.isRegularFile(Path.of("fleet-thirty-only.fixture")) ? 2 : 0;
                request(TARGETS[stage] - 1);
                write("fleet-live.json");
            }
            var pilot = server.getPlayerList().getPlayerByName("FleetCaptain");
            var players = server.getPlayerList().getPlayers();
            for (var p : players) if (passengers.add(p.getUUID())) seat(server, p);
            if (tick % 20 == 0) sampleFurnaces(server);
            int connected = 0;
            for (var p : players) {
                if (p instanceof FakePlayer
                        || p.serverLevel().getServer() != server
                        || p.connection.connection.isMemoryConnection()
                        || !(p.connection.connection.getRemoteAddress()
                                instanceof InetSocketAddress peer)
                        || !peer.getAddress().isLoopbackAddress())
                    throw new IllegalStateException(
                            "Expected TCP loopback player: " + p.getScoreboardName());
                if (p.getScoreboardName().equals("FleetCaptain")
                        || p.getScoreboardName().matches("FleetQA[0-3][0-9]")) connected++;
            }
            if (!measuring) {
                if (connected == TARGETS[stage] && pilot != null) {
                    if (++waitTicks >= 100) {
                        measuring = true;
                        ms.clear();
                        heap.clear();
                        measuringSince = System.nanoTime();
                        gcStart = gctime();
                        lastProducts = totalOutput[1] + totalOutput[2];
                        log("Reached " + connected + " connected TCP players; begin measurements");
                        write("fleet-live.json");
                    }
                } else {
                    waitTicks = 0;
                    if (tick - worldReady > 16000 && completed.isEmpty())
                        throw new IllegalStateException(
                                "Did not reach first complete connected cohort");
                }
            } else {
                if (connected != TARGETS[stage])
                    throw new IllegalStateException(
                            "Connected count dropped from " + TARGETS[stage] + " to " + connected);
                if (tick % 20 == 0)
                    heap.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                if (stage == 2 && ms.size() > 200 && pilot != null) {
                    try {
                        advanceFlight(server, pilot);
                    } catch (Exception | LinkageError flightError) {
                        flight.put("error", flightError.toString());
                        flightError.printStackTrace();
                        log(
                                "Physical flight check failed, continuing to sample the connected"
                                    + " server");
                    }
                }
                ms.add((System.nanoTime() - tickStart) / 1e6);
                if (tick % 200 == 0) write("fleet-live.json");
                boolean flightProbeEnded =
                        Files.isRegularFile(Path.of("fleet-flight-only.fixture"))
                                && stage == 2
                                && ms.size() > 500
                                && (flight.containsKey("finished") || flight.containsKey("error"));
                if (ms.size() >= DURATIONS[stage] || flightProbeEnded)
                    finishCohort(server, players);
            }
        } catch (Exception | LinkageError problem) {
            fail(server, problem);
        }
    }

    private static void initializeDeck(MinecraftServer server, int length, int index)
            throws Exception {
        var level = server.overworld();
        var saved = NbtIo.readCompressed(Path.of("inputs", length + ".nbt").toFile());
        var hull = saved.getCompound("Hull");
        var sourceMin = BlockPos.of(hull.getLong("Min"));
        var sourceMax = BlockPos.of(hull.getLong("Max"));
        if (sourceMax.getZ() - sourceMin.getZ() + 1 != length)
            throw new IllegalStateException("Wrong native manifest");
        var start = new BlockPos(240 + index * 128, 175, 64);
        var delta = start.subtract(sourceMin);
        var end = sourceMax.offset(delta);
        for (int x = start.getX() >> 4; x <= end.getX() >> 4; x++)
            for (int z = start.getZ() >> 4; z <= end.getZ() >> 4; z++) {
                var cp = new ChunkPos(x, z);
                if (tickets.add(cp)) {
                    level.setChunkForced(x, z, true);
                    level.getChunk(x, z);
                }
            }
        // Reopen the genuine atomic output of a successful LargeDock launch through its production
        // block and machine loader.
        var type = Class.forName("dev.starbridge.compat.rocketry.ShipyardHull");
        var load = type.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        var restore =
                type.getDeclaredMethod("restore", ServerLevel.class, BlockPos.class, boolean.class);
        restore.setAccessible(true);
        restore.invoke(load.invoke(null, hull), level, delta, false);
        var bridge = BlockPos.of(hull.getLong("Bridge")).offset(delta);
        var core = (ShipCoreBlockEntity) level.getBlockEntity(bridge);
        if (core == null || !core.id().equals(hull.getUUID("Core")))
            throw new IllegalStateException("Core lost from ship " + length);
        var rocket = saved.getUUID("Rocket");
        core.bindOriginalRocket(rocket);
        core.register();
        var key =
                Class.forName("dev.starbridge.compat.rocketry.OriginalShipyard")
                        .getDeclaredMethod(
                                "bind", ShipCoreBlockEntity.class, BlockPos.class, BlockPos.class);
        key.setAccessible(true);
        key.invoke(null, core, start, end);
        core.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(IllegalStateException::new)
                .receiveEnergy(100000, false);
        VoyageLog.get(server)
                .rememberHome(core.id(), new Vec3(start.getX() + 4.5, 220.5, start.getZ() + 4.5));
        var chests = new ArrayList<BlockPos>();
        for (var tag : hull.getList("Cells", 10)) {
            var row = (CompoundTag) tag;
            if (!row.getCompound("State").getString("Name").equals("minecraft:chest")) continue;
            var pos = BlockPos.of(row.getLong("P")).offset(delta);
            var chest = (ChestBlockEntity) level.getBlockEntity(pos);
            var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos.offset(2, 0, 0));
            if (chest == null
                    || chest.getItem(0).getCount() != 17
                    || furnace == null
                    || furnace.getItem(0).getCount() + furnace.getItem(2).getCount() != 8)
                throw new IllegalStateException("Original cargo corrupt");
            chests.add(pos);
            if (index > 0) {
                furnace.setItem(0, new ItemStack(Items.IRON_ORE, 64));
                furnace.setItem(1, new ItemStack(Items.COAL, 64));
                furnace.setItem(2, ItemStack.EMPTY);
            }
        }
        var cells = hull.getList("Cells", Tag.TAG_COMPOUND);
        int machines = (int) cells.stream().filter(cell -> ((CompoundTag) cell).contains("Tile")).count();
        decks.add(new Deck(length, core.id(), rocket, start, bridge, cells.size(), machines, List.copyOf(chests)));
        log(
                "Restored the verified "
                        + length
                        + "-block AR rocket "
                        + rocket
                        + " with "
                        + chests.size()
                        + " loaded cargo stations");
    }

    private static void sampleFurnaces(MinecraftServer server) {
        for (int i = 1; i < 3; i++) {
            var d = decks.get(i);
            int sum = 0, removed = 0;
            for (var pos : d.chests) {
                var f =
                        (AbstractFurnaceBlockEntity)
                                server.overworld().getBlockEntity(pos.offset(2, 0, 0));
                int n = f.getItem(2).getCount();
                sum += n;
                if (n >= 40) {
                    removed += n;
                    f.setItem(2, ItemStack.EMPTY);
                }
                if (f.getItem(0).getCount() < 12) f.setItem(0, new ItemStack(Items.IRON_ORE, 64));
                if (f.getItem(1).getCount() < 4) f.setItem(1, new ItemStack(Items.COAL, 64));
            }
            if (previousOutput[i] >= 0) totalOutput[i] += sum - previousOutput[i];
            previousOutput[i] = sum - removed;
        }
    }

    private static void seat(MinecraftServer s, ServerPlayer p) throws Exception {
        boolean captain = p.getScoreboardName().equals("FleetCaptain");
        int num = captain ? 0 : Integer.parseInt(p.getScoreboardName().substring(7));
        var deck = decks.get(captain ? 0 : 1 + num % 2);
        var seat =
                captain
                        ? deck.bridge.offset(1, 0, 1)
                        : deck.min.offset(3, 1, 2 + Math.floorMod(13 * num + 5, deck.length - 4));
        p.teleportTo(s.overworld(), seat.getX() + .5, seat.getY() + .01, seat.getZ() + .5, 0, 0);
        if (captain) {
            var core = CoreRegistry.find(deck.core).orElseThrow();
            core.setOwner(p.getUUID());
            var gear =
                    Class.forName("dev.starbridge.flightqa.OriginalEquipment")
                            .getDeclaredMethod("equip", ServerPlayer.class);
            gear.setAccessible(true);
            flight.put("pilotEquipment", gear.invoke(null, p));
            p.getInventory()
                    .setItem(0, new ItemStack(dev.starbridge.voyage.VoyageModule.SLATE.get()));
            p.getInventory().selected = 0;
        }
    }

    private static void advanceFlight(MinecraftServer s, ServerPlayer pilot) throws Exception {
        if (flight.containsKey("finished") || flight.containsKey("error")) return;
        var d = decks.get(0);
        var core = CoreRegistry.find(d.core).orElse(null);
        if (!chartOpened) {
            if (core == null) return;
            Files.writeString(Path.of("fleet-flight-start"), d.length + " " + d.bridge.getX() + " " + d.bridge.getY() + " " + d.bridge.getZ());
            chartOpened = true;
            flightStarted = tick;
            log("Rendered pilot may now right-click the actual " + d.length + "-block original ship bridge");
            return;
        }
        if (tick - flightStarted > 3500) {
            flight.put(
                    "error",
                    "Timed out at "
                            + pilot.serverLevel().dimension().location()
                            + ", core="
                            + (core == null
                                    ? "unloaded"
                                    : ((BlockEntity) core).getBlockPos().toShortString()));
            log(d.length + "-block flight timed out; preserving the crew and steady load measurements");
            return;
        }
        String route = atMoon ? "home" : "moon";
        if (!fares.containsKey(route) && HullTransit.inProgress(s, d.core)) {
            CompoundTag journal = NbtIo.read(s.getWorldPath(LevelResource.ROOT)
                    .resolve("data/starbridge_passages.dat").toFile());
            if (journal != null) for (var item : journal.getList("Legs", Tag.TAG_COMPOUND)) {
                var leg = (CompoundTag) item;
                if (leg.getUUID("Core").equals(d.core) && route.equals(leg.getString("Route"))
                        && leg.contains("FareProof", Tag.TAG_COMPOUND)) {
                    fares.put(route, new Fare(leg.getUUID("Id"), leg.getCompound("FareProof").getInt("Energy"), leg.getInt("Fare")));
                }
            }
        }
        if (core == null || !(((BlockEntity) core).getLevel() instanceof ServerLevel l)) return;
        if (tick % 100 == 0)
            flight.put(
                    "lastPilotState",
                    Map.of(
                            "pairedToCore",
                            core.id()
                                    .equals(
                                            pilot.getPersistentData()
                                                            .hasUUID("starbridge:selected_core")
                                                    ? pilot.getPersistentData()
                                                            .getUUID("starbridge:selected_core")
                                                    : null),
                            "world",
                            pilot.serverLevel().dimension().location().toString(),
                            "position",
                            pilot.position().toString(),
                            "activeOrbit",
                            dev.starbridge.orbit.OrbitModule.active(core),
                            "pendingTransfer",
                            HullTransit.inProgress(s, core.id()),
                            "energy",
                            core.energy()));
        if (!atMoon
                && l.dimension().location().toString().equals("retrofit:legacy_dim_-28")
                && !HullTransit.inProgress(s, core.id())
                && HullTransit.isAboard(pilot, core)) {
            atMoon = true;
            returnAfter = tick + 50;
            flight.put("lunar", verifyArrival(l, core, d, pilot, route));
            log(d.length + "-block original hull landed on the Galacticraft Moon with the rendered pilot");
        }
        if (atMoon && !returnChart && tick >= returnAfter) {
            Files.writeString(Path.of("fleet-return-ready"), "ready");
            returnChart = true;
            return;
        }
        if (returnChart
                && l.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && !HullTransit.inProgress(s, core.id())
                && HullTransit.isAboard(pilot, core)) {
            flight.put("home", verifyArrival(l, core, d, pilot, route));
            flight.put("finished", true);
            flight.put("ticks", tick - flightStarted);
            log(d.length + "-block lunar round trip verified with native rocket identity, crew and cargo");
        }
    }

    private static Map<String, Object> verifyArrival(
            ServerLevel l, ShipCoreBlockEntity c, Deck d, ServerPlayer p, String route) {
        var ship = VS.inspect(l, ((BlockEntity) c).getBlockPos()).orElseThrow();
        var delta = ((BlockEntity) c).getBlockPos().subtract(d.bridge);
        int diamonds = 0, ore = 0;
        for (var source : d.chests) {
            var pos = source.offset(delta);
            var chest = (ChestBlockEntity) l.getBlockEntity(pos);
            var oven = (AbstractFurnaceBlockEntity) l.getBlockEntity(pos.offset(2, 0, 0));
            if (chest == null || oven == null)
                throw new IllegalStateException("Missing native freight at " + pos);
            if (!chest.getItem(0).is(Items.DIAMOND) || chest.getItem(0).getCount() != 17
                    || (!oven.getItem(0).isEmpty() && !oven.getItem(0).is(Items.IRON_ORE))
                    || (!oven.getItem(2).isEmpty() && !oven.getItem(2).is(Items.IRON_INGOT))
                    || oven.getItem(0).getCount() + oven.getItem(2).getCount() != 8)
                throw new IllegalStateException("Freight changed at original station " + source);
            diamonds += chest.getItem(0).getCount();
            ore += oven.getItem(0).getCount() + oven.getItem(2).getCount();
        }
        if (diamonds != d.chests.size() * 17
                || ore != d.chests.size() * 8
                || !d.rocket.equals(c.originalRocket())
                || !p.isAlive())
            throw new IllegalStateException("Wrong transferred cargo or identity");
        var bounds = VS.storageBoundsById(l, ship.shipId()).orElseThrow();
        // The VS storage AABB can include a border of empty cells. Measure the occupied grid.
        var occupied = net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulatingPositions(
                java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ()).spliterator(), false)
                        .filter(pos -> !l.getBlockState(pos).isAir()).map(BlockPos::immutable).toList()).orElseThrow();
        int blockCount = 0, machineCount = 0;
        for (var pos : BlockPos.betweenClosed(occupied.minX(), occupied.minY(), occupied.minZ(),
                occupied.maxX(), occupied.maxY(), occupied.maxZ())) {
            if (!l.getBlockState(pos).isAir()) ++blockCount;
            if (l.getBlockEntity(pos) != null) ++machineCount;
        }
        if (occupied.getZSpan() != d.length || occupied.getXSpan() != 7 || occupied.getYSpan() != 6
                || blockCount != d.occupied || machineCount != d.machines)
            throw new IllegalStateException("Occupied hull changed: " + occupied + ", blocks=" + blockCount + ", machines=" + machineCount);
        flight.put(route + "Hull", Map.of("length", d.length, "width", 7, "height", 6, "blocks", blockCount,
                "blockEntities", machineCount, "cargoStations", d.chests.size(), "core", c.id().toString()));
        var localFeet = HullTransit.toShipLocal(l, ship.shipId(), p.position()).orElseThrow();
        var floor = BlockPos.containing(localFeet.x, localFeet.y - .10, localFeet.z);
        var support = l.getBlockState(floor);
        var hit = support.getCollisionShape(l, floor).clip(localFeet.add(0, .10, 0), localFeet.add(0, -.10, 0), floor);
        if (hit == null || hit.getDirection() != net.minecraft.core.Direction.UP
                || Math.abs(localFeet.y - hit.getLocation().y) > .075)
            throw new IllegalStateException("Pilot has no deck contact on " + route + " at " + localFeet);
        flight.put(route + "Footing", Map.of("floorBlock", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(support.getBlock()).toString(), "localFeet", localFeet.toString(),
                "worldFeet", p.position().toString(), "floorTop", hit.getLocation().y,
                "gap", localFeet.y - hit.getLocation().y, "pilotHealth", p.getHealth()));
        Fare fare = fares.get(route);
        var coreTag = ((BlockEntity) c).saveWithoutMetadata();
        if (fare == null || fare.charge != 12000 || c.energy() != fare.before - fare.charge
                || !coreTag.hasUUID("PaidVoyage") || !fare.leg.equals(coreTag.getUUID("PaidVoyage")))
            throw new IllegalStateException("Missing or incorrect settled fare on " + route + ": " + fare + ", energy=" + c.energy());
        flight.put(route + "Fare", Map.of("leg", fare.leg.toString(), "energyBefore", fare.before,
                "charged", fare.charge, "balance", c.energy(), "receiptMatches", true, "pending", false));
        return Map.of(
                "dimension",
                l.dimension().location().toString(),
                "shipId",
                ship.shipId(),
                "original",
                c.originalRocket().toString(),
                "diamonds",
                diamonds,
                "furnaceItems",
                ore,
                "energy",
                c.energy(),
                "crewAlive",
                p.isAlive());
    }

    private static void finishCohort(MinecraftServer s, List<ServerPlayer> players)
            throws Exception {
        long now = System.nanoTime();
        var sorted = ms.stream().sorted().toList();
        double sec = (now - measuringSince) / 1e9;
        var row = new LinkedHashMap<String, Object>();
        row.put("realTcpPlayers", players.size());
        row.put("renderedClients", 1);
        row.put("headlessForgePlayConnections", players.size() - 1);
        row.put("ticks", ms.size());
        row.put("seconds", sec);
        row.put("wallTps", ms.size() / sec);
        row.put("tickMsMean", ms.stream().mapToDouble(x -> x).average().orElseThrow());
        row.put("tickMsP50", percentile(sorted, .5));
        row.put("tickMsP95", percentile(sorted, .95));
        row.put("tickMsP99", percentile(sorted, .99));
        row.put("tickMsMaximum", sorted.get(sorted.size() - 1));
        row.put("ticksOver50ms", ms.stream().filter(v -> v > 50).count());
        row.put("heapUsedMinBytes", Collections.min(heap));
        row.put("heapUsedMaxBytes", Collections.max(heap));
        row.put("gcMillis", gctime() - gcStart);
        row.put(
                "operatingStationaryFurnaces",
                decks.get(1).chests.size() + decks.get(2).chests.size());
        row.put("ironIngotsProduced", totalOutput[1] + totalOutput[2] - lastProducts);
        row.put(
                "uniquePlayerIds",
                players.stream().map(p -> p.getUUID().toString()).distinct().count());
        if ((int) row.get("ironIngotsProduced") <= 0)
            throw new IllegalStateException("Stationary machinery failed to advance");
        completed.add(row);
        log(
                "Completed "
                        + TARGETS[stage]
                        + " clients for "
                        + ms.size()
                        + " ticks with "
                        + row.get("ironIngotsProduced")
                        + " ingots");
        if (++stage == TARGETS.length) {
            done = true;
            boolean completedVoyage = Boolean.TRUE.equals(flight.get("finished")) && !flight.containsKey("error");
            boolean withinBudget = ms.size() / sec >= MIN_WALL_TPS;
            row.put("minimumWallTps", MIN_WALL_TPS);
            row.put("performancePassed", withinBudget);
            report.put("passed", completedVoyage && withinBudget);
            if (!withinBudget) report.put("reason", "Average TPS below " + MIN_WALL_TPS);
            if (!completedVoyage) report.put("reason", "The connected fleet measurement ended without a verified round trip");
            write("fleet-result.json");
            s.halt(false);
            return;
        }
        measuring = false;
        waitTicks = 0;
        request(TARGETS[stage] - 1);
        write("fleet-live.json");
    }

    private static double percentile(List<Double> sorted, double pct) {
        return sorted.get((int) Math.ceil(sorted.size() * pct) - 1);
    }

    private static long gctime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionTime()))
                .sum();
    }

    private static void request(int peers) throws Exception {
        Files.writeString(REQUEST, Integer.toString(peers));
    }

    private static void log(String msg) {
        events.add("tick " + tick + " " + msg);
        dev.starbridge.Starbridge.LOGGER.info("FLEET {}", msg);
    }

    private static void write(String name) throws Exception {
        report.put("currentTick", tick);
        report.put("currentCohort", stage < TARGETS.length ? TARGETS[stage] : 0);
        report.put("sampleTicks", ms.size());
        report.put("hullIngots", List.of(totalOutput[1], totalOutput[2]));
        Files.writeString(
                Path.of(name), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static void fail(MinecraftServer s, Throwable cause) {
        done = true;
        cause.printStackTrace();
        report.put("passed", false);
        report.put("reason", cause.toString());
        try {
            write("fleet-result.json");
        } catch (Exception ignored) {
        }
        s.halt(false);
    }
}
