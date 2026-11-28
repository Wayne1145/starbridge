package dev.starbridge.survivalqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Ordinary use actions on visible surfaces. Let a full movement packet carry directional placement to the server. */
final class BlockUse {
    private static Vec3 aim;
    private BlockUse() {}

    static boolean placeBed(Minecraft game, BlockPos foot, Direction direction) {
        if (direction==null || !direction.getAxis().isHorizontal()) throw new IllegalArgumentException("Choose a horizontal bed direction");
        var existing=game.level.getBlockState(foot);
        var head=game.level.getBlockState(foot.relative(direction));
        if (existing.is(Blocks.WHITE_BED) && head.is(Blocks.WHITE_BED))
            return existing.getValue(BedBlock.PART)==BedPart.FOOT && existing.getValue(BedBlock.FACING)==direction
                    && head.getValue(BedBlock.PART)==BedPart.HEAD && head.getValue(BedBlock.FACING)==direction;
        if (!existing.canBeReplaced() || !head.canBeReplaced() || !ExpeditionClient.select(game,"minecraft:white_bed")) return false;
        var eye=game.player.getEyePosition();
        for (double dx : new double[]{0,.38,-.38}) for (double dz : new double[]{0,.38,-.38}) {
            var point=Vec3.atCenterOf(foot.below()).add(dx,.499,dz);
            var looking=point.subtract(eye);
            if (Direction.getNearest(looking.x,0,looking.z)!=direction || eye.distanceTo(point)>4.4) continue;
            var ray=ExpeditionClient.ray(game,point);
            if (ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(foot.below()) && ray.getDirection()==Direction.UP) {
                use(game,ray); return false;
            }
        }
        return false;
    }

    static boolean click(Minecraft game, BlockPos pos) {
        if (game.screen!=null) { game.screen.onClose(); return false; }
        var state=game.level.getBlockState(pos);
        for (var box:state.getShape(game.level,pos).toAabbs()) {
            var point=box.getCenter().add(Vec3.atLowerCornerOf(pos));
            if (game.player.getEyePosition().distanceTo(point)>4.4) continue;
            var ray=ExpeditionClient.ray(game,point);
            if (ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(pos)) return use(game,ray);
        }
        return false;
    }

    static boolean sleepAt(Minecraft game, BlockPos bed) {
        if (game.player.isSleeping()) return true;
        long dayTime = Math.floorMod(game.level.getDayTime(), 24000L);
        if (dayTime >= 12542 && dayTime < 23460 && game.level.getBlockState(bed).is(Blocks.WHITE_BED)) {
            click(game, bed);
        }
        return false;
    }

    static boolean switchTo(Minecraft game, BlockPos pos, boolean on) {
        var state=game.level.getBlockState(pos);
        if (!state.is(Blocks.LEVER)) return false;
        if (state.getValue(LeverBlock.POWERED)==on) return true;
        click(game,pos);
        return false;
    }

    static boolean lightPortal(Minecraft game, BlockPos base) {
        if (game.level.getBlockState(base.above()).is(Blocks.NETHER_PORTAL)) return true;
        if (game.screen != null) { game.screen.onClose(); return false; }
        if (!game.level.getBlockState(base).is(Blocks.OBSIDIAN)
                || !ExpeditionClient.select(game, "minecraft:flint_and_steel")) return false;
        Vec3 top = Vec3.atCenterOf(base).add(0, .499, 0);
        if (game.player.getEyePosition().distanceTo(top) > 4.4) return false;
        var hit = ExpeditionClient.ray(game, top);
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(base) && hit.getDirection() == Direction.UP) {
            use(game, hit);
        }
        return false;
    }

    static boolean enterNether(Minecraft game, BlockPos gate) {
        var dimension = game.level.dimension();
        if (dimension.equals(net.minecraft.world.level.Level.NETHER)) return true;
        if (!dimension.equals(net.minecraft.world.level.Level.OVERWORLD)
                || !game.level.getBlockState(gate).is(Blocks.NETHER_PORTAL)) return false;
        PlaceAt.stand(game, gate);
        return false; // Wait for vanilla's actual dimension transition.
    }

    private static boolean use(Minecraft game, BlockHitResult hit) {
        var point=hit.getLocation();
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,point);
        if (aim==null || aim.distanceToSqr(point)>.0001) { aim=point; return false; }
        game.gameMode.useItemOn(game.player,InteractionHand.MAIN_HAND,hit);
        game.player.swing(InteractionHand.MAIN_HAND);
        aim=null;
        return true;
    }
}
