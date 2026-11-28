package dev.starbridge.qa.home;

import dev.starbridge.flight.Vec3d;
import dev.starbridge.orbit.OrbitalFlight;
import net.minecraft.server.MinecraftServer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;

/** Two synthetic traffic records, in the real server ledger. No extra player or ship is spawned. */
final class RadarTraffic {
    static final UUID A = UUID.fromString("a115ea91-4230-418c-90a4-f7a43949fbb1");
    static final UUID B = UUID.fromString("b224fb02-06e7-4800-88d3-461c21593f12");
    static volatile int requested;
    private static int recorded = -1;
    private static Map<UUID, Object> legs;
    private static Object first, second;
    private static Field pose;
    private static final List<Object> phases = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static void attach(MinecraftServer server) throws ReflectiveOperationException {
        Class<?> ledger = Class.forName("dev.starbridge.orbit.OrbitLedger");
        var get = ledger.getDeclaredMethod("get", MinecraftServer.class);
        get.setAccessible(true);
        var map = ledger.getDeclaredField("legs");
        map.setAccessible(true);
        legs = (Map<UUID, Object>) map.get(get.invoke(null, server));
        Class<?> leg = Class.forName("dev.starbridge.orbit.OrbitLedger$Leg");
        Constructor<?> create =
                leg.getDeclaredConstructor(
                        UUID.class,
                        UUID.class,
                        String.class,
                        String.class,
                        OrbitalFlight.Pose.class);
        create.setAccessible(true);
        pose = leg.getDeclaredField("pose");
        pose.setAccessible(true);
        var parked = new OrbitalFlight.Pose(Vec3d.ZERO, Vec3d.ZERO, 0, 0);
        first = create.newInstance(A, new UUID(0, 1), "moon", "home", parked);
        second = create.newInstance(B, new UUID(0, 2), "moon", "home", parked);
        RemoteHomeWorld.require(
                legs.putIfAbsent(A, first) == null && legs.putIfAbsent(B, second) == null,
                "Fresh synthetic radar IDs");
    }

    static void update(MinecraftServer server, UUID captainCore, Map<String, Object> status)
            throws Exception {
        if (requested == 3 && recorded == 3) return;
        if (legs == null) attach(server);
        int stage = requested;
        RemoteHomeWorld.require(
                legs.containsKey(captainCore) && "home".equals(status.get("target")),
                "Radar fixture accompanies original return only");
        var ship =
                new Vec3d(
                        (double) status.get("x"),
                        (double) status.get("y"),
                        (double) status.get("z"));
        var a = ship.add(new Vec3d(stage == 0 ? 1000 : 2200, 120, 220));
        var b = ship.add(new Vec3d(stage == 0 ? -2050 : -800, -180, -300));
        if (stage < 3) pose.set(first, new OrbitalFlight.Pose(a, Vec3d.ZERO, 0, 0));
        if (stage < 2) pose.set(second, new OrbitalFlight.Pose(b, Vec3d.ZERO, 0, 0));
        if (stage >= 2) legs.remove(B, second);
        if (stage == 3) legs.remove(A, first);
        if (recorded != stage) {
            var records = new LinkedHashMap<String, Object>();
            if (legs.get(A) == first) records.put(A.toString(), a);
            if (legs.get(B) == second) records.put(B.toString(), b);
            phases.add(
                    Map.of(
                            "phase",
                            stage,
                            "tick",
                            server.getTickCount(),
                            "core",
                            captainCore,
                            "target",
                            status.get("target"),
                            "ship",
                            ship,
                            "contacts",
                            records));
            RemoteHomeWorld.write(
                    "radar-server.json", Map.of("syntheticLedgerTraffic", true, "phases", phases));
            recorded = stage;
        }
    }
}
