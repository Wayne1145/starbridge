package dev.starbridge.qa.integrated;

import com.google.gson.Gson;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import java.nio.file.*;
import java.util.*;

/** Observe the fall-check ray before vanilla traverses distant terrain in a failed handoff. */
public final class HandoffTrace {
    public static final boolean ENABLED = Files.isRegularFile(Path.of("trace-handoff.fixture"));
    private static final Map<UUID, Vec3> previous = new HashMap<>();
    private static final Deque<Map<String, Object>> history = new ArrayDeque<>();
    private static final Gson JSON = new Gson();

    private static final Map<UUID, Integer> written = new HashMap<>();
    private static final Map<UUID, Integer> starting = new HashMap<>();

    private static void recordTransfer(ServerPlayer player) {
        String phase = Pilot.phase;
        if (!phase.endsWith("handoff") && !phase.endsWith("deck")) return;
        int tick = player.serverLevel().getServer().getTickCount();
        int start = starting.computeIfAbsent(player.getUUID(), id -> tick);
        if (tick - start > 3000
                || tick % 3 != 0
                || Objects.equals(written.put(player.getUUID(), tick), tick)) return;
        var row = state(player);
        row.put("phase", phase);
        try {
            Object info = player.getClass().getMethod("getDraggingInformation").invoke(player);
            Object shipId = info.getClass().getMethod("getLastShipStoodOn").invoke(info);
            row.put("lastStoodOn", String.valueOf(shipId));
            row.put(
                    "dragVelocity",
                    String.valueOf(
                            info.getClass().getMethod("getAddedMovementLastTick").invoke(info)));
            if (shipId instanceof Number id) {
                var utils = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
                var getter =
                        Arrays.stream(utils.getMethods())
                                .filter(
                                        m ->
                                                m.getName().equals("getShipObjectWorld")
                                                        && m.getParameterCount() == 1
                                                        && m.getParameterTypes()[0].isInstance(
                                                                player.serverLevel()))
                                .findFirst()
                                .orElseThrow();
                var world = getter.invoke(null, player.serverLevel());
                var ships = world.getClass().getMethod("getAllShips").invoke(world);
                var found =
                        ships.getClass()
                                .getMethod("getById", long.class)
                                .invoke(ships, id.longValue());
                if (found != null)
                    for (String method : List.of("getPrevTickTransform", "getTransform")) {
                        var transform = found.getClass().getMethod(method).invoke(found);
                        var pos = transform.getClass().getMethod("getPosition").invoke(transform);
                        row.put(method, pos.toString());
                    }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            row.put("nativeProbe", ex.toString());
        }
        try {
            Files.writeString(
                    Path.of("handoff-ticks.jsonl"),
                    JSON.toJson(row) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static Map<String, Object> state(ServerPlayer player) {
        var row = new LinkedHashMap<String, Object>();
        row.put("tick", player.serverLevel().getServer().getTickCount());
        row.put("world", player.serverLevel().dimension().location().toString());
        row.put("player", player.getScoreboardName());
        row.put("position", player.position().toString());
        row.put("previous", List.of(player.xo, player.yo, player.zo));
        row.put("delta", player.getDeltaMovement().toString());
        row.put("fallDistance", player.fallDistance);
        return row;
    }

    public static void before(ServerPlayer player, Vec3 displacement) {
        recordTransfer(player);
        var pos = player.position();
        var last = previous.put(player.getUUID(), pos);
        var row = state(player);
        row.put("requestedMove", displacement.toString());
        if (last != null) row.put("fromPreviousMove", last.distanceTo(pos));
        history.addLast(row);
        if (history.size() > 18) history.removeFirst();
    }

    public static void ray(ServerPlayer player, ClipContext context) {
        if (context.getFrom().distanceToSqr(context.getTo()) <= 64 * 64) return;
        var row = state(player);
        row.put("from", context.getFrom().toString());
        row.put("to", context.getTo().toString());
        row.put("length", context.getFrom().distanceTo(context.getTo()));
        row.put("recentMoves", List.copyOf(history));
        try {
            Object info = player.getClass().getMethod("getDraggingInformation").invoke(player);
            for (var method : info.getClass().getMethods())
                if (method.getParameterCount() == 0
                        && (method.getName().startsWith("get")
                                && !method.getName().equals("getClass")))
                    row.put(method.getName(), String.valueOf(method.invoke(info)));
        } catch (ReflectiveOperationException ex) {
            row.put("draggingRead", ex.toString());
        }
        try {
            Files.writeString(
                    Path.of("handoff-ray.jsonl"),
                    JSON.toJson(row) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        // This targeted diagnostic fails explicitly before forcing thousands of chunks into memory.
        throw new IllegalStateException(
                "Captured impossible fall-check ray of "
                        + row.get("length")
                        + " blocks; see handoff-ray.jsonl");
    }
}
