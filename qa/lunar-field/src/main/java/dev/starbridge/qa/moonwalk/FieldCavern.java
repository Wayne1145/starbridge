package dev.starbridge.qa.moonwalk;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
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

    static void move(Minecraft mc, int tick) {
        var p = mc.player;
        if (appleStarted > 0) {
            if (tick - appleStarted < 37) return;
            mc.options.keyUse.setDown(false);
            mc.gameMode.releaseUsingItem(p);
            appleStarted = 0;
        }
        ItemEntity key = null;
        LivingEntity foe = null;
        double best = Double.POSITIVE_INFINITY;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == p || p.distanceToSqr(entity) > 48 * 48 || !entity.isAlive() || !p.hasLineOfSight(entity)) continue;
            if (entity instanceof ItemEntity dropped) {
                var id = ForgeRegistries.ITEMS.getKey(dropped.getItem().getItem());
                if (id != null && id.toString().equals("galacticraftcore:item.key")) key = dropped;
            } else if (entity instanceof LivingEntity mob) {
                var id = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
                boolean boss = id != null && id.toString().equals("galacticraftcore:evolvedskeletonboss");
                if (!boss && !(mob instanceof Enemy)) continue;
                double priority = p.distanceToSqr(mob) - (boss ? 2500 : 0);
                if (priority < best) { best = priority; foe = mob; }
            }
        }
        if (key != null) {
            destination = null;
            p.lookAt(Anchor.EYES,key.position());
            mc.options.keyUp.setDown(p.distanceToSqr(key) > .2);
            return;
        }
        if (foe != null) {
            destination = null;
            if (p.getHealth() < 13 && !p.getInventory().getItem(7).isEmpty()
                    && !p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) {
                mc.options.keyUp.setDown(false);
                p.getInventory().selected = 7;
                mc.options.keyUse.setDown(true);
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                appleStarted = tick;
                return;
            }
            p.getInventory().selected = 1;
            p.lookAt(Anchor.EYES,foe.getBoundingBox().getCenter());
            double squared = foe.getBoundingBox().distanceToSqr(p.getEyePosition());
            mc.options.keyUp.setDown(squared > 2.25);
            mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
            if (squared < 8.9 && p.getAttackStrengthScale(.5f) > .94f) {
                mc.gameMode.attack(p,foe);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        BlockPos here = p.blockPosition();
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
        }
        if (destination == null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            return;
        }
        double dx = destination.getX() + .5 - p.getX(), dz = destination.getZ() + .5 - p.getZ();
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(7);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.onGround() && destination.getY() > here.getY());
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
                Vec3 feet = Vec3.atBottomCenterOf(cell);
                if (sight(mc, eye, feet.add(0, 1.15, 0)).getType() != HitResult.Type.MISS) continue;
                if (!mc.level.noCollision(player, player.getBoundingBox().move(feet.subtract(player.position())))) continue;
                var floor = sight(mc, feet.add(0, .15, 0), feet.add(0, -.65, 0));
                if (floor.getType() != HitResult.Type.BLOCK) continue;
                Vec3 from = feet.add(0, 1.15, 0);
                double depth = sight(mc, from, from.add(Vec3.atLowerCornerOf(direction.getNormal()).scale(6)))
                        .getLocation().distanceTo(from);
                double score = depth - 9 * visits.getOrDefault(cell, 0) - Math.abs(dy);
                if (score <= bestScore) continue;
                best = cell;
                bestScore = score;
            }
        }
        return best;
    }

    private static BlockHitResult sight(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
    }
}
