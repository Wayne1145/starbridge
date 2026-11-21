package dev.starbridge.flightqa;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.transit.HullTransit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.nio.file.*;
import java.util.*;

final class LandingCheck {
    static final boolean ENABLED = Files.isRegularFile(Path.of("starbridge-approach.fixture"));
    private static final Map<String, Object> observations = new LinkedHashMap<>();
    private static double savedSize, savedX, savedZ;

    static void prepare(ServerLevel moon) {
        if (!ENABLED) return;
        var border = moon.getWorldBorder();
        savedSize = border.getSize();
        savedX = border.getCenterX();
        savedZ = border.getCenterZ();
        border.setCenter(0, 0);
        border.setSize(800);
    }

    static void permitRetry(ServerPlayer pilot, ServerLevel moon, ShipCoreBlockEntity core)
            throws Exception {
        if (!ENABLED
                || observations.containsKey("blockedCourseIntact")
                || !Files.isRegularFile(Path.of("../OrbitCaptain/rejected-site.json"))) return;
        boolean intact =
                core != null
                        && OrbitModule.active(core)
                        && pilot.serverLevel() != moon
                        && !HullTransit.inProgress(moon.getServer(), core.id())
                        && !core.saveWithoutMetadata().hasUUID("PaidVoyage");
        observations.put("blockedCourseIntact", intact);
        observations.put("energyAfterDenial", core == null ? -1 : core.energy());
        if (!intact)
            throw new IllegalStateException("Rejected approach changed the hull or created a fare");
        var border = moon.getWorldBorder();
        border.setCenter(savedX, savedZ);
        border.setSize(savedSize);
        MissionSignals.write(
                Path.of("../OrbitCaptain/correct-site.json"), Map.of("courseRetained", true));
    }

    static void arrived(ServerPlayer pilot, ShipCoreBlockEntity core, long shipId)
            throws Exception {
        if (!ENABLED) return;
        ServerLevel level = pilot.serverLevel();
        var bounds = new VsShipBackend().storageBoundsById(level, shipId).orElseThrow();
        double min = Double.POSITIVE_INFINITY;
        int sampled = 0;
        for (var block :
                BlockPos.betweenClosed(
                        bounds.minX(),
                        bounds.minY(),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ())) {
            if (level.getBlockState(block).isAir()) continue;
            Vec3 bottom =
                    HullTransit.toWorld(
                                    level,
                                    shipId,
                                    new Vec3(block.getX() + .5, block.getY(), block.getZ() + .5))
                            .orElseThrow();
            int terrain =
                    level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING,
                            net.minecraft.util.Mth.floor(bottom.x),
                            net.minecraft.util.Mth.floor(bottom.z));
            min = Math.min(min, bottom.y - terrain);
            sampled++;
        }
        if (sampled == 0 || !Double.isFinite(min))
            throw new IllegalStateException("No landed hull points");
        observations.put("world", level.dimension().location().toString());
        observations.put("pilotWorldFeet", pilot.position().toString());
        observations.put("shipId", shipId);
        observations.put("sampledOccupiedBlocks", sampled);
        observations.put("minimumHullBottomAboveNaturalTerrainBlocks", min);
        observations.put("groundContactObserved", min <= .05);
        observations.put("validCorrectionLanded", true);
        MissionSignals.write(Path.of("landing-result.json"), observations);
    }
}
