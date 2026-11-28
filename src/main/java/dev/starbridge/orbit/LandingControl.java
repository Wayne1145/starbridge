package dev.starbridge.orbit;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitWire.Clearance;
import dev.starbridge.voyage.Voyages;
import dev.starbridge.voyage.VoyageNetwork;

import net.minecraft.server.MinecraftServer;
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
    private static final int ASSEMBLY_CHUNKS_PER_TICK = 4;
    private static final int MAX_ASSEMBLY_PREPARATIONS = 2;
    private static final int SURVEY_STALL_TICKS = 300;
    static final String SURVEY_TIMEOUT = "orbit.starbridge.landing_survey_timeout";

    private record Reservation(LandingSurvey survey, AABB area, int expires) {}

    private LandingControl() {}

    static void select(ServerPlayer pilot, ShipCoreBlockEntity core, String target, int x, int z) {
        if (reservations.containsKey(core.id())) return;
        int now = pilot.serverLevel().getServer().getTickCount();
        var old = jobs.get(core.id());
        if (old != null && old.x == x && old.z == z) {
            old.touched = now;
            if (old.result != Clearance.BUSY) {
                // Keep the previous map visible during background polling. A click on descent
                // always starts a fresh confirmation pass against the current blocks and entities.
                if (old.finished) {
                    if (old.result == Clearance.BORDER || old.result == Clearance.UNAVAILABLE) {
                        OrbitWire.send(pilot, old.packet());
                    } else old.reset(false, now);
                }
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
        var active = jobs.values().stream()
                .filter(j -> !j.chunks.isEmpty() && j.result != Clearance.BUSY && j.result != Clearance.UNAVAILABLE)
                .toList();
        int reserved = active.stream().mapToInt(j -> j.chunks.size()).sum()
                + reservations.values().stream().mapToInt(r -> r.survey.chunks.size()).sum();
        if (job.chunks.size() + reserved > LandingSurvey.MAX_HELD_CHUNKS
                || active.size() + reservations.size() >= 8)
            job.end(Clearance.BUSY);
        jobs.put(job.id, job);
        OrbitWire.send(pilot, job.packet());
    }

    static boolean confirm(ServerPlayer pilot, UUID ship, int x, int z) {
        var job = jobs.get(ship);
        if (job == null
                || job.x != x
                || job.z != z
                || job.confirm
                || !job.lastClear) return false;
        int now = pilot.serverLevel().getServer().getTickCount();
        job.reset(true, now);
        int upcoming = (int) jobs.values().stream().filter(survey -> survey.assemblyStorage != null).count();
        long retained = reservations.values().stream().filter(r -> r.survey.assemblyStorage != null).count();
        if (upcoming + retained < MAX_ASSEMBLY_PREPARATIONS) {
            var source = pilot.serverLevel();
            int ordinal = (int) jobs.values().stream()
                    .filter(survey -> survey.assemblyStorage != null && survey.assemblyStorage.isIn(source)).count();
            try {
                job.assemblyStorage = LandingStorage.start(source, ship, job.hull, ordinal);
            } catch (RuntimeException | LinkageError failure) {
                dev.starbridge.Starbridge.LOGGER.warn("Cannot prepare ship storage for {}", ship, failure);
            }
        }
        job.touched = now;
        OrbitWire.send(pilot, job.packet());
        return true;
    }

    static void dismiss(UUID id) {
        var job = jobs.remove(id);
        var reservation = reservations.get(id);
        if (job != null && (reservation == null || reservation.survey != job)) job.release();
    }

    static void clear() {
        jobs.values().forEach(LandingSurvey::release);
        jobs.clear();
        reservations.values().forEach(r -> r.survey.release());
        reservations.clear();
        cursor = 0;
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        LandingStorage.recover(server);
        reservations.values().removeIf(r -> {
            if (r.expires > now) return false;
            r.survey.release();
            return true;
        });
        if (jobs.isEmpty()) return;
        for (var j : new ArrayList<>(jobs.values()))
            if (now - j.touched > 300 || OrbitModule.seatedPilot(server, j.id) == null)
                dismiss(j.id);
        var preparing = jobs.values().stream().filter(job -> job.assemblyStorage != null).toList();
        if (!preparing.isEmpty()) {
            int slice = ASSEMBLY_CHUNKS_PER_TICK / preparing.size();
            for (var job : preparing) {
                try {
                    job.assemblyStorage.tick(slice);
                } catch (RuntimeException failure) {
                    dev.starbridge.Starbridge.LOGGER.warn("Cannot preload ship storage for {}", job.id, failure);
                    job.releaseStorage();
                }
            }
        }
        var pending = jobs.values().stream().filter(j -> !j.finished).toList();
        if (pending.isEmpty()) return;
        var job = pending.get(Math.floorMod(cursor++, pending.size()));
        try {
            int before = job.visited();
            job.work();
            if (job.visited() != before) {
                job.lastProgress = now;
                if (job.confirm) job.touched = now;
            }
            if (!job.finished) {
                if (now - job.lastProgress < SURVEY_STALL_TICKS) return;
                Starbridge.LOGGER.warn("Landing survey stalled for ship {} at {} ({}, {}), confirmed: {}, work completed: {}",
                        job.id, job.level.dimension().location(), job.cx, job.cz, job.confirm, job.visited());
                job.end(Clearance.UNAVAILABLE);
                job.release();
                job.touched = now;
                OrbitModule.landingNotice(job.id, SURVEY_TIMEOUT);
            }
            if (job.result == Clearance.CLEAR
                    && reservations.values().stream()
                            .anyMatch(r -> r.survey.level == job.level && r.area.intersects(job.box())))
                job.end(Clearance.OCCUPIED);
            var pilot = OrbitModule.seatedPilot(server, job.id);
            if (pilot == null) {
                dismiss(job.id);
                return;
            }
            boolean commit = job.confirm && job.result == Clearance.CLEAR;
            job.confirm = false;
            OrbitWire.send(pilot, job.packet());
            if (commit) land(server, pilot, job, now);
            else if (job.finished) job.releaseStorage();
        } catch (RuntimeException failure) {
            job.confirm = false;
            job.end(Clearance.UNAVAILABLE);
            var pilot = OrbitModule.seatedPilot(server, job.id);
            if (pilot != null) {
                OrbitModule.landingNotice(job.id, VoyageNetwork.feedbackKey(failure));
                OrbitWire.send(pilot, job.packet());
            }
            dismiss(job.id);
        }
    }

    private static void land(MinecraftServer server, ServerPlayer pilot, LandingSurvey job, int now) {
        // The normal completion removes the survey from the helm. Keep its loaded terrain
        // through the berth reservation so the native transfer can check the same surface.
        reservations.put(job.id, new Reservation(job, job.box(), now + 200));
        boolean accepted = false;
        try {
            if (job.assemblyStorage != null) job.assemblyStorage.reportReadiness();
            accepted = OrbitModule.finishLanding(server, pilot, job);
        } finally {
            if (!accepted) {
                job.releaseStorage();
                reservations.remove(job.id);
                if (jobs.get(job.id) != job) job.release();
            }
            // On success the berth lease keeps the pasted VS chunks loaded while VS adopts
            // the ship and the cross-world transfer gets its own storage tickets.
        }
    }
}
