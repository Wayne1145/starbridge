package dev.starbridge.qa.fleet;

import com.google.gson.Gson;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.orbit.OrbitalFlight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Fixed-duration loopback endurance run. Each sample uses a monotonic clock in this server JVM. */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public final class OrbitalSoak {
    static final Gson JSON = new Gson();
    static final boolean ENABLED = Files.isRegularFile(Path.of("starbridge-orbit-soak.fixture"));
    static final boolean FULL_COHORTS = Files.isRegularFile(Path.of("full-cohort-hours.fixture"));
    static final Map<UUID, net.minecraft.network.Connection> cohortConnections = new HashMap<>();
    static long fullCohortNanos;
    static boolean cohortFilled, finishedFullCohorts;
    static final int[] lengths = {100, 200, 300};
    static final int[] items = {3, 5, 7};
    static final long HOUR = 3_600_000_000_000L;
    static final Map<UUID, Double> previousX = new HashMap<>();
    static final Set<UUID> motion = new HashSet<>();
    static final List<BlockPos> furnaces = new ArrayList<>();
    static final BlockPos[] cargo = new BlockPos[3];
    static final long[] histogram = new long[502];
    static List<ShipCoreBlockEntity> cores = new ArrayList<>();
    static UUID[] captains = new UUID[3];
    static long opened,
            begin,
            lastSample,
            tickStart,
            windowTicks,
            totalTicks,
            over50,
            maxNano,
            totalStone,
            lastStone,
            lastWall,
            lastPeriod = -1;
    static int generated,
            desired = 10,
            minOnline = 31,
            maxOnline,
            missingTicks,
            missingStreak,
            worstStreak;
    static boolean done, braking;

    static void atomic(String file, Object value) throws Exception {
        Path dst = Path.of(file);
        Path temp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.writeString(temp, JSON.toJson(value));
        Files.move(temp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void event(String text) throws Exception {
        Files.writeString(
                Path.of("soak-events.jsonl"),
                JSON.toJson(Map.of("utc", Instant.now().toString(), "event", text)) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }

    static void target(int n) throws Exception {
        desired = n;
        atomic("soak-peers.txt", n);
        event("request " + n + " ordinary TCP identities");
    }

    static void seedRoute(MinecraftServer server, ShipCoreBlockEntity core) throws Exception {
        var ledger = Class.forName("dev.starbridge.orbit.OrbitLedger");
        var get = ledger.getDeclaredMethod("get", MinecraftServer.class);
        get.setAccessible(true);
        var legs = ledger.getDeclaredField("legs");
        legs.setAccessible(true);
        var flight = ((Map<?, ?>) legs.get(get.invoke(null, server))).get(core.id());
        var pose = flight.getClass().getDeclaredField("pose");
        pose.setAccessible(true);
        // At 17 navigation units/tick the three crews can travel nearly 59 million units in 48 hours.
        int departureZ = FULL_COHORTS ? -100_000_000 : -25_000_000;
        pose.set(flight, new OrbitalFlight.Pose(new Vec3d(0, 0, departureZ), Vec3d.ZERO, 0, 0));
    }

    static void dock(ServerLevel level) throws Exception {
        for (int i = 0; i < 3; i++) {
            var core = (ShipCoreBlockEntity) level.getBlockEntity(ConnectedFleet.cores[i]);
            cores.add(Objects.requireNonNull(core));
            for (int z = 0; z < lengths[i]; z += 16)
                level.setChunkForced((640 * i) >> 4, z >> 4, true);
            for (int z = 9; z < lengths[i]; z += 20)
                furnaces.add(new BlockPos(i * 640 + 5, ConnectedFleet.BASE + 1, z));
            cargo[i] = new BlockPos(i * 640 + 5, ConnectedFleet.BASE + 1, 10);
            ((ChestBlockEntity) level.getBlockEntity(cargo[i]))
                    .setItem(0, new ItemStack(Items.DIAMOND, items[i]));
        }
        atomic(
                "soak-fixture.json",
                Map.of(
                        "pid",
                        ProcessHandle.current().pid(),
                        "ships",
                        ConnectedFleet.ships,
                        "furnaces",
                        furnaces.size(),
                        "diamonds",
                        items,
                        "openedUtc",
                        Instant.now().toString()));
        target(10);
    }

    static void machineWork(ServerLevel level) {
        for (BlockPos pos : furnaces) {
            var oven = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
            require(oven != null, "missing furnace " + pos);
            if (oven.getItem(0).getCount() < 16)
                oven.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            if (oven.getItem(1).getCount() < 4) oven.setItem(1, new ItemStack(Items.COAL, 16));
            var output = oven.getItem(2);
            if (!output.isEmpty()) {
                require(output.is(Items.STONE), "wrong output");
                totalStone += output.getCount();
                oven.setItem(2, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < 3; i++) {
            var chest = (ChestBlockEntity) level.getBlockEntity(cargo[i]);
            require(
                    chest != null
                            && chest.getItem(0).is(Items.DIAMOND)
                            && chest.getItem(0).getCount() == items[i],
                    "changed cargo on ship " + i);
            var cap = cores.get(i).getCapability(ForgeCapabilities.ENERGY);
            if (cap.isPresent()) cap.resolve().orElseThrow().receiveEnergy(300, false);
        }
    }

    static void board(MinecraftServer server, ServerPlayer p) throws Exception {
        require(
                !(p instanceof FakePlayer)
                        && !p.hasPermissions(2)
                        && !p.isCreative()
                        && !p.isSpectator()
                        && !p.connection.connection.isMemoryConnection()
                        && p.connection.connection.getRemoteAddress()
                                instanceof InetSocketAddress ip
                        && ip.getAddress().isLoopbackAddress(),
                "unexpected player connection");
        var name = p.getGameProfile().getName();
        require(name.matches("FleetQA[0-3][0-9]"), "unexpected local player");
        int n = Integer.parseInt(name.substring(7)) - 1;
        require(n >= 0 && n < 30, "identity outside fixture");
        if (FULL_COHORTS) {
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require(n < desired && p.getUUID().equals(offline), "unexpected cohort identity");
            var connection = p.connection.connection;
            var original = cohortConnections.putIfAbsent(p.getUUID(), connection);
            require(original == null || original == connection, "crew connection replaced during measurement");
        }
        int i = n % 3;
        var core = cores.get(i);
        Double prior = previousX.put(p.getUUID(), p.getX());
        if (prior != null) {
            if (Math.abs(p.getX() - prior) > .001) motion.add(p.getUUID());
            return;
        }
        int row = n / 3;
        p.teleportTo(
                server.overworld(),
                i * 640 + 1.5 + row % 4,
                ConnectedFleet.BASE + 1,
                4.5 + row * 3,
                0,
                0);
        if (captains[i] == null) {
            captains[i] = p.getUUID();
            core.setOwner(p.getUUID());
            OrbitModule.begin(p, core, "home", "moon", true);
            seedRoute(server, core);
        }
    }

    static long quantile(double raw) {
        long goal = (long) Math.ceil(windowTicks * raw), n = 0;
        for (int i = 0; i < histogram.length; i++) {
            n += histogram[i];
            if (n >= goal) return i;
        }
        return 0;
    }

    static void sample(MinecraftServer server, long now, int count) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        var routes = new ArrayList<Object>();
        for (var core : cores) routes.add(OrbitModule.status(core));
        row.put("utc", Instant.now().toString());
        row.put("pid", ProcessHandle.current().pid());
        row.put("serverTick", server.getTickCount());
        row.put("quorumWallSeconds", (now - begin) / 1e9);
        row.put("windowWallSeconds", (now - lastSample) / 1e9);
        row.put("windowTicks", windowTicks);
        row.put("tps", windowTicks * 1e9 / (now - lastSample));
        row.put("hostWallGapMs", System.currentTimeMillis() - lastWall);
        row.put("tickP95CeilMs", quantile(.95));
        row.put("tickP99CeilMs", quantile(.99));
        row.put("peakTickMs", maxNano / 1e6);
        row.put("ticksOver50Ms", over50);
        row.put("online", count);
        row.put("target", desired);
        row.put("minimumOnline", minOnline);
        row.put("maximumOnline", maxOnline);
        row.put("observedMoving", motion.size());
        if (FULL_COHORTS) {
            var players = server.getPlayerList().getPlayers();
            row.put("members", players.stream().map(p -> p.getGameProfile().getName()).sorted().toList());
            row.put("movingMembers", players.stream().filter(p -> motion.contains(p.getUUID()))
                    .map(p -> p.getGameProfile().getName()).sorted().toList());
            boolean full = minOnline == desired && maxOnline == desired && motion.size() == desired;
            require(!cohortFilled || full, "full cohort stopped moving or disconnected");
            if (full) {
                cohortFilled = true;
                fullCohortNanos += now - lastSample;
            }
        }
        row.put("totalQuorumDeficitTicks", missingTicks);
        row.put("stoneTotal", totalStone);
        row.put("stoneThisWindow", totalStone - lastStone);
        row.put("cargo", items);
        row.put("ships", routes);
        row.put(
                "usedHeapMiB",
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
        Files.writeString(
                Path.of("soak-windows.jsonl"),
                JSON.toJson(row) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        atomic("soak-live.json", row);
        require(totalStone > lastStone, "all furnaces stopped");
        require(motion.size() >= Math.min(10, desired), "fewer than ten moving connections");
        require(
                routes.size() == 3 && cores.stream().allMatch(OrbitModule::active),
                "missing live orbital route");
        lastSample = now;
        lastStone = totalStone;
        lastWall = System.currentTimeMillis();
        Arrays.fill(histogram, 0);
        windowTicks = over50 = maxNano = 0;
        minOnline = 31;
        maxOnline = 0;
        motion.clear();
        if (FULL_COHORTS && fullCohortNanos >= HOUR * (desired == 10 ? 2 : desired == 20 ? 6 : 40)) {
            if (desired == 30) finishedFullCohorts = true;
            else target(desired + 10);
            fullCohortNanos = 0;
            cohortFilled = false;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!ENABLED || done) return;
        var server = event.getServer();
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            tickStart = now;
            return;
        }
        try {
            if (opened == 0) {
                require(
                        server.isDedicatedServer()
                                && "127.0.0.1".equals(server.getLocalIp())
                                && server.getPort() == 26750,
                        "isolated loopback fixture required");
                require(
                        !Files.exists(Path.of("soak-fixture.json")),
                        "a fresh world is required for a continuous run");
                opened = now;
            }
            if (generated < 600) {
                for (int j = 0; j < 3 && generated < 600; j++, generated++) {
                    int line = generated, i = 0;
                    while (line >= lengths[i]) line -= lengths[i++];
                    ConnectedFleet.hull(server.overworld(), i, line);
                }
                if (generated == 600) dock(server.overworld());
                return;
            }
            var players = server.getPlayerList().getPlayers();
            for (var p : players) board(server, p);
            int count = players.size();
            if (FULL_COHORTS) require(count == cohortConnections.size(), "a persistent crew identity disconnected");
            if (server.getTickCount() % 40 == 0) machineWork(server.overworld());
            if (begin == 0) {
                if (count < 10 || motion.size() < 10) {
                    require(now - opened < 900_000_000_000L, "ten participants never joined");
                    return;
                }
                if (FULL_COHORTS) motion.clear();
                begin = lastSample = now;
                lastWall = System.currentTimeMillis();
                event(
                        "10 participants, observed movement and 3 orbital routes. Continuous"
                            + " measurement began");
                atomic(
                        "soak-start.json",
                        Map.of(
                                "utc",
                                Instant.now().toString(),
                                "serverTick",
                                server.getTickCount(),
                                "targetHours",
                                48));
            }
            long elapsed = now - begin;
            int stage = elapsed < HOUR * 2 ? 10 : elapsed < HOUR * 8 ? 20 : 30;
            if (!FULL_COHORTS && stage != desired) target(stage);
            if (count < desired) {
                missingTicks++;
                missingStreak++;
                worstStreak = Math.max(worstStreak, missingStreak);
            } else missingStreak = 0;
            require(missingStreak < 6_000, "target cohort missing for 6000 consecutive ticks");
            long period = elapsed / HOUR;
            boolean brakeWindow =
                    elapsed % HOUR >= 180_000_000_000L && elapsed % HOUR < 200_000_000_000L;
            if (braking != brakeWindow || lastPeriod != period) {
                for (int i = 0; i < 3; i++) {
                    var pilot = server.getPlayerList().getPlayer(captains[i]);
                    require(pilot != null, "pilot disconnected");
                    if (brakeWindow) OrbitModule.brake(pilot, cores.get(i));
                    else OrbitModule.autopilot(pilot, cores.get(i), true);
                }
                event(
                        (brakeWindow ? "brake " : "cruise ")
                                + "on all 3 ships, elapsed hour "
                                + period);
                braking = brakeWindow;
                lastPeriod = period;
            }
            long cost = now - tickStart;
            histogram[(int) Math.min(histogram.length - 1, (cost + 999_999) / 1_000_000)]++;
            maxNano = Math.max(maxNano, cost);
            if (cost > 50_000_000) over50++;
            minOnline = Math.min(minOnline, count);
            maxOnline = Math.max(maxOnline, count);
            windowTicks++;
            totalTicks++;
            if (now - lastSample >= 60_000_000_000L) sample(server, now, count);
            if (FULL_COHORTS ? finishedFullCohorts : elapsed >= 48 * HOUR) {
                require(count == 30, "30 final participants");
                atomic(
                        "soak-result.json",
                        Map.of(
                                "pass",
                                true,
                                "elapsedWallSeconds",
                                elapsed / 1e9,
                                "totalServerTicks",
                                totalTicks,
                                "quorumDeficitTicks",
                                missingTicks,
                                "worstConsecutiveDeficitTicks",
                                worstStreak,
                                "finalOnline",
                                count,
                                "stone",
                                totalStone));
                done = true;
                server.halt(false);
            }
        } catch (Exception ex) {
            atomic(
                    "soak-result.json",
                    Map.of(
                            "pass",
                            false,
                            "cause",
                            ex.toString(),
                            "utc",
                            Instant.now().toString(),
                            "serverTick",
                            server.getTickCount()));
            done = true;
            server.halt(false);
        }
    }
}
