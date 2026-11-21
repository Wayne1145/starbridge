package dev.starbridge.qa.berth;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import dev.starbridge.orbit.OrbitWire;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Measure two first visits without requesting any of the destination chunks before the pilots do.
 */
@Mod.EventBusSubscriber(modid = "starbridgefleetqa")
public final class FirstVisit {
    static final boolean ENABLED = Files.isRegularFile(Path.of("starbridge-split-worlds.fixture"));
    static final String[] KEYS = {"extraplanets.venusep", "galaxyspace.ceres"};
    static final int[][] POINTS = {{336, -272}, {-320, 352}};
    static final Visit[] visits = {new Visit(), new Visit()};

    static final class Visit {
        CelestialPort port;
        ServerLevel world;
        long selected, firstChunk, clear, land, entered, active;
        int chunks, fresh, previousCount;
        boolean beforeResident, beforeOnDisk;
        int playersBefore;
    }

    static boolean split() {
        return ENABLED && ConcurrentBerths.round == 2;
    }

    static String world(int index) {
        return split()
                ? visits[index].port.dimension().location().toString()
                : ConcurrentBerths.MOON;
    }

    static String route(int index) {
        return split() ? visits[index].port.title() : "route.starbridge.moon";
    }

    static int fare(int i) {
        return split() ? CelestialPort.HOME.fareTo(visits[i].port) : ConcurrentBerths.FARE;
    }

    static boolean allocated(Path region, int cx, int cz) throws Exception {
        if (!Files.isRegularFile(region)) return false;
        try (var f = new RandomAccessFile(region.toFile(), "r")) {
            f.seek(4L * ((cx & 31) + 32 * (cz & 31)));
            return f.readInt() != 0;
        }
    }

    static void prepare(MinecraftServer server, int i, ShipCoreBlockEntity core) throws Exception {
        if (!split()) return;
        var v = visits[i];
        v.port = OriginalStarMap.ports(server).get(KEYS[i]);
        ConcurrentBerths.require(
                v.port != null
                        && v.port.provider().equals(i == 0 ? CelestialPort.EP : CelestialPort.GS),
                "Missing original world " + KEYS[i]);
        v.world = server.getLevel(v.port.dimension());
        int cx = POINTS[i][0] >> 4, cz = POINTS[i][1] >> 4;
        v.playersBefore = v.world.players().size();
        v.previousCount = v.world.getChunkSource().getLoadedChunksCount();
        v.beforeResident = v.world.getChunkSource().hasChunk(cx, cz);
        Path file =
                Path.of(
                        "world",
                        "dimensions",
                        "retrofit",
                        v.port.dimension().location().getPath(),
                        "region",
                        "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        v.beforeOnDisk = allocated(file, cx, cz);
        ConcurrentBerths.require(
                !v.beforeResident && !v.beforeOnDisk && v.playersBefore == 0,
                "Target column or player already present " + KEYS[i]);
        // The first-visit load is the object under test. Set documented original chart tier as a
        // fixture precondition.
        var tag = core.saveWithoutMetadata();
        tag.putInt("NavigationTier", v.port.tier());
        core.load(tag);
        core.setChanged();
    }

    static int index(ServerPlayer p) {
        return Arrays.asList(ConcurrentBerths.NAMES).indexOf(p.getGameProfile().getName());
    }

    static void request(ServerPlayer p, OrbitWire.Control packet) {
        if (!split()) return;
        int i = index(p);
        if (i < 0 || visits[i].port == null) return;
        var v = visits[i];
        if (packet.action() == OrbitWire.Action.SURVEY
                && packet.approachX() == POINTS[i][0]
                && packet.approachZ() == POINTS[i][1]
                && v.selected == 0) v.selected = System.nanoTime();
        if (packet.action() == OrbitWire.Action.LAND && ConcurrentBerths.replay && v.land == 0)
            v.land = System.nanoTime();
    }

    public static void response(ServerPlayer p, Object packet) {
        if (!split() || !(packet instanceof OrbitWire.Site site)) return;
        int i = index(p);
        if (i < 0) return;
        var v = visits[i];
        if (site.x() == POINTS[i][0]
                && site.z() == POINTS[i][1]
                && site.clearance() == OrbitWire.Clearance.CLEAR
                && v.clear == 0) v.clear = System.nanoTime();
    }

    @SubscribeEvent
    public static void chunk(ChunkEvent.Load event) {
        if (!split()) return;
        for (int i = 0; i < 2; i++) {
            var v = visits[i];
            if (v.world == null || event.getLevel() != v.world || v.selected == 0) continue;
            var chunk = event.getChunk().getPos();
            if (chunk.x == POINTS[i][0] >> 4 && chunk.z == POINTS[i][1] >> 4) {
                if (v.firstChunk == 0) v.firstChunk = System.nanoTime();
                ++v.chunks;
                if (event.isNewChunk()) ++v.fresh;
            }
        }
    }

    static void observe(MinecraftServer server) {
        if (!split()) return;
        for (int i = 0; i < 2; i++) {
            var v = visits[i];
            if (v.world == null || v.land == 0) continue;
            var p = server.getPlayerList().getPlayerByName(ConcurrentBerths.NAMES[i]);
            if (p != null && p.serverLevel() == v.world && v.entered == 0)
                v.entered = System.nanoTime();
            if (v.active == 0 && ConcurrentBerths.arrived(server, i)) v.active = System.nanoTime();
        }
    }

    static List<Object> result() {
        var all = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var v = visits[i];
            ConcurrentBerths.require(
                    v.selected > 0
                            && v.clear >= v.selected
                            && v.land >= v.clear
                            && v.entered >= v.land
                            && v.active >= v.entered,
                    "Incomplete first-visit timings for " + KEYS[i]);
            var m = new LinkedHashMap<String, Object>();
            m.put("pilot", ConcurrentBerths.NAMES[i]);
            m.put("route", KEYS[i]);
            m.put("registeredTitle", v.port.title());
            m.put("provider", v.port.provider());
            m.put("world", world(i));
            m.put("exactMapSelection", POINTS[i]);
            m.put("crewBefore", v.playersBefore);
            m.put("loadedChunksWhenFixtureBegan", v.previousCount);
            m.put("selectedColumnPreviouslyLoaded", v.beforeResident);
            m.put("selectedColumnPresentOnDiskBeforeVisit", v.beforeOnDisk);
            m.put("selectedColumnLoadEvents", v.chunks);
            m.put("selectedColumnNewGenerationEvents", v.fresh);
            m.put(
                    "surveyRequestToFirstColumnLoadMs",
                    v.firstChunk == 0 ? null : (v.firstChunk - v.selected) / 1e6);
            m.put("surveyRequestToFirstClearMapMs", (v.clear - v.selected) / 1e6);
            m.put("confirmToPlayerDimensionChangeMs", (v.entered - v.land) / 1e6);
            m.put("confirmToSettledDeliveryMs", (v.active - v.land) / 1e6);
            all.add(m);
        }
        return all;
    }
}
