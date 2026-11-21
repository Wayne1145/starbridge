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
        if (!ENABLED || core == null) return;
        var client = Path.of("../OrbitCaptain");
        if (Files.isRegularFile(client.resolve("rejected-site.json"))
                && !observations.containsKey("blockedCourseIntact")) {
            boolean intact = fixedAndUnpaid(pilot, moon, core);
            observations.put("blockedCourseIntact", intact);
            if (!intact) throw new IllegalStateException("Border denial changed the cabin or fare");
            var border = moon.getWorldBorder();
            border.setCenter(savedX, savedZ);
            border.setSize(savedSize);
            MissionSignals.write(
                    client.resolve("correct-site.json"), Map.of("courseRetained", true));
        }
        if (Files.isRegularFile(client.resolve("add-obstruction.json"))
                && !observations.containsKey("obstructedFloor")) {
            var selected =
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(client.resolve("add-obstruction.json")))
                            .getAsJsonObject();
            int x = selected.get("x").getAsInt(),
                    y = selected.get("floor").getAsInt(),
                    z = selected.get("z").getAsInt();
            var stand =
                    new net.minecraft.world.entity.decoration.ArmorStand(moon, x + .5, y, z + .5);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            if (!moon.addFreshEntity(stand))
                throw new IllegalStateException("Fixture could not occupy the selected berth");
            observations.put("obstructedFloor", y);
            MissionSignals.write(
                    client.resolve("obstruction-added.json"), Map.of("x", x, "y", y, "z", z));
        }
        if (Files.isRegularFile(client.resolve("occupied-site.json"))
                && !observations.containsKey("goAroundRange")) {
            if (!fixedAndUnpaid(pilot, moon, core))
                throw new IllegalStateException("Obstruction changed the hull or charged the fare");
            var status = OrbitModule.status(core);
            double distance = ((Number) status.get("distance")).doubleValue();
            observations.putIfAbsent("rangeAfterDenial", distance);
            if (distance >= dev.starbridge.orbit.OrbitalFlight.ENTRY + 55) {
                observations.put("goAroundRange", distance);
                observations.put("occupiedCourseIntact", true);
                MissionSignals.write(
                        client.resolve("climb-witness.json"),
                        Map.of("distance", distance, "fixedCabin", true));
            }
        }
    }

    private static boolean fixedAndUnpaid(
            ServerPlayer pilot, ServerLevel moon, ShipCoreBlockEntity core) {
        return OrbitModule.active(core)
                && pilot.serverLevel() != moon
                && !HullTransit.inProgress(moon.getServer(), core.id())
                && new VsShipBackend().inspect(pilot.serverLevel(), core.getBlockPos()).isEmpty()
                && !core.saveWithoutMetadata().hasUUID("PaidVoyage");
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
            // Independent oracle: walk down the actual destination column, starting at build
            // height. The original-world motion heightmap omits many native lunar blocks.
            int x=net.minecraft.util.Mth.floor(bottom.x), z=net.minecraft.util.Mth.floor(bottom.z);
            double terrain=level.getMinBuildHeight();
            for(int y=level.getMaxBuildHeight()-1;y>=level.getMinBuildHeight();y--) {
                var pos=new BlockPos(x,y,z);
                var state=level.getBlockState(pos);
                if(state.isAir()) continue;
                var shape=state.getCollisionShape(level,pos);
                if(!shape.isEmpty()) { terrain=y+shape.max(net.minecraft.core.Direction.Axis.Y); break; }
            }
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
        observations.put("groundContactObserved", Math.abs(min) <= .05);
        observations.put("validCorrectionLanded", true);
        MissionSignals.write(Path.of("landing-result.json"), observations);
        if (Math.abs(min) > .05) throw new IllegalStateException("Ship did not touch the native terrain: " + min);
    }
}
