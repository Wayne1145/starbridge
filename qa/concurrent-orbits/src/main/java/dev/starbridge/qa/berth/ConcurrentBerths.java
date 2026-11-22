package dev.starbridge.qa.berth;

import com.google.gson.GsonBuilder;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.*;
import dev.starbridge.qa.home.landing.ApproachTiming;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.transit.HullTransit;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public final class ConcurrentBerths {
    static final boolean ENABLED =
            Files.isRegularFile(Path.of("starbridge-concurrent-berths.fixture"));
    static final boolean WIDE = "true".equals(System.getenv("GITHUB_ACTIONS"))
            && "Linux".equals(System.getenv("RUNNER_OS"))
            && "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
            && Files.isRegularFile(Path.of("remote-wide-berths.fixture"));
    static final int WIDE_LENGTH = 300, WIDE_WIDTH = 19, WIDE_HEIGHT = 8;
    static final int[] WIDE_X = {-240, 240};
    static final int WIDE_Z = -64;
    static final String[] NAMES = {"OrbitCaptain", "OrbitEngineer"};
    static final String MOON = "retrofit:legacy_dim_-28";
    static final VsShipBackend VS = new VsShipBackend();
    static final int FARE = 12000;
    static final List<Object> rounds = new ArrayList<>();
    static final Map<UUID, Held> held = new LinkedHashMap<>();
    static Ship[] ships = new Ship[2];
    static int round, phase, start, dispatched, loser = -1, validTicks;
    static boolean replay, done;
    static Map<String, Object> report;

    record Held(UUID player, OrbitWire.Control packet, int received) {}

    record Ship(UUID core, UUID rocket, BlockPos oldBridge, int diamonds) {}

    static void save(String name, Object data) throws Exception {
        Path f = Path.of(name);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(data));
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void require(boolean ok, String issue) {
        if (!ok) throw new IllegalStateException(issue);
    }

    static void order(int i, String action, int x, int z) throws Exception {
        var pos = ships[i].oldBridge();
        save(
                "../" + NAMES[i] + "/berth-order.json",
                Map.of(
                        "round",
                        round,
                        "action",
                        action,
                        "x",
                        x,
                        "z",
                        z,
                        "route",
                        FirstVisit.route(i),
                        "core",
                        List.of(pos.getX(), pos.getY(), pos.getZ())));
    }

    public static boolean queue(ServerPlayer p, OrbitWire.Control packet) {
        if (ENABLED && !done) FirstVisit.request(p, packet);
        if (!ENABLED || done || replay || packet.action() != OrbitWire.Action.LAND) return false;
        for (int i = 0; i < 2; i++)
            if (ships[i] != null
                    && ships[i].core().equals(packet.core())
                    && NAMES[i].equals(p.getGameProfile().getName())) {
                requestedX[i] = packet.approachX();
                requestedZ[i] = packet.approachZ();
            }
        if (phase != 1) return false;
        for (int i = 0; i < 2; i++)
            if (ships[i].core().equals(packet.core())
                    && NAMES[i].equals(p.getGameProfile().getName())) {
                held.putIfAbsent(
                        p.getUUID(),
                        new Held(p.getUUID(), packet, p.serverLevel().getServer().getTickCount()));
                return true;
            }
        return false;
    }

    static void prepare(MinecraftServer server, int i) throws Exception {
        var level = server.overworld();
        var pilot = server.getPlayerList().getPlayerByName(NAMES[i]);
        require(
                pilot != null && pilot.isAlive() && !pilot.isCreative() && !pilot.hasPermissions(2),
                "ordinary active pilot needed");
        int x = round * 240 + i * 75;
        var bridge = new BlockPos(x + 1, 129, 1);
        var engine =
                Objects.requireNonNull(
                        ForgeRegistries.BLOCKS.getValue(
                                new ResourceLocation("advancedrocketry", "rocketmotor")));
        for (int dx = 0; dx < 5; dx++)
            for (int z = 0; z < 6; z++)
                level.setBlockAndUpdate(
                        new BlockPos(x + dx, 128, z), Blocks.IRON_BLOCK.defaultBlockState());
        if (WIDE) buildWideDeck(level, x);
        level.setBlockAndUpdate(new BlockPos(x + 2, 128, 0), engine.defaultBlockState());
        level.setBlockAndUpdate(bridge, Starbridge.SHIP_CORE.get().defaultBlockState());
        level.setBlockAndUpdate(bridge.offset(2, 0, 2), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(bridge.offset(-1, 0, 3), Blocks.FURNACE.defaultBlockState());
        var core = (ShipCoreBlockEntity) level.getBlockEntity(bridge);
        core.setOwner(pilot.getUUID());
        var rocket = UUID.randomUUID();
        core.bindOriginalRocket(rocket);
        core.register();
        var max = WIDE ? new BlockPos(x + WIDE_WIDTH - 1, 128 + WIDE_HEIGHT - 1, WIDE_LENGTH - 1)
                : new BlockPos(x + 4, 129, 5);
        OrbitModule.bindCabin(core, new BlockPos(x, 128, 0), max);
        core.getCapability(ForgeCapabilities.ENERGY)
                .resolve()
                .orElseThrow()
                .receiveEnergy(100000, false);
        int diamonds = 11 + 7 * i + 2 * round;
        ((ChestBlockEntity) level.getBlockEntity(bridge.offset(2, 0, 2)))
                .setItem(0, new ItemStack(Items.DIAMOND, diamonds));
        var oven = (AbstractFurnaceBlockEntity) level.getBlockEntity(bridge.offset(-1, 0, 3));
        oven.setItem(0, new ItemStack(Items.IRON_ORE, 6));
        oven.setItem(1, new ItemStack(Items.COAL, 3));
        if (WIDE) {
            var tail = (ChestBlockEntity) level.getBlockEntity(bridge.offset(1, 0, WIDE_LENGTH - 3));
            tail.setItem(26, new ItemStack(Items.EMERALD, diamonds + 3));
        }
        var equip =
                Class.forName("dev.starbridge.flightqa.OriginalEquipment")
                        .getDeclaredMethod("equip", ServerPlayer.class);
        equip.setAccessible(true);
        var equipment = equip.invoke(null, pilot);
        if (WIDE) {
            var suit = ((Map<?, ?>) equipment).get("recipes");
            require(suit instanceof List<?> pieces && pieces.size() == 4, "Four sealed original suit pieces required");
            save("berth-equipment-" + i + ".json", equipment);
        }
        pilot.teleportTo(level, x + 1.5, 129, 2.5, 180, 30);
        pilot.getInventory().selected = 0;
        pilot.getInventory().setItem(0, ItemStack.EMPTY);
        ships[i] = new Ship(core.id(), rocket, bridge, diamonds);
        FirstVisit.prepare(server, i, core);
        order(
                i,
                "select",
                FirstVisit.split()
                        ? FirstVisit.POINTS[i][0]
                        : WIDE ? WIDE_X[i] : round == 0 ? 64 : (i == 0 ? -192 : 240),
                FirstVisit.split()
                        ? FirstVisit.POINTS[i][1]
                        : WIDE ? WIDE_Z : round == 0 ? -64 : (i == 0 ? 65 : 155));
    }

    private static void buildWideDeck(ServerLevel level, int x) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int dx = 0; dx < WIDE_WIDTH; dx++) {
            for (int z = 0; z < WIDE_LENGTH; z++) {
                var base = new BlockPos(x + dx, 128, z);
                level.setBlock(base, Blocks.IRON_BLOCK.defaultBlockState(), flags);
                level.setBlock(base.above(4), dx >= 2 && dx <= 16
                        ? Blocks.BARREL.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState(), flags);
                level.setBlock(base.above(7), Blocks.IRON_BLOCK.defaultBlockState(), flags);
                if (dx == 0 || dx == WIDE_WIDTH - 1) {
                    for (int y : new int[]{1, 2, 3, 5, 6}) {
                        level.setBlock(base.above(y), Blocks.IRON_BLOCK.defaultBlockState(), flags);
                    }
                }
            }
        }
        level.setBlock(new BlockPos(x + 2, 129, WIDE_LENGTH - 2), Blocks.CHEST.defaultBlockState(), flags);
    }

    static ShipCoreBlockEntity core(int i) {
        return CoreRegistry.find(ships[i].core()).orElse(null);
    }

    static Map<String, Object> inspect(MinecraftServer server, int i) {
        var core = core(i);
        require(core != null, "missing core " + i);
        var w = (ServerLevel) core.getLevel();
        var pos = core.getBlockPos();
        var c = (ChestBlockEntity) w.getBlockEntity(pos.offset(2, 0, 2));
        var oven = (AbstractFurnaceBlockEntity) w.getBlockEntity(pos.offset(-1, 0, 3));
        var pilot = server.getPlayerList().getPlayerByName(NAMES[i]);
        var ship = VS.inspect(w, pos);
        var live = core.saveWithoutMetadata();
        var m = new LinkedHashMap<String, Object>();
        m.put("core", core.id());
        m.put("storageBridge", List.of(pos.getX(), pos.getY(), pos.getZ()));
        m.put(
                "playerWorld",
                pilot == null ? "offline" : pilot.serverLevel().dimension().location().toString());
        m.put("rocket", core.originalRocket());
        m.put("world", w.dimension().location().toString());
        m.put("FE", core.energy());
        m.put("receipt", live.hasUUID("PaidVoyage") ? live.getUUID("PaidVoyage").toString() : "");
        m.put(
                "diamonds",
                c != null && c.getItem(0).is(Items.DIAMOND) ? c.getItem(0).getCount() : -1);
        m.put(
                "furnaceUnits",
                oven == null ? -1 : oven.getItem(0).getCount() + oven.getItem(2).getCount());
        if (WIDE) {
            var tail = w.getBlockEntity(pos.offset(1, 0, WIDE_LENGTH - 3));
            m.put("tailEmeralds", tail instanceof ChestBlockEntity chest && chest.getItem(26).is(Items.EMERALD)
                    ? chest.getItem(26).getCount() : -1);
            m.put("sourceBridge", List.of(ships[i].oldBridge().getX(), ships[i].oldBridge().getY(), ships[i].oldBridge().getZ()));
        }
        m.put("owner", pilot == null ? "offline" : pilot.getUUID().toString());
        m.put("alive", pilot != null && pilot.isAlive());
        m.put("orbit", OrbitModule.active(core));
        m.put("fixedCabin", OrbitModule.cabinBounds(core).isPresent());
        m.put("pending", HullTransit.inProgress(server, core.id()));
        m.put("nativeShip", ship.map(s -> s.shipId()).orElse(-1L));
        m.put("aboard", pilot != null && HullTransit.isAboard(pilot, core));
        if (ship.isPresent()) {
            var p = ship.get().position();
            m.put("position", List.of(p.x(), p.y(), p.z()));
        }
        m.put(
                "oldFixedCabinCleared",
                server.overworld().getBlockEntity(ships[i].oldBridge()) == null);
        return m;
    }

    static int[] before = {0, 0}, requestedX = {0, 0}, requestedZ = {0, 0};
    static String[] receipt = {"", ""};

    static void dispatch(MinecraftServer server) throws Exception {
        var method =
                OrbitModule.class.getDeclaredMethod(
                        "control", ServerPlayer.class, OrbitWire.Control.class);
        method.setAccessible(true);
        var raw = new ArrayList<>();
        dispatched = server.getTickCount();
        replay = true;
        try {
            for (int i = 0; i < 2; i++) {
                var player = server.getPlayerList().getPlayerByName(NAMES[i]);
                var h = held.get(player.getUUID());
                require(h != null, "missing actual GUI input");
                Map<String, Object> pre = inspect(server, i);
                before[i] = (int) pre.get("FE");
                receipt[i] = (String) pre.get("receipt");
                require(
                        (int) pre.get("diamonds") == ships[i].diamonds()
                                && (int) pre.get("furnaceUnits") == 6
                                && receipt[i].isEmpty(),
                        "bad initial cargo");
                raw.add(
                        Map.of(
                                "receivedTick",
                                h.received(),
                                "processedTick",
                                dispatched,
                                "x",
                                h.packet().approachX(),
                                "z",
                                h.packet().approachZ(),
                                "before",
                                pre));
                method.invoke(null, player, h.packet());
                if (WIDE) {
                    var controls = Class.forName("dev.starbridge.orbit.LandingControl").getDeclaredField("jobs");
                    controls.setAccessible(true);
                    var survey = ((Map<?, ?>) controls.get(null)).get(ships[i].core());
                    require(survey != null, "Missing survey after the real client confirmation");
                    var confirm = survey.getClass().getDeclaredField("confirm");
                    confirm.setAccessible(true);
                    require(confirm.getBoolean(survey), "The real client confirmation was not accepted");
                }
            }
        } finally {
            replay = false;
        }
        var list = held.values().stream().map(Held::packet).toList();
        boolean equal =
                list.get(0).approachX() == list.get(1).approachX()
                        && list.get(0).approachZ() == list.get(1).approachZ();
        require(equal == (!WIDE && round == 0), "wrong selected sites");
        report.put("confirmation", raw);
        report.put("equalSite", equal);
        save("berth-live.json", report);
        held.clear();
        phase = 2;
    }

    static boolean arrived(MinecraftServer server, int i) {
        var core = core(i);
        if (core == null
                || !core.getLevel().dimension().location().toString().equals(FirstVisit.world(i))
                || HullTransit.inProgress(server, core.id())) return false;
        var p = server.getPlayerList().getPlayerByName(NAMES[i]);
        return p != null
                && p.isAlive()
                && p.serverLevel() == core.getLevel()
                && HullTransit.isAboard(p, core);
    }

    static void denied(MinecraftServer server, int i) throws Exception {
        var r = inspect(server, i);
        require(
                (boolean) r.get("orbit")
                        && (boolean) r.get("fixedCabin")
                        && !(boolean) r.get("pending")
                        && (long) r.get("nativeShip") == -1L
                        && receipt[i].equals(r.get("receipt")),
                "loser left orbit");
        require(
                (int) r.get("diamonds") == ships[i].diamonds() && (int) r.get("furnaceUnits") == 6,
                "denial changed cargo");
        int difference = before[i] - (int) r.get("FE");
        require(difference == 0, "charged a landing fee on denial");
        report.put("denied", r);
        report.put("deniedPilot", NAMES[i]);
        report.put("orbitalEnergyUsedBeforeDenial", difference);
        order(i, "correct", -105, 135);
        save("berth-live.json", report);
        phase = 3;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!ENABLED || done || event.phase != TickEvent.Phase.END) return;
        var s = event.getServer();
        int tick = s.getTickCount();
        try {
            require(
                    s.isDedicatedServer()
                            && s.getPort() == 26751
                            && "127.0.0.1".equals(s.getLocalIp()),
                    "isolated loopback port required");
            if (phase == 0) {
                if (s.getPlayerList().getPlayerCount() != 2) return;
                report = new LinkedHashMap<>();
                report.put("round", round);
                report.put("startedTick", tick);
                if (WIDE) {
                    require(!s.getWorldData().getAllowCommands(), "cheats must be off");
                    report.put("footprint", List.of(WIDE_LENGTH, WIDE_WIDTH, WIDE_HEIGHT));
                }
                prepare(s, 0);
                prepare(s, 1);
                start = tick;
                phase = 1;
                validTicks = 0;
                return;
            }
            if (WIDE && phase >= 2) {
                for (String name : NAMES) {
                    var player = s.getPlayerList().getPlayerByName(name);
                    require(player == null || player.isAlive(), name + " died during lunar transfer");
                }
            }
            FirstVisit.observe(s);
            if (tick - start > (WIDE ? 9000 : FirstVisit.split() ? 6400 : 4700))
                throw new IllegalStateException("timed out during stage " + phase);
            if (phase == 1 && held.size() == 2) {
                dispatch(s);
                return;
            }
            if (phase == 2 && round == 0 && !WIDE) {
                for (int i = 0; i < 2; i++) {
                    if (Files.isRegularFile(
                            Path.of("..", NAMES[i], "occupied-" + round + ".json"))) {
                        loser = i;
                        denied(s, i);
                        return;
                    }
                }
            } else if ((phase == 2 && (WIDE || round >= 1) || phase == 3) && arrived(s, 0) && arrived(s, 1)) {
                if (++validTicks < (FirstVisit.split() ? 200 : 40)) return;
                var finals = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    var a = inspect(s, i);
                    require(
                            (int) a.get("diamonds") == ships[i].diamonds()
                                    && (int) a.get("furnaceUnits") == 6
                                    && ships[i].rocket().equals(a.get("rocket"))
                                    && (boolean) a.get("oldFixedCabinCleared")
                                    && !(boolean) a.get("orbit")
                                    && !((String) a.get("receipt")).isBlank(),
                            "cargo, original ship, source, or receipt");
                    if (WIDE) require((int) a.get("tailEmeralds") == ships[i].diamonds() + 3, "Tail chest contents changed");
                    int energy = before[i] - (int) a.get("FE");
                    require(
                            energy == FirstVisit.fare(i),
                            "unexpected fare or double debit " + energy);
                    @SuppressWarnings("unchecked")
                    var pos = (List<Double>) a.get("position");
                    double error =
                            Math.hypot(
                                    pos.get(0) - requestedX[i] - .5,
                                    pos.get(2) - requestedZ[i] - .5);
                    require(error < 5, "did not reach selected berth: " + error);
                    a.put("selectedSite", List.of(requestedX[i], requestedZ[i]));
                    a.put("distanceFromRequestedCenter", error);
                    finals.add(a);
                }
                require(
                        !((Map<?, ?>) finals.get(0))
                                .get("receipt")
                                .equals(((Map<?, ?>) finals.get(1)).get("receipt")),
                        "receipts reused");
                report.put("landings", finals);
                report.put("finishedTick", tick);
                if (FirstVisit.split()) {
                    report.put("firstVisits", FirstVisit.result());
                    report.put("simultaneousOccupiedDestinationTicks", validTicks);
                }
                rounds.add(report);
                save("berth-rounds.json", rounds);
                if (round++ == (WIDE ? 0 : FirstVisit.ENABLED ? 2 : 1)) {
                    if (WIDE) ApproachTiming.stop();
                    save("berth-result.json", Map.of("pass", true, "rounds", rounds));
                    done = true;
                    if (!WIDE) s.halt(false);
                } else phase = 0;
            } else validTicks = 0;
        } catch (Exception ex) {
            if (WIDE) ApproachTiming.stop();
            save(
                    "berth-result.json",
                    Map.of(
                            "pass",
                            false,
                            "exception",
                            ex.toString(),
                            "phase",
                            phase,
                            "round",
                            round,
                            "completed",
                            rounds,
                            "recent",
                            report == null ? Map.of() : report));
            done = true;
            if (!WIDE) s.halt(false);
        }
    }
}
