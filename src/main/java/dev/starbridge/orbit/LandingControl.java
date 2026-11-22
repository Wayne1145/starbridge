package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitWire.Clearance;
import dev.starbridge.voyage.Voyages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class LandingControl {
    private static final Map<UUID, LandingSurvey> jobs = new LinkedHashMap<>();
    private static final Map<UUID, Reservation> reservations = new HashMap<>();
    private static int cursor;

    private record Reservation(ServerLevel level, AABB area, int expires) {}

    private LandingControl() {}

    static void select(ServerPlayer pilot, ShipCoreBlockEntity core, String target, int x, int z) {
        int now = pilot.serverLevel().getServer().getTickCount();
        var old = jobs.get(core.id());
        if (old != null && old.x == x && old.z == z) {
            old.touched = now;
            if (old.result != Clearance.BUSY) {
                // Keep the previous map visible during background polling. A click on descent
                // always starts a fresh confirmation pass against the current blocks and entities.
                if (old.finished && old.result != Clearance.BORDER) old.reset(false);
                else if (old.finished) OrbitWire.send(pilot, old.packet());
                return;
            }
        }
        dismiss(core.id());
        var job =
                new LandingSurvey(
                        core.id(),
                        x,
                        z,
                        OrbitModule.cabinBounds(core).orElseThrow(),
                        Voyages.approachTarget(core, target),
                        now);
        int reserved =
                jobs.values().stream()
                        .filter(j -> j.result != Clearance.BUSY)
                        .mapToInt(j -> j.chunks.size())
                        .sum();
        if (job.chunks.size() + reserved > LandingSurvey.MAX_HELD_CHUNKS
                || jobs.values().stream().filter(j -> j.result != Clearance.BUSY).count() >= 8)
            job.end(Clearance.BUSY);
        jobs.put(job.id, job);
        OrbitWire.send(pilot, job.packet());
    }

    static void confirm(ServerPlayer pilot, UUID ship, int x, int z) {
        var job = jobs.get(ship);
        if (job == null
                || job.x != x
                || job.z != z
                || job.confirm
                || !job.lastClear) return;
        job.reset(true);
        job.touched = pilot.serverLevel().getServer().getTickCount();
        OrbitWire.send(pilot, job.packet());
    }

    static void dismiss(UUID id) {
        var job = jobs.remove(id);
        if (job != null) job.release();
    }

    static void clear() {
        jobs.values().forEach(LandingSurvey::release);
        jobs.clear();
        reservations.clear();
        cursor = 0;
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        reservations.values().removeIf(r -> r.expires <= now);
        if (jobs.isEmpty()) return;
        for (var j : new ArrayList<>(jobs.values()))
            if (now - j.touched > 300 || OrbitModule.seatedPilot(server, j.id) == null)
                dismiss(j.id);
        var pending = jobs.values().stream().filter(j -> !j.finished).toList();
        if (pending.isEmpty()) return;
        var job = pending.get(Math.floorMod(cursor++, pending.size()));
        try {
            int before = job.visited();
            job.work();
            // A long confirmed survey stays alive while it advances, including with several ships sharing the work.
            if (job.confirm && job.visited() != before) job.touched = now;
            if (!job.finished) return;
            if (job.result == Clearance.CLEAR
                    && reservations.values().stream()
                            .anyMatch(r -> r.level == job.level && r.area.intersects(job.box())))
                job.end(Clearance.OCCUPIED);
            var pilot = OrbitModule.seatedPilot(server, job.id);
            if (pilot == null) {
                dismiss(job.id);
                return;
            }
            boolean commit = job.confirm && job.result == Clearance.CLEAR;
            job.confirm = false;
            OrbitWire.send(pilot, job.packet());
            if (commit && OrbitModule.finishLanding(server, pilot, job)) {
                // The native body takes several ticks to appear in the destination physics world.
                reservations.put(job.id, new Reservation(job.level, job.box(), now + 200));
            }
        } catch (RuntimeException failure) {
            job.confirm = false;
            job.end(Clearance.UNAVAILABLE);
            var pilot = OrbitModule.seatedPilot(server, job.id);
            if (pilot != null) OrbitWire.send(pilot, job.packet());
            dev.starbridge.Starbridge.LOGGER.warn(
                    "Landing check for {} failed: {}", job.id, failure.toString());
            dismiss(job.id);
        }
    }
}
