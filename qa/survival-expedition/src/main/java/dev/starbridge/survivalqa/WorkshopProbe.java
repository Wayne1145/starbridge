package dev.starbridge.survivalqa;

import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Role;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compare the displayed parts with the original controllers' own structure checks. */
final class WorkshopProbe {
    static void run(MinecraftServer server) {
        if (!server.getPlayerList().getPlayers().isEmpty()) throw new IllegalStateException("Use an empty diagnostic server");
        var result = new LinkedHashMap<String, Object>();
        int x = 25;
        for (var station : List.of(Station.PRECISION_ASSEMBLER, Station.ROLLING_MACHINE, Station.ARC_FURNACE)) {
            var position = new BlockPos(x, 155, 25);
            try {
                result.put(station.id().toString(), assemble(server.overworld(), station, position));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                result.put(station.id().toString(), Map.of("complete", false, "problem", failure.toString()));
            }
            x += 16;
        }
        Expedition.save("original-workshop-assembly-probe.json", result);
    }

    private static Map<String, Object> assemble(ServerLevel level, Station station, BlockPos position)
            throws ReflectiveOperationException {
        var plan = OriginalMachineLayout.read(station).orElseThrow();
        var anchor = plan.cells().stream().filter(cell -> cell.role() == Role.CONTROLLER).findFirst().orElseThrow();
        place(level, position, station.id(), 2);
        var block = level.getBlockEntity(position);
        if (block == null) throw new IllegalStateException("Original controller has no hosted tile");
        var original = block.getClass().getMethod("legacyTile").invoke(block);
        var nativeFront = original.getClass().getMethod("getFrontDirection").invoke(original);
        int frontX = nativeFront.getClass().getField("offsetX").getInt(nativeFront);
        int frontZ = nativeFront.getClass().getField("offsetZ").getInt(nativeFront);
        int blocks = 1;
        int clear = 0;
        for (var cell : plan.cells()) {
            if (cell == anchor) continue;
            int dx = cell.x() - anchor.x(), dz = cell.z() - anchor.z();
            var target = position.offset(dx * frontZ - dz * frontX, cell.layer() - anchor.layer(),
                    -dx * frontX - dz * frontZ);
            if (cell.role() == Role.CLEAR) {
                if (!level.isEmptyBlock(target)) throw new IllegalStateException("Required air was blocked at " + target);
                clear++;
            } else {
                place(level, target, cell.item(), cell.metadata());
                blocks++;
            }
        }
        boolean complete = Boolean.TRUE.equals(original.getClass().getMethod("attemptCompleteStructure").invoke(original));
        return Map.of("complete", complete, "cells", blocks, "clear", clear,
                "controller", List.of(position.getX(), position.getY(), position.getZ()));
    }

    private static void place(ServerLevel level, BlockPos position, ResourceLocation id, int metadata) {
        if (!level.isEmptyBlock(position)) throw new IllegalStateException("Occupied diagnostic cell " + position);
        var block = ForgeRegistries.BLOCKS.getDelegate(id).orElseThrow().get();
        var state = block.defaultBlockState();
        if (block.getStateDefinition().getProperty("meta") instanceof IntegerProperty meta) {
            state = state.setValue(meta, metadata);
        }
        if (!level.setBlockAndUpdate(position, state)) throw new IllegalStateException("Could not place " + id);
    }

    private WorkshopProbe() {}
}
