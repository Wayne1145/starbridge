package dev.starbridge.survivalqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Place a filled bucket using the same aim and use action as a survival player. */
final class BucketPlacement {
    private static Vec3 previousAim;
    private static long nextUse;
    private BucketPlacement() {}

    static boolean pour(Minecraft game, String fluid, BlockPos target) {
        boolean lava = fluid.equals("lava");
        if (!lava && !fluid.equals("water")) throw new IllegalArgumentException("Unknown bucket fluid");
        var state = game.level.getBlockState(target);
        var liquid = state.getFluidState();
        if (liquid.isSource() && liquid.is(lava ? FluidTags.LAVA : FluidTags.WATER)) return true;
        if (game.screen != null) { game.screen.onClose(); return false; }
        if (!state.canBeReplaced() || !liquid.isEmpty()) return false;
        if (lava) {
            // Fill only a player-built, fully enclosed casting pit distant from the wooden camp.
            if (target.distManhattan(new BlockPos(-88, 64, -18)) < 15) throw new IllegalStateException("Cast outside the wooden shelter");
            for (var side : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN}) {
                if (!game.level.getBlockState(target.relative(side)).is(Blocks.COBBLESTONE)) return false;
            }
        }
        if (!ExpeditionClient.select(game, lava ? "minecraft:lava_bucket" : "minecraft:water_bucket")) return false;
        for (var face : Direction.values()) {
            var support = target.relative(face.getOpposite());
            if (!game.level.getBlockState(support).isFaceSturdy(game.level, support, face)) continue;
            var point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.495));
            if (game.player.getEyePosition().distanceTo(point) > 4.3) continue;
            var hit = ExpeditionClient.ray(game, point);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection()!=face) continue;
            game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, point);
            if (!point.equals(previousAim)) { previousAim = point; return false; }
            if (game.level.getGameTime() < nextUse) return false;
            nextUse = game.level.getGameTime() + 12;
            game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            game.player.swing(InteractionHand.MAIN_HAND);
            return false;
        }
        return false;
    }
}
