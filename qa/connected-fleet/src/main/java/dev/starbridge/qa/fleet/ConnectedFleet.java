package dev.starbridge.qa.fleet;

import com.google.gson.GsonBuilder;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.orbit.OrbitalFlight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod("starbridgefleetqa")
@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public class ConnectedFleet {
    static final int[] LENGTHS = {100, 200, 300};
    static final int WIDTH = 8, BASE = 155, COUNT = 1200;
    static final BlockPos[] cores = new BlockPos[3];
    static final UUID[] pilots = new UUID[3];
    static final List<Object> ships = new ArrayList<>();
    static final List<Map<String, Object>> tiers = new ArrayList<>();
    static final Map<UUID, Integer> onboard = new HashMap<>();
    static final Map<UUID, Double> priorX = new HashMap<>();
    static final Set<UUID> moved = new HashSet<>(), movedDuringSample = new HashSet<>();
    static final double[] work = new double[COUNT], interval = new double[COUNT];
    static int generation, stage = 10, samples, stable, opened, stageFurnace;
    static long begin, startTick, previous;
    static boolean enabled, done;

    static Path file(String name) {
        return Path.of(name);
    }

    static void save(String file, Object obj) throws Exception {
        Files.writeString(file(file), new GsonBuilder().setPrettyPrinting().create().toJson(obj));
    }

    static void ask() throws Exception {
        Files.writeString(file("fleet-peer-request.txt"), Integer.toString(stage));
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) throws Exception {
        if (!Files.exists(file("starbridge-fleet.fixture"))) return;
        var s = event.getServer();
        if (!s.isDedicatedServer()
                || !(s.getLocalIp().equals("127.0.0.1") || s.getLocalIp().equals("localhost"))
                || s.getPort() != 25890)
            throw new IllegalStateException("Fleet QA needs its local test port");
        enabled = true;
        opened = s.getTickCount();
    }
    /* Build a slice on each tick so test preparation does not block the main loop. */
    static void hull(ServerLevel w, int ship, int z) {
        int x = ship * 640, n = LENGTHS[ship];
        if (z == 0) cores[ship] = new BlockPos(x + 2, BASE + 1, 1);
        for (int side = 0; side < WIDTH; side++) {
            w.setBlockAndUpdate(
                    new BlockPos(x + side, BASE, z), Blocks.IRON_BLOCK.defaultBlockState());
            w.setBlockAndUpdate(
                    new BlockPos(x + side, BASE + 3, z), Blocks.SMOOTH_STONE.defaultBlockState());
            if (side == 0 || side == WIDTH - 1 || z == 0 || z == n - 1) {
                w.setBlockAndUpdate(
                        new BlockPos(x + side, BASE + 1, z), Blocks.IRON_BLOCK.defaultBlockState());
                w.setBlockAndUpdate(
                        new BlockPos(x + side, BASE + 2, z), Blocks.GLASS.defaultBlockState());
            }
        }
        if (z % 20 == 9) {
            BlockPos pos = new BlockPos(x + 5, BASE + 1, z);
            w.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
            var f = (FurnaceBlockEntity) w.getBlockEntity(pos);
            f.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            f.setItem(1, new ItemStack(Items.COAL, 16));
        }
        if (z % 20 == 10)
            w.setBlockAndUpdate(new BlockPos(x + 5, BASE + 1, z), Blocks.CHEST.defaultBlockState());
        if (z == n - 1) {
            w.setBlockAndUpdate(cores[ship], Starbridge.SHIP_CORE.get().defaultBlockState());
            var core = (ShipCoreBlockEntity) w.getBlockEntity(cores[ship]);
            core.bindOriginalRocket(UUID.randomUUID());
            OrbitModule.bindCabin(
                    core, new BlockPos(x, BASE, 0), new BlockPos(x + WIDTH - 1, BASE + 3, n - 1));
            var energy =
                    core.getCapability(ForgeCapabilities.ENERGY)
                            .orElseThrow(IllegalStateException::new);
            for (int j = 0; j < 200; j++) energy.receiveEnergy(10000, false);
            core.register();
            int blocks = 0;
            for (var pt :
                    BlockPos.betweenClosed(
                            new BlockPos(x, BASE, 0), new BlockPos(x + WIDTH - 1, BASE + 3, n - 1)))
                if (!w.getBlockState(pt).isAir()) blocks++;
            ships.add(
                    Map.of(
                            "length",
                            n,
                            "width",
                            WIDTH,
                            "height",
                            4,
                            "nonAir",
                            blocks,
                            "furnaces",
                            n / 20,
                            "chests",
                            n / 20,
                            "id",
                            core.id().toString()));
        }
    }
    /**
     * Place the already running route far enough from final approach to measure three consecutive
     * time windows.
     */
    private static void seedLongRoute(MinecraftServer server, ShipCoreBlockEntity core)
            throws ReflectiveOperationException {
        var type = Class.forName("dev.starbridge.orbit.OrbitLedger");
        var get = type.getDeclaredMethod("get", MinecraftServer.class);
        get.setAccessible(true);
        Object ledger = get.invoke(null, server);
        var legs = type.getDeclaredField("legs");
        legs.setAccessible(true);
        Object leg = ((Map<?, ?>) legs.get(ledger)).get(core.id());
        var pose = leg.getClass().getDeclaredField("pose");
        pose.setAccessible(true);
        pose.set(leg, new OrbitalFlight.Pose(new Vec3d(0, 0, -150000), Vec3d.ZERO, 0, 0));
    }

    static int furnace(ServerLevel w) {
        int n = 0;
        for (int i = 0; i < 3; i++)
            for (int z = 9; z < LENGTHS[i]; z += 20) {
                var p = new BlockPos(i * 640 + 5, BASE + 1, z);
                if (w.isLoaded(p) && w.getBlockEntity(p) instanceof FurnaceBlockEntity f)
                    n += f.getItem(2).getCount();
            }
        return n;
    }

    static double centile(double[] source, double q) {
        double[] temp = source.clone();
        Arrays.sort(temp);
        return temp[(int) Math.ceil(q * temp.length) - 1];
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent ev) throws Exception {
        if (!enabled || done) return;
        long now = System.nanoTime();
        if (ev.phase == TickEvent.Phase.START) {
            startTick = now;
            return;
        }
        var server = ev.getServer();
        var w = server.overworld();
        int tick = server.getTickCount();
        if (generation < 600) {
            for (int step = 0; step < 3 && generation < 600; step++, generation++) {
                int line = generation;
                int which = 0;
                while (line >= LENGTHS[which]) {
                    line -= LENGTHS[which];
                    which++;
                }
                hull(w, which, line);
            }
            if (generation == 600) {
                save("fleet-hulls.json", ships);
                ask();
            }
            return;
        }
        var online = server.getPlayerList().getPlayers();
        for (var p : online) {
            String name = p.getGameProfile().getName();
            if (!name.matches("FleetQA[0-9]{2}")) continue;
            int number = Integer.parseInt(name.substring(7)) - 1;
            if (number < 0 || number >= 30) continue;
            int i = number % 3;
            var id = p.getUUID();
            var core = (ShipCoreBlockEntity) w.getBlockEntity(cores[i]);
            if (!onboard.containsKey(id)) {
                int row = number / 3;
                double x = i * 640 + 1.5 + (row % 4), z = 4.5 + row * 3;
                p.teleportTo(w, x, BASE + 1, z, 0, 0);
                onboard.put(id, i);
                priorX.put(id, x);
                if (pilots[i] == null) {
                    pilots[i] = id;
                    core.setOwner(id);
                    OrbitModule.begin(p, core, "home", "moon", true);
                    seedLongRoute(server, core);
                }
            } else if (Math.abs(p.getX() - priorX.get(id)) > .025) {
                priorX.put(id, p.getX());
                moved.add(id);
                if (stable >= 100) movedDuringSample.add(id);
            }
        }
        if (tick % 40 == 0)
            save(
                    "fleet-live.json",
                    Map.of(
                            "stage",
                            stage,
                            "connected",
                            online.size(),
                            "sample",
                            samples,
                            "movingPeers",
                            moved.size(),
                            "tick",
                            tick));
        if (online.size() != stage || moved.size() < stage) {
            stable = 0;
            samples = 0;
            movedDuringSample.clear();
            return;
        }
        if (++stable <= 100) {
            if (stable == 100) {
                begin = now;
                previous = now;
                stageFurnace = furnace(w);
                movedDuringSample.clear();
            }
            return;
        }
        work[samples] = (now - startTick) / 1e6;
        interval[samples] = (now - previous) / 1e6;
        previous = now;
        ++samples;
        if (samples < COUNT) return;
        List<Object> orbits = new ArrayList<>();
        boolean allMoving = true;
        for (var pos : cores) {
            var s = OrbitModule.status((ShipCoreBlockEntity) w.getBlockEntity(pos));
            orbits.add(s);
            allMoving &=
                    Boolean.TRUE.equals(s.get("active"))
                            && s.get("speed") instanceof Number n
                            && n.doubleValue() > .1;
        }
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("connected", online.size());
        sample.put("uniqueMoving", movedDuringSample.size());
        sample.put("ticks", samples);
        sample.put("seconds", (now - begin) / 1e9);
        sample.put("tps", samples * 1e9 / (now - begin));
        sample.put(
                "tickBodyMs",
                List.of(
                        centile(work, .5),
                        centile(work, .95),
                        centile(work, .99),
                        centile(work, 1)));
        sample.put(
                "tickIntervalMs",
                List.of(
                        centile(interval, .5),
                        centile(interval, .95),
                        centile(interval, .99),
                        centile(interval, 1)));
        int furnaceOutput = furnace(w) - stageFurnace;
        sample.put("furnaceOutput", furnaceOutput);
        sample.put("orbits", orbits);
        sample.put("allThreeMoving", allMoving);
        sample.put("pass", allMoving && furnaceOutput > 0 && movedDuringSample.size() == stage);
        tiers.add(sample);
        save("fleet-measurements.json", Map.of("ships", ships, "tiers", tiers));
        if (stage >= 30) {
            done = true;
            save(
                    "fleet-result.json",
                    Map.of("pass", tiers.size() == 3 && tiers.stream().allMatch(t -> Boolean.TRUE.equals(t.get("pass"))), "ships", ships, "tiers", tiers));
        } else {
            stage += 10;
            samples = 0;
            stable = 0;
            ask();
        }
    }
}
