package dev.starbridge.flightqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Look ahead from the eye before stepping over a rim. A pilot may skirt any unsafe drop. */
final class RimGround {
    private static final int[] TURNS = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
    private boolean ledge;
    private int previousTick = -100;
    private float previousHeading, safeYaw;

    float course(Minecraft game, float desired, int tick) {
        if (!game.player.onGround()) return previousTick < 0 ? desired : safeYaw;
        if (tick - previousTick < 3 && Math.abs(net.minecraft.util.Mth.wrapDegrees(desired - previousHeading)) < 15) return safeYaw;
        previousTick = tick; previousHeading = desired;
        for (int turn : TURNS) {
            float yaw = desired + turn;
            if (visibleStep(game, yaw, 1.5) && visibleStep(game, yaw, 2.45)) {
                ledge = turn != 0; safeYaw = yaw;
                return yaw;
            }
        }
        ledge = true; safeYaw = Float.NaN;
        return safeYaw;
    }

    boolean canSprint() { return !ledge; }

    private static boolean visibleStep(Minecraft game, float yaw, double distance) {
        var p = game.player;
        double a = Math.toRadians(yaw);
        double dx = -Math.sin(a) * distance, dz = Math.cos(a) * distance;
        for (double surface : new double[]{0, 1, -1}) {
            var end = new Vec3(p.getX()+dx,p.getY()+surface-.09,p.getZ()+dz);
            var hit = game.level.clip(new ClipContext(p.getEyePosition(),end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
            if (hit.getType()!=HitResult.Type.BLOCK || hit.getDirection()!=Direction.UP) continue;
            var point=hit.getLocation();
            if (Math.hypot(point.x-p.getX(),point.z-p.getZ())>=distance*.68 && point.y>=p.getY()-1.10) return true;
        }
        return false;
    }
}
