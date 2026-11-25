package dev.starbridge.survivalqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/** Break one visible, reachable face with the survivor's own tool, at normal vanilla mining speed. */
final class DigAt {
    private static BlockPos target;
    private static Direction face;
    private DigAt() {}

    static void advance(Minecraft game) {
        if (target == null) return;
        if (game.player==null || game.gameMode==null || game.level==null || game.screen!=null || !game.player.isAlive()) { stop(game); return; }
        if (game.level.isEmptyBlock(target)) { stop(game); return; }
        var hit=visible(game,target,4.4);
        if (hit==null) { stop(game); return; }
        face=hit.getDirection();
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,hit.getLocation());
        game.gameMode.continueDestroyBlock(target,face);
        game.player.swing(InteractionHand.MAIN_HAND);
    }

    static void stop(Minecraft game) {
        if (target!=null && game.gameMode!=null) game.gameMode.stopDestroyBlock();
        target=null;
    }

    static boolean one(Minecraft game, BlockPos pos) {
        if (game.screen!=null) { game.screen.onClose(); return false; }
        BlockState state=game.level.getBlockState(pos);
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty() || state.hasBlockEntity() || state.getDestroySpeed(game.level,pos)<0) return false;
        if (pos.equals(target)) return false;
        var hit=visible(game,pos,4.4);
        if (hit==null) return false;
        var inventory=game.player.getInventory();
        int best=-1; float speed=0;
        for (int i=0; i<36; i++) {
            var stack=inventory.getItem(i);
            if (stack.isEmpty() || (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state))) continue;
            float score=stack.getDestroySpeed(state);
            if (score>speed) { best=i; speed=score; }
        }
        if (best<0 && state.requiresCorrectToolForDrops()) return false;
        if (best>=0 && speed>1 && !ExpeditionClient.select(game,Expedition.item(inventory.getItem(best)))) return false;
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,hit.getLocation());
        target=pos.immutable(); face=hit.getDirection();
        if (!game.gameMode.startDestroyBlock(target,face)) { stop(game); return false; }
        game.player.swing(InteractionHand.MAIN_HAND);
        return false;
    }

    static boolean harvest(Minecraft game, String block, BlockPos position) {
        var state = game.level.getBlockState(position);
        if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.WATER)) return true;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block) && one(game, position);
    }

    static boolean exposed(Minecraft game,String requested) {
        if (target!=null) return false;
        var types=Arrays.asList(requested.split(","));
        var center=game.player.blockPosition();
        for (BlockPos candidate : BlockPos.betweenClosed(center.offset(-4,-3,-4),center.offset(4,5,4))) {
            var state=game.level.getBlockState(candidate);
            var id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!types.contains(id) || visible(game,candidate,4.4)==null) continue;
            one(game,candidate); return false;
        }
        return true;
    }

    static BlockHitResult visible(Minecraft game, BlockPos pos, double reach) {
        var eye=game.player.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(pos))>reach+.87) return null;
        for (Direction side:Direction.values()) {
            var point=Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(.49));
            var hit=ExpeditionClient.ray(game,point);
            if (hit.getType()== HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && eye.distanceTo(hit.getLocation())<=reach) return hit;
        }
        return null;
    }
}
