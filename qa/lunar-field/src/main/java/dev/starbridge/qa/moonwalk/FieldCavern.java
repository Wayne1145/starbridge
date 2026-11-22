package dev.starbridge.qa.moonwalk;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.HashMap;
import java.util.Map;

/** Explore visible corridors and attack only a target that the walking player can see and reach. */
final class FieldCavern {
    private static final Map<Long, Integer> passes = new HashMap<>();
    private static long lastCell = Long.MIN_VALUE;
    private static float heading;
    private static int changed, appleStarted;

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
            p.lookAt(Anchor.EYES,key.position());
            mc.options.keyUp.setDown(p.distanceToSqr(key) > .2);
            return;
        }
        if (foe != null) {
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
        long here = cell(p.getX(), p.getZ());
        if (here != lastCell) { passes.merge(here, 1, Integer::sum); lastCell = here; }
        if (tick - changed >= 7 || p.horizontalCollision) {
            changed = tick;
            double score = -Double.MAX_VALUE;
            for (int i = 0; i < 16; i++) {
                float yaw = i * 22.5f;
                double angle = Math.toRadians(yaw), dx = -Math.sin(angle), dz = Math.cos(angle);
                Vec3 eye = p.getEyePosition();
                var wall = sight(mc,eye,eye.add(dx * 8,0,dz * 8));
                double open = wall.getLocation().distanceTo(eye);
                if (open < 1.3) continue;
                // Look down at the next foothold from the current eye. A wall hides anything behind it.
                var foot = sight(mc,eye,new Vec3(p.getX()+dx*1.4,p.getY()-2.6,p.getZ()+dz*1.4));
                if (foot.getType() != HitResult.Type.BLOCK || foot.getLocation().y < p.getY()-2.1) continue;
                long near = cell(p.getX()+dx*2.4,p.getZ()+dz*2.4);
                long far = cell(p.getX()+dx*Math.min(6,open-.6), p.getZ()+dz*Math.min(6,open-.6));
                double value = Math.min(open,6) - 3*passes.getOrDefault(near,0) - 4*passes.getOrDefault(far,0)
                        + 1.4*Math.cos(Math.toRadians(yaw-heading));
                if (value > score) { score = value; heading = yaw; }
            }
        }
        p.setYRot(heading); p.setXRot(7);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
    }

    private static long cell(double x, double z) {
        return ((long)Math.floor(x/2) << 32) ^ ((long)Math.floor(z/2) & 0xffffffffL);
    }

    private static BlockHitResult sight(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
    }
}
