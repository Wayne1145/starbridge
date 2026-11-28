package dev.starbridge.flightqa;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A stuck walker may centre on a visible patch of ground and jump-place one of its own blocks. */
final class LedgeStep {
    private BlockPos goal, base, mining;
    private double best;
    private int progressed, started, placed, oldSlot;
    private Direction side;

    boolean tick(Minecraft game, BlockPos target, double distance, int tick) {
        var player=game.player;
        if (!target.equals(goal)) { goal=target; best=distance; progressed=tick; }
        if (distance<best-.8) {best=distance; progressed=tick;}
        if (base==null && (distance<2.5 || tick-progressed<65 || !player.onGround() || !player.getInventory().getItem(4).is(Items.COBBLESTONE))) return false;
        if (base==null) {base=player.blockPosition();started=tick;placed=0;oldSlot=player.getInventory().selected;}
        if (tick-started>160 || placed!=0 && player.onGround() && tick>placed+3) {
            if (mining!=null) {game.gameMode.stopDestroyBlock();mining=null;}
            player.getInventory().selected=oldSlot;game.options.keyUp.setDown(false);game.options.keyJump.setDown(false);
            base=null;progressed=tick;best=distance;return false;
        }
        game.options.keySprint.setDown(false);game.options.keyDown.setDown(false);game.options.keyLeft.setDown(false);game.options.keyRight.setDown(false);
        var centre=new Vec3(base.getX()+.5,player.getEyeY(),base.getZ()+.5);
        if (Math.hypot(player.getX()-centre.x,player.getZ()-centre.z)>.27) {
            player.lookAt(Anchor.EYES,centre);game.options.keyUp.setDown(true);game.options.keyJump.setDown(false);return true;
        }
        if (placed==0 && tick-started>20 && cutVisibleOverhang(game,target)) return true;
        game.options.keyUp.setDown(false); game.options.keyJump.setDown(placed==0 && player.onGround());
        player.setXRot(90);player.getInventory().selected=4;
        var eye=player.getEyePosition();var hit=game.level.clip(new ClipContext(eye,eye.add(0,-4.4,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if (placed!=0 || hit.getType()!=HitResult.Type.BLOCK || hit.getDirection()!=Direction.UP || player.getY()-hit.getLocation().y<1.02) return true;
        if (game.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit).consumesAction()) { player.swing(InteractionHand.MAIN_HAND);placed=tick; }
        return true;
    }
    private boolean cutVisibleOverhang(Minecraft game,BlockPos destination) {
        var player=game.player; var eye=player.getEyePosition();
        var ahead=new Vec3(destination.getX()+.5-player.getX(),0,destination.getZ()+.5-player.getZ()).normalize();
        for (var vector : new Vec3[]{new Vec3(0,2.8,0),ahead.scale(2.4).add(0,.6,0),ahead.scale(2.5)}) {
            var hit=game.level.clip(new ClipContext(eye,eye.add(vector),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
            if (hit.getType()!=HitResult.Type.BLOCK || eye.distanceTo(hit.getLocation())>4) continue;
            var state=game.level.getBlockState(hit.getBlockPos());
            var key=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if(key==null || !key.getNamespace().equals("galacticraftcore") || !key.getPath().contains("moonblock")) continue;
            // The player clears only the first visible natural rock surface, never dungeon masonry.
            if (state.toString().contains("meta=14")) continue;
            player.getInventory().selected=5;game.options.keyUp.setDown(false);game.options.keyJump.setDown(false);
            player.lookAt(Anchor.EYES,hit.getLocation());
            if (!hit.getBlockPos().equals(mining) || side!=hit.getDirection()) {
                if (mining!=null) game.gameMode.stopDestroyBlock();
                mining=hit.getBlockPos();side=hit.getDirection();game.gameMode.startDestroyBlock(mining,side);
            } else game.gameMode.continueDestroyBlock(mining,side);
            player.swing(InteractionHand.MAIN_HAND);return true;
        }
        if (mining!=null) {game.gameMode.stopDestroyBlock();mining=null;}
        return false;
    }

}
