package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Comparator;

/** Cut trees actually visible above ground. Mining preferences from a previous ore trip do not direct lumber collection. */
final class SurfaceTimber {
    private static BlockPos tree;
    private static int nextSearch, nextPath, turn;
    private SurfaceTimber() {}
    static void reset() { tree=null;nextSearch=0;nextPath=0; }

    static boolean collect(Minecraft game, int logs, int tick) {
        if (ExpeditionClient.count(game,"#minecraft:logs")>=logs) return true;
        if (game.screen!=null) { game.screen.onClose(); return false; }
        var player=game.player;
        var walker=BaritoneAPI.getProvider().getPrimaryBaritone();
        var drop=game.level.getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(10),
                item -> item.getItem().is(net.minecraft.tags.ItemTags.LOGS) && player.hasLineOfSight(item)).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (drop!=null && player.distanceToSqr(drop)>1) {
            if (tick>=nextPath) { walker.getCustomGoalProcess().setGoalAndPath(new GoalNear(drop.blockPosition(),0)); nextPath=tick+35; }
            return false;
        }
        var at=player.blockPosition();
        if (tree!=null && !game.level.getBlockState(tree).is(BlockTags.LOGS)) { tree=null; nextSearch=tick+8; }
        if (tree==null && tick>=nextSearch) {
            nextSearch=tick+40;
            double best=18*18;
            for (var pos:BlockPos.betweenClosed(at.offset(-17,-4,-17),at.offset(17,8,17))) {
                if (!game.level.getBlockState(pos).is(BlockTags.LOGS)) continue;
                double score=at.distSqr(pos);
                if (score>=best || DigAt.visible(game,pos,18)==null) continue;
                best=score;tree=pos.immutable();
            }
            if (tree!=null) { nextPath=0; Expedition.log("Saw tree at " + tree.toShortString()); }
        }
        if (tree!=null) {
            if (DigAt.visible(game,tree,4.4)!=null) {
                if (walker.getPathingBehavior().isPathing()) walker.getPathingBehavior().cancelEverything();
                DigAt.one(game,tree);
            } else if (tick>=nextPath) {
                walker.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tree)); nextPath=tick+80;
                if (DigAt.visible(game,tree,20)==null) tree=null;
            }
        } else if (tick>=nextPath && !walker.getPathingBehavior().isPathing()) {
            int dx=switch(turn%4){ case 0 -> 14; case 2 -> -18;default -> 4;};
            int dz=switch(turn++%4){ case 1 -> 18; case 3 -> -14;default -> 3;};
            int x=at.getX()+dx,z=at.getZ()+dz;
            var ground=new BlockPos(x,game.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            walker.getCustomGoalProcess().setGoalAndPath(new GoalNear(ground,1)); nextPath=tick+80;
        }
        return false;
    }
}
