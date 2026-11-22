package dev.starbridge.qa.fuel;

import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Cell;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Role;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Give an ordinary survival pilot the loose components, then observe actual block placement and
 * original assembly.
 */
final class WorkshopAssembly {
    static final Path MARK = Path.of("assemble-workshop.fixture");
    private static final int FLOOR = 147;
    private static final List<Object> actions = new ArrayList<>();
    private static final Set<Cell> placed = new HashSet<>();
    private static final Map<String, Object> formed = new LinkedHashMap<>();
    private static boolean prepared, supplied, reactor;
    private static int sequence = 30, issuedAt = -1;
    private static OriginalMachineLayout plan;
    private static Cell anchor, placing;
    private static BlockPos controller, target;

    static boolean tick(ServerLevel level) throws Exception {
        if (!prepared) {
            level.setChunkForced(1, 2, true);
            level.setChunkForced(2, 2, true);
            for (int x = 23; x <= 43; x++)
                for (int z = 27; z <= 40; z++)
                    level.setBlockAndUpdate(
                            new BlockPos(x, FLOOR, z), Blocks.SMOOTH_STONE.defaultBlockState());
            FactoryTrial.write(
                    Path.of("factory-start.json"), Map.of("mode", "survival block placement"));
            select(false);
            prepared = true;
        }
        var pilots = level.getServer().getPlayerList().getPlayers();
        if (pilots.isEmpty()) return false;
        var pilot = pilots.get(0);
        int now = level.getServer().getTickCount();
        if (!supplied) {
            FactoryTrial.require(
                    !pilot.hasPermissions(2)
                            && !pilot.isCreative()
                            && !pilot.isSpectator()
                            && pilot.getInventory().isEmpty(),
                    "Expected a fresh non-operator survival pilot");
            for (boolean type : List.of(false, true)) {
                for (Cell cell : OriginalMachineLayout.read(type).orElseThrow().cells()) {
                    var part = cell.stack();
                    FactoryTrial.require(
                            pilot.getInventory().add(part), "No inventory space for original part");
                }
            }
            supplied = true;
        }
        if (issuedAt >= 0) {
            FactoryTrial.require(
                    now - issuedAt < 1000,
                    "Timed out at original component " + sequence + " " + target.toShortString());
            if (!Files.exists(Path.of("../LunarTester/factory-done-" + sequence + ".json")))
                return false;
            if (placing != null) {
                FactoryTrial.require(
                        level.getBlockState(target).getBlock().asItem()
                                == placing.stack().getItem(),
                        "The expected part was not placed at " + target.toShortString());
                placed.add(placing);
                actions.add(
                        Map.of(
                                "item",
                                placing.item().toString(),
                                "variant",
                                placing.metadata(),
                                "role",
                                placing.role(),
                                "position",
                                target.toShortString(),
                                "sequence",
                                sequence));
            } else {
                var tile = NativeMachine.tile(level, controller);
                var complete = tile.getClass().getMethod("isComplete").invoke(tile);
                FactoryTrial.require(
                        Boolean.TRUE.equals(complete),
                        "The original controller did not form after the player used it");
                formed.put(
                        reactor ? "reactor" : "electrolyser",
                        Map.of(
                                "nativeClass",
                                tile.getClass().getName(),
                                "complete",
                                true,
                                "controller",
                                controller.toShortString(),
                                "placed",
                                placed.size()));
                if (reactor) {
                    FactoryTrial.require(
                            pilot.getInventory().isEmpty(),
                            "Expected all nineteen supplied parts to be used");
                    FactoryTrial.write(
                            Path.of("factory-result.json"),
                            Map.of(
                                    "pass",
                                    true,
                                    "ordinaryPilot",
                                    pilot.getUUID(),
                                    "formed",
                                    formed,
                                    "actions",
                                    actions));
                    return true;
                }
                select(true);
            }
            sequence++;
        }
        placing = next();
        if (placing == null) {
            target = controller;
            moveToFront(level, pilot, target, false);
            FactoryTrial.write(
                    Path.of("factory-order.json"),
                    new FactoryTrial.Order(sequence, "form", target.asLong(), "", ""));
        } else {
            target = position(level, placing);
            FactoryTrial.require(
                    level.isEmptyBlock(target),
                    "Placement space occupied at " + target.toShortString());
            FactoryTrial.require(
                    !level.isEmptyBlock(target.below()),
                    "Missing support beneath " + target.toShortString());
            moveToFront(level, pilot, target, true);
            FactoryTrial.write(
                    Path.of("factory-order.json"),
                    new FactoryTrial.Order(
                            sequence,
                            "place",
                            target.asLong(),
                            placing.item().toString(),
                            Integer.toString(placing.metadata())));
        }
        issuedAt = now;
        return false;
    }

