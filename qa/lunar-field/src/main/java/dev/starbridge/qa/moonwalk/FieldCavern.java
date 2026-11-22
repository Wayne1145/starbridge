package dev.starbridge.qa.moonwalk;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explore visible corridors and attack only a target that the walking player can see and reach. */
final class FieldCavern {
    private static final Map<BlockPos, Integer> visits = new HashMap<>();
    private static final Map<BlockPos, Integer> blockedUntil = new HashMap<>();
    private static BlockPos lastCell, destination;
    private static int choiceTick, appleStarted;
    private static int corridorHeight = Integer.MIN_VALUE;
    private static BlockPos bridgeGoal, bridgeAnchor;
    private static Direction bridgeDirection;
    private static int bridgeStarted;
    private static final int MAX_BRIDGE_LENGTH = 10;

    static void restoreVisits(com.google.gson.JsonArray saved, Collection<Vec3> priorTrail) {
        if (saved == null) {
            for (Vec3 point : priorTrail) visits.merge(BlockPos.containing(point), 1, Integer::sum);
            return;
        }
        for (var entry : saved) {
            var row = entry.getAsJsonArray();
            var cell = new BlockPos(row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt());
            visits.put(cell, row.get(3).getAsInt());
        }
    }

    static List<List<Integer>> saveVisits() {
        return visits.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var p = entry.getKey();
            return List.of(p.getX(), p.getY(), p.getZ(), entry.getValue());
        }).toList();
    }

    static boolean recoverHealth(Minecraft mc, int tick) {
        var player = mc.player;
        if (appleStarted > 0) {
            if (tick - appleStarted < 38) return true;
            mc.options.keyUse.setDown(false);
            mc.gameMode.releaseUsingItem(player);
            appleStarted = 0;
        }
        if (player.getHealth() >= 13) return false;
        if (player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            return true;
        }
        if (!player.getInventory().getItem(7).is(net.minecraft.world.item.Items.GOLDEN_APPLE)) return false;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        player.getInventory().selected = 7;
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        appleStarted = tick;
        return true;
    }

    static void move(Minecraft mc, int tick, Vec3 remembered) {
        var p = mc.player;
        BlockPos here = p.blockPosition();
        if (p.onGround() && masonry(mc, here.below())) corridorHeight = here.getY();
        mc.options.keyShift.setDown(false);
        if (bridgeGoal != null && bridge(mc, here, tick)) return;
        boolean carryingKey = FieldVessel.keys(p) > 0;
        ItemEntity key = null;
        LivingEntity foe = null;
        double best = Double.POSITIVE_INFINITY;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == p || p.distanceToSqr(entity) > 48 * 48 || !entity.isAlive() || !p.hasLineOfSight(entity)) continue;
            if (entity instanceof ItemEntity dropped) {
                var id = ForgeRegistries.ITEMS.getKey(dropped.getItem().getItem());
                if (id != null && id.toString().equals("galacticraftcore:item.key")
                        && clearFooting(mc, dropped.position())) key = dropped;
            } else if (entity instanceof LivingEntity mob) {
                var id = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
                boolean originalHostile = id != null && id.getNamespace().equals("galacticraftcore")
                        && id.getPath().startsWith("evolved");
                boolean boss = originalHostile && id.getPath().equals("evolvedskeletonboss");
                if (!originalHostile && !(mob instanceof Enemy)) continue;
                if (p.distanceToSqr(mob) > (carryingKey ? 12.25 : boss ? 48 * 48 : 8 * 8)) continue;
                if (p.distanceToSqr(mob) > 7.2 && !clearFooting(mc, mob.position())) continue;
                double priority = p.distanceToSqr(mob) - (boss ? 2500 : 0);
                if (priority < best) { best = priority; foe = mob; }
            }
        }
        if (key != null) {
            destination = null;
            p.lookAt(Anchor.EYES,key.position());
            mc.options.keyUp.setDown(p.distanceToSqr(key) > .2 && firmGroundAhead(mc));
            mc.options.keyJump.setDown(false);
            return;
        }
        if (foe != null) {
            destination = null;
            p.getInventory().selected = 1;
            p.lookAt(Anchor.EYES,foe.getBoundingBox().getCenter());
            double squared = foe.getBoundingBox().distanceToSqr(p.getEyePosition());
            var id = ForgeRegistries.ENTITY_TYPES.getKey(foe.getType());
            boolean explosive = id != null && id.getPath().endsWith("creeper");
            if (explosive && squared < 14 && p.getAttackStrengthScale(.5f) < .94f) {
                p.setYRot(p.getYRot() + 180);
                mc.options.keyUp.setDown(firmGroundAhead(mc));
                mc.options.keyJump.setDown(false);
                return;
            }
            mc.options.keyUp.setDown(squared > (explosive ? 7.2 : 2.25) && firmGroundAhead(mc));
            mc.options.keyJump.setDown(false);
            if (squared < 8.9 && p.getAttackStrengthScale(.5f) > .94f) {
                mc.gameMode.attack(p,foe);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (remembered != null && followRemembered(mc, remembered)) {
            destination = null;
            return;
        }
        if (!here.equals(lastCell)) {
            visits.merge(here, 1, Integer::sum);
            lastCell = here;
        }
        if (destination != null && tick - choiceTick > 32) {
            blockedUntil.put(destination, tick + 120);
            destination = null;
        }
        if (destination == null || here.equals(destination)) {
            destination = nextCell(mc, here, tick);
            choiceTick = tick;
            boolean backtracking = destination == null || visits.getOrDefault(destination, 0) > 0;
            if (backtracking && selectBridge(mc, here, tick) && bridge(mc, here, tick)) return;
        }
        if (destination == null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            return;
        }
        double dx = destination.getX() + .5 - p.getX(), dz = destination.getZ() + .5 - p.getZ();
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(7);
        boolean stepUp = destination.getY() > here.getY();
        // A chest can raise the player's feet without changing their block position.
        boolean stepDownToFloor = p.getY() - destination.getY() > .5 && bridgeSupport(mc, destination);
        mc.options.keyUp.setDown(stepUp || stepDownToFloor || firmGroundAhead(mc));
        mc.options.keyJump.setDown(p.onGround() && stepUp);
    }

    private static BlockPos nextCell(Minecraft mc, BlockPos here, int tick) {
        var player = mc.player;
        var eye = player.getEyePosition();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            var adjacent = here.relative(direction);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos cell = adjacent.offset(0, dy, 0);
                if (!mc.level.hasChunkAt(cell) || blockedUntil.getOrDefault(cell, 0) > tick) continue;
                // A raised brick in the passage can be one block above the original walking floor.
                if (here.getY() == corridorHeight && cell.getY() < corridorHeight && !bridgeSupport(mc, cell)) continue;
                Vec3 feet = Vec3.atBottomCenterOf(cell);
                if (sight(mc, eye, feet.add(0, 1.15, 0)).getType() != HitResult.Type.MISS) continue;
                if (!mc.level.noCollision(player, player.getBoundingBox().move(feet.subtract(player.position())))) continue;
                var floor = sight(mc, feet.add(0, .15, 0), feet.add(0, -.65, 0));
                if (floor.getType() != HitResult.Type.BLOCK) continue;
                Vec3 from = feet.add(0, 1.15, 0);
                double depth = sight(mc, from, from.add(Vec3.atLowerCornerOf(direction.getNormal()).scale(6)))
                        .getLocation().distanceTo(from);
                double score = depth + (masonry(mc, floor.getBlockPos()) ? 12 : 0)
                        - 9 * visits.getOrDefault(cell, 0) - Math.abs(dy);
                if (score <= bestScore) continue;
                best = cell;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean selectBridge(Minecraft mc, BlockPos here, int tick) {
        var player = mc.player;
        if (!player.onGround() || here.getY() != corridorHeight
                || !masonry(mc, here.below()) || !player.getInventory().getItem(6).is(Items.COBBLESTONE)) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= MAX_BRIDGE_LENGTH; distance++) {
                BlockPos step = here.relative(direction, distance);
                Vec3 feet = Vec3.atBottomCenterOf(step);
                if (!mc.level.hasChunkAt(step) || !mc.level.noCollision(player,
                        player.getBoundingBox().move(feet.subtract(player.position())))) break;
                if (masonry(mc, step.below())) {
                    if (distance == 1 || visits.getOrDefault(step, 0) > 0
                            || blockedUntil.getOrDefault(step, 0) > tick) break;
                    if (sight(mc, player.getEyePosition(), feet.add(0, .1, 0)).getType() != HitResult.Type.MISS) break;
                    bridgeGoal = step.immutable();
                    bridgeAnchor = here.immutable();
                    bridgeDirection = direction;
                    bridgeStarted = tick;
                    return true;
                }
                if (!mc.level.getBlockState(step.below()).isAir()) break;
            }
        }
        return false;
    }

    private static boolean bridge(Minecraft mc, BlockPos here, int tick) {
        var player = mc.player;
        if (here.equals(bridgeGoal)) {
            bridgeGoal = null;
            destination = null;
            return false;
        }
        BlockPos next = bridgeAnchor.relative(bridgeDirection);
        if (here.equals(next) && bridgeSupport(mc, next)) {
            bridgeAnchor = next;
            next = bridgeAnchor.relative(bridgeDirection);
        }
        // Sneaking allows the player's center to pass the side before there is a new block below it.
        if (tick - bridgeStarted > 620 || (!here.equals(bridgeAnchor) && !here.equals(next))
                || !bridgeSupport(mc, bridgeAnchor)) {
            blockedUntil.put(bridgeGoal, tick + 120);
            bridgeGoal = null;
            destination = null;
            return false;
        }
        mc.options.keyShift.setDown(true);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        var facing = Vec3.atLowerCornerOf(bridgeDirection.getNormal());
        Vec3 feet = Vec3.atBottomCenterOf(bridgeAnchor);
        if (bridgeSupport(mc, next)) {
            player.lookAt(Anchor.EYES, Vec3.atBottomCenterOf(next).add(0, player.getEyeHeight(), 0));
            mc.options.keyUp.setDown(true);
            return true;
        }
        BlockPos support = bridgeAnchor.below();
        var supportFace = Vec3.atCenterOf(support).add(facing.scale(.49));
        player.lookAt(Anchor.EYES, supportFace);
        var hit = sight(mc, player.getEyePosition(), supportFace);
        boolean atFace = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                && hit.getDirection() == bridgeDirection;
        if (atFace) {
            mc.options.keyUp.setDown(false);
            if (tick % 6 == 0 && mc.level.getBlockState(next.below()).isAir()
                    && player.getInventory().getItem(6).is(Items.COBBLESTONE)) {
                player.getInventory().selected = 6;
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            player.lookAt(Anchor.EYES, feet.add(facing.scale(2)).add(0, player.getEyeHeight(), 0));
            mc.options.keyUp.setDown(player.onGround());
        }
        return true;
    }

    private static boolean bridgeSupport(Minecraft mc, BlockPos feet) {
        BlockPos floor = feet.below();
        return masonry(mc, floor) || mc.level.getBlockState(floor).is(Blocks.COBBLESTONE);
    }

    static boolean canFollowRemembered(Minecraft mc, Vec3 target) {
        var p = mc.player;
        if (p.position().distanceToSqr(target) > 9 || Math.abs(target.y - p.getY()) > 1.2
                || sight(mc, p.getEyePosition(), target.add(0, 1.15, 0)).getType() != HitResult.Type.MISS) return false;
        boolean step = Math.abs(target.y - p.getY()) > .5 && bridgeSupport(mc, BlockPos.containing(target));
        return step || clearFooting(mc, target);
    }

    private static boolean followRemembered(Minecraft mc, Vec3 target) {
        if (!canFollowRemembered(mc, target)) return false;
        var p = mc.player;
        boolean rising = target.y > p.getY() + .5;
        p.setYRot((float) Math.toDegrees(Math.atan2(p.getX() - target.x, target.z - p.getZ())));
        p.setXRot(7);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.onGround() && rising);
        return true;
    }

    private static boolean clearFooting(Minecraft mc, Vec3 target) {
        var feet = mc.player.position();
        var horizontal = new Vec3(target.x - feet.x, 0, target.z - feet.z);
        if (Math.abs(target.y - feet.y) > 1.2) return false;
        int checks = Math.max(1, (int) Math.ceil(horizontal.length() * 2));
        for (int step = 1; step <= checks; step++) {
            if (!supportedAt(mc, feet.add(horizontal.scale(step / (double) checks)))) return false;
        }
        return true;
    }

    private static boolean firmGroundAhead(Minecraft mc) {
        var player = mc.player;
        var direction = player.getForward();
        var flat = new Vec3(direction.x, 0, direction.z).normalize();
        return supportedAt(mc, player.position().add(flat.scale(.6)));
    }

    private static boolean supportedAt(Minecraft mc, Vec3 feet) {
        return sight(mc, feet.add(0, .35, 0), feet.add(0, -.70, 0)).getType() == HitResult.Type.BLOCK;
    }

    private static boolean masonry(Minecraft mc, BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && key.toString().equals("galacticraftcore:tile.moonblock")
                && state.getValues().entrySet().stream().anyMatch(entry ->
                        entry.getKey().getName().equals("meta") && entry.getValue().equals(14));
    }

    private static BlockHitResult sight(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
    }
}
