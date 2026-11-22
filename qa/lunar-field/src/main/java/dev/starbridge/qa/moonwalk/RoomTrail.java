package dev.starbridge.qa.moonwalk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Chooses a short route through corridors the same explorer has already walked. */
final class RoomTrail {
    private record Step(BlockPos cell, double distance) {}

    private RoomTrail() {}

    static Deque<Vec3> towardDeepest(Iterable<Vec3> observed, Vec3 feet, Vec3 entry) {
        return plan(observed, feet, entry, true);
    }

    static Deque<Vec3> towardEntrance(Iterable<Vec3> observed, Vec3 feet, Vec3 entry) {
        return plan(observed, feet, entry, false);
    }

    private static Deque<Vec3> plan(Iterable<Vec3> observed, Vec3 feet, Vec3 entry, boolean explore) {
        Map<BlockPos, Vec3> positions = new LinkedHashMap<>();
        Map<BlockPos, List<BlockPos>> links = new LinkedHashMap<>();
        Vec3 previous = null;
        for (Vec3 current : observed) {
            if (Math.abs(current.y - feet.y) > 1.2) {
                previous = null;
                continue;
            }
            BlockPos to = cell(current);
            positions.put(to, current);
            if (previous != null && previous.distanceToSqr(current) <= 1.75 * 1.75) {
                BlockPos from = cell(previous);
                if (!from.equals(to)) {
                    link(links, from, to);
                    link(links, to, from);
                }
            }
            previous = current;
        }
        var route = new ArrayDeque<Vec3>();
        var start = positions.entrySet().stream()
                .min(Comparator.comparingDouble(p -> p.getValue().distanceToSqr(feet)))
                .orElse(null);
        if (start == null || start.getValue().distanceToSqr(feet) > 4) return route;

        var frontier = new PriorityQueue<>(Comparator.comparingDouble(Step::distance).thenComparing(Step::cell));
        var distances = new HashMap<BlockPos, Double>();
        var parents = new HashMap<BlockPos, BlockPos>();
        var destination = start.getKey();
        double best = entryScore(positions.get(destination), entry, explore);
        distances.put(destination, 0d);
        frontier.add(new Step(destination, 0));
        while (!frontier.isEmpty()) {
            var step = frontier.remove();
            if (step.distance() > distances.get(step.cell())) continue;
            Vec3 here = positions.get(step.cell());
            double score = entryScore(here, entry, explore);
            if (score > best) {
                destination = step.cell();
                best = score;
            }
            for (BlockPos next : links.getOrDefault(step.cell(), List.of())) {
                double distance = step.distance() + here.distanceTo(positions.get(next));
                if (distance >= distances.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                distances.put(next, distance);
                parents.put(next, step.cell());
                frontier.add(new Step(next, distance));
            }
        }
        if (destination.equals(start.getKey())) return route;
        for (BlockPos cell = destination; cell != null; cell = parents.get(cell)) route.addFirst(positions.get(cell));
        return route;
    }

    private static double entryScore(Vec3 feet, Vec3 entry, boolean explore) {
        if (explore) return feet.distanceToSqr(entry);
        double x = feet.x - entry.x, z = feet.z - entry.z;
        return -(x * x + z * z);
    }

    private static BlockPos cell(Vec3 feet) {
        // Keep footprints on opposite sides of a narrow corner separate.
        return BlockPos.containing(feet.x * 2, feet.y * 2, feet.z * 2);
    }

    private static void link(Map<BlockPos, List<BlockPos>> graph, BlockPos from, BlockPos to) {
        var adjacent = graph.computeIfAbsent(from, key -> new ArrayList<>(4));
        if (!adjacent.contains(to)) adjacent.add(to);
    }
}