    private static void select(boolean chemical) {
        reactor = chemical;
        plan = OriginalMachineLayout.read(chemical).orElseThrow();
        anchor =
                plan.cells().stream()
                        .filter(c -> c.role() == Role.CONTROLLER)
                        .findFirst()
                        .orElseThrow();
        controller = new BlockPos(chemical ? 37 : 28, FLOOR + 1 + anchor.layer(), 33);
        placed.clear();
    }

    private static Cell next() {
        // The reactor's motor lies directly under its controller regardless of orientation. Place
        // it first.
        return plan.cells().stream()
                .filter(c -> !placed.contains(c))
                .min(
                        Comparator.comparingInt(
                                (Cell c) ->
                                        c.x() == anchor.x() && c.z() == anchor.z()
                                                ? c.layer()
                                                : 10 + c.layer()))
                .orElse(null);
    }

    private static BlockPos position(ServerLevel level, Cell c) throws Exception {
        int x = c.x() - anchor.x(), y = c.layer() - anchor.layer(), z = c.z() - anchor.z();
        if (x == 0 && z == 0) return controller.offset(0, y, 0);
        Object tile = NativeMachine.tile(level, controller);
        Object front = tile.getClass().getMethod("getFrontDirection").invoke(tile);
        int dx = front.getClass().getField("offsetX").getInt(front),
                dz = front.getClass().getField("offsetZ").getInt(front);
        FactoryTrial.require(
                Math.abs(dx) + Math.abs(dz) == 1, "Controller needs a horizontal face");
        return controller.offset(x * dz - z * dx, y, -x * dx - z * dz);
    }

    private static void moveToFront(
            ServerLevel level, ServerPlayer pilot, BlockPos part, boolean aboveSupport) {
        var clicked = aboveSupport ? part.below() : part;
        var aim =
                net.minecraft.world.phys.Vec3.atCenterOf(clicked)
                        .add(0, aboveSupport ? .498 : 0, 0);
        for (int radius : List.of(2, 3))
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var column = part.relative(direction, radius);
                if (Math.abs(column.getX() - controller.getX()) < 2
                        && Math.abs(column.getZ() - controller.getZ()) < 2) continue;
                for (int y : List.of(FLOOR + 2, FLOOR + 3, FLOOR + 1)) {
                    var feet = new BlockPos(column.getX(), y, column.getZ());
                    if (!level.isEmptyBlock(feet) || !level.isEmptyBlock(feet.above())) continue;
                    var spot = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
                    var eyes = spot.add(0, 1.62, 0);
                    if (eyes.distanceTo(aim) > 4.35) continue;
                    var hit =
                            level.clip(
                                    new net.minecraft.world.level.ClipContext(
                                            eyes,
                                            aim,
                                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                                            pilot));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                            || !clicked.equals(hit.getBlockPos())
                            || aboveSupport
                                    && hit.getDirection() != net.minecraft.core.Direction.UP)
                        continue;
                    if (level.isEmptyBlock(feet.below()))
                        level.setBlockAndUpdate(
                                feet.below(), Blocks.SMOOTH_STONE.defaultBlockState());
                    pilot.teleportTo(level, spot.x, spot.y, spot.z, 0, 0);
                    pilot.lookAt(
                            net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, aim);
                    return;
                }
            }
        throw new IllegalStateException("Cannot reach visible face at " + part.toShortString());
    }

    private WorkshopAssembly() {}
}
