package dev.starbridge.qa.integrated;

import com.google.gson.GsonBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Run the same native hull, cargo, deck and fare probes as the dedicated benchmark in a
 * singleplayer game.
 */
@Mod("starbridgeintegratedqa")
@Mod.EventBusSubscriber(modid = "starbridgeintegratedqa")
public final class IntegratedFlight {
    static final boolean ENABLED = Files.isRegularFile(Path.of("integrated-flight.fixture"));
    static final int LENGTH = Integer.getInteger("starbridge.integrated.length", 100);
    static volatile boolean preparing, ready, settled, failed;
    static volatile long origin;
    static int ticks;
    static long restoreStart, restoreEnd;
    static Class<?> fleet;
    static Method restore, seat, advance;
    static Field fleetTick, flight;

    static Method method(String name, Class<?>... types) throws Exception {
        var m = fleet.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    static Field field(String name) throws Exception {
        var f = fleet.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static void write(String name, Object data) throws Exception {
        var file = Path.of(name);
        var tmp = file.resolveSibling(name + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(data));
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void fail(Throwable problem) {
        failed = true;
        problem.printStackTrace();
        try {
            write(
                    "integrated-failure.json",
                    Map.of(
                            "cause",
                            problem.toString(),
                            "nested",
                            String.valueOf(problem.getCause()),
                            "serverTick",
                            ticks));
        } catch (Exception ignored) {
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (!ENABLED || failed || settled || e.phase != TickEvent.Phase.END) return;
        if (FlightProfile.ENABLED) FlightProfile.server = Thread.currentThread();
        var s = e.getServer();
        if (s.isDedicatedServer()) return;
        var p = s.getPlayerList().getPlayerByName("FleetCaptain");
        if (p == null) return;
        try {
            ++ticks;
            if (ticks == 1) {
                if (!Arrays.asList(100, 200, 300).contains(LENGTH)
                        || s.getWorldData().getAllowCommands()
                        || p.isCreative()
                        || p.isSpectator()
                        || s.isPublished())
                    throw new IllegalStateException(
                            "Expected isolated non-cheating singleplayer host");
                fleet = Class.forName("dev.starbridge.qa.fleet.FleetScenario");
                restore = method("initializeDeck", MinecraftServer.class, int.class, int.class);
                seat = method("seat", MinecraftServer.class, ServerPlayer.class);
                advance = method("advanceFlight", MinecraftServer.class, ServerPlayer.class);
                fleetTick = field("tick");
                flight = field("flight");
                origin = System.nanoTime();
                return;
            }
            if (ticks < 100) return;
            if (!ready) {
                preparing = true;
                restoreStart = System.nanoTime();
                restore.invoke(null, s, LENGTH, 0);
                seat.invoke(null, s, p);
                restoreEnd = System.nanoTime();
                ready = true;
                preparing = false;
            }
            fleetTick.setInt(null, ticks);
            advance.invoke(null, s, p);
            @SuppressWarnings("unchecked")
            var f = (Map<String, Object>) flight.get(null);
            if (f.containsKey("error")) throw new IllegalStateException(f.get("error").toString());
            if (Boolean.TRUE.equals(f.get("finished"))) {
                var r = new LinkedHashMap<String, Object>();
                r.put("integrated", true);
                r.put("publishedLan", s.isPublished());
                r.put("allowCommands", s.getWorldData().getAllowCommands());
                r.put("seed", s.overworld().getSeed());
                r.put("length", LENGTH);
                r.put("hostCount", s.getPlayerList().getPlayerCount());
                r.put("actualSingleplayerOwner", s.isSingleplayerOwner(p.getGameProfile()));
                r.put("restoreMs", (restoreEnd - restoreStart) / 1e6);
                r.put("flight", f);
                write("integrated-server.json", r);
                settled = true;
            } else if (ticks > 5100)
                throw new IllegalStateException(
                        "Integrated flight timed out at " + p.serverLevel().dimension().location());
        } catch (Exception | LinkageError ex) {
            fail(ex);
        }
    }
}
