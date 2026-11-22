package dev.starbridge.flightqa;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;

/** Build a short boarding tower on visible ground next to a raised hull, then jump aboard. */
final class BoardingSteps {
    private BlockPos foot;
    private int stage, lastUse, leap;
    private boolean attempted;

    void tick(Minecraft game, int clock, BlockPos ship) throws Exception {
        var player = game.player;
        if (!attempted) {
            attempted = true;
            double dx = player.getX()-ship.getX()-.5, dz=player.getZ()-ship.getZ()-.5;
            foot = Math.abs(dx)>Math.abs(dz) ? ship.offset(dx>0?3:-3,0,0) : ship.offset(0,0,dz>0?3:-3);
            Files.writeString(Path.of("trail-near-hull"), "Returning on foot toward the selected ship");
            FlightClient.log("Approach visible ship side near " + foot.toShortString());
        }
        if (clock % 80 == 0) FlightClient.log("Boarding stage " + stage + " at " + player.blockPosition().toShortString());
        if (stage == 0) {
            move(game,foot,false);
            if (range(player.position(),foot)<.25 && player.onGround()) { still(game); stage=1; }
        } else if (stage == 1) {
            if (range(player.position(),foot)>.47) {stage=0; return;}
            still(game);
            if (player.getY()>=ship.getY()+3.4 && player.onGround()) { stage=2; player.getInventory().selected=0; leap=0; return; }
            player.getInventory().selected=4;
            player.setXRot(90);
            var eye=player.getEyePosition();
            var hit=game.level.clip(new ClipContext(eye,eye.add(0,-4.4,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
            if(hit.getType()!=HitResult.Type.BLOCK || hit.getDirection()!=Direction.UP) return;
            game.options.keyJump.setDown(player.onGround());
            if(player.getY()-hit.getLocation().y<1.02 || clock-lastUse<4) return;
            lastUse=clock;
            var result=game.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit);
            if(result.consumesAction()) {player.swing(InteractionHand.MAIN_HAND); FlightClient.log("Placed boarding step on " + hit.getBlockPos().toShortString());}
        } else {
            double distance=range(player.position(),ship);
            if (distance<.9 && player.getY()>ship.getY()-1 && player.onGround()) {
                still(game); return;
            }
            move(game,ship,true);
            if (leap++<4 || (player.horizontalCollision && player.onGround())) game.options.keyJump.setDown(true);
            if (player.getY() < ship.getY()-1.25 && player.onGround() && leap>35) {stage=0; leap=0;}
        }
    }
    private static double range(Vec3 pos, BlockPos block) {return Math.hypot(block.getX()+.5-pos.x,block.getZ()+.5-pos.z);}
    private static void move(Minecraft game, BlockPos block, boolean fast) {
        var player=game.player;
        player.lookAt(Anchor.EYES,new Vec3(block.getX()+.5,player.getEyeY(),block.getZ()+.5));
        game.options.keyUp.setDown(true);game.options.keyDown.setDown(false);game.options.keyLeft.setDown(false);game.options.keyRight.setDown(false);
        game.options.keyJump.setDown(player.horizontalCollision && player.onGround());game.options.keySprint.setDown(fast);game.options.keyShift.setDown(false);
    }
    private static void still(Minecraft game) {game.options.keyUp.setDown(false);game.options.keyJump.setDown(false);game.options.keySprint.setDown(false);}
}
