package dev.starbridge.flightqa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** Repeat the actual pickaxe and jump-placement sequence witnessed in the engineer's field save. */
final class FieldAscent {
    private Vec3 anchor;
    private BlockPos mining;
    private boolean climbing;
    private int since, cycle, began, originalSlot;

    boolean active() { return climbing; }

    boolean tick(Minecraft game, BlockPos destination, int tick) throws Exception {
        var p = game.player;
        if (!climbing) {
            if (anchor==null || horizontal(p.position(),anchor)>4) {anchor=p.position();since=tick;}
            if (tick-since<120 || !p.onGround() || horizontal(p.position(),Vec3.atCenterOf(destination))<2) return false;
            if (openGround(game)) {anchor=p.position();since=tick;return false;}
            climbing=true;began=cycle=tick;originalSlot=p.getInventory().selected;
            FlightClient.log("Escape enclosed terrain with the carried pickaxe and blocks at " + p.blockPosition().toShortString());
        }
        game.options.keyUp.setDown(false);game.options.keySprint.setDown(false);game.options.keyShift.setDown(false);
        game.options.keyDown.setDown(false);
        game.options.keyLeft.setDown(false);game.options.keyRight.setDown(false);
        if ((tick-cycle==0 && tick>began && openGround(game)) || tick-began>1600) {finish(game,tick);return false;}
        int age=tick-cycle;
        if (age<90) {
            game.options.keyJump.setDown(false);p.getInventory().selected=5;p.setXRot(-90);
            if (age>=4 && game.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK && canMine(game,hit)) {
                if (!hit.getBlockPos().equals(mining)) {game.gameMode.stopDestroyBlock();mining=hit.getBlockPos();game.gameMode.startDestroyBlock(mining,hit.getDirection());}
                else game.gameMode.continueDestroyBlock(mining,hit.getDirection());
                p.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            if (mining!=null) {game.gameMode.stopDestroyBlock();mining=null;}
            if (!material(game)) { finish(game,tick);return false; }
            p.setXRot(90);game.options.keyJump.setDown(age>=95);
            if (age>=95 && age%4==0 && game.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK && hit.getDirection()==Direction.UP) {
                if (game.gameMode.useItemOn(p,InteractionHand.MAIN_HAND,hit).consumesAction()) p.swing(InteractionHand.MAIN_HAND);
            }
            if (age>=166) {game.options.keyJump.setDown(false);cycle=tick+1;}
        }
        return true;
    }
    private static boolean canMine(Minecraft g,BlockHitResult hit) {
        var state=g.level.getBlockState(hit.getBlockPos());var key=ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key!=null && key.getNamespace().equals("galacticraftcore") && key.getPath().contains("moonblock") && !state.toString().contains("meta=14");
    }
    private static boolean material(Minecraft g) {
        var p=g.player;var inv=p.getInventory();
        if (!inv.getItem(4).isEmpty() && inv.getItem(4).getItem() instanceof BlockItem) {inv.selected=4;return true;}
        for (int i=9;i<36;i++) {
            var stack=inv.getItem(i);var key=ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key==null || !key.getNamespace().equals("galacticraftcore") || !key.getPath().contains("moonblock") || stack.getTag()==null) continue;
            int meta=stack.getTag().getInt("LegacyMeta");if (meta<3 || meta>5) continue;
            g.setScreen(new InventoryScreen(p));g.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId,i,4,ClickType.SWAP,p);p.closeContainer();
            inv.selected=4;return true;
        }
        inv.selected=3;return inv.getItem(3).is(Items.SCAFFOLDING);
    }
    private static boolean openGround(Minecraft game) {
        var p=game.player;var eye=p.getEyePosition();
        if (game.level.clip(new ClipContext(eye,eye.add(0,42,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()==HitResult.Type.BLOCK) return false;
        int open=0;
        for (var direction:Direction.Plane.HORIZONTAL) {
            var end=eye.add(direction.getStepX()*5,0,direction.getStepZ()*5);
            if (game.level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()!=HitResult.Type.BLOCK) open++;
        }
        return open>=2;
    }
    private static double horizontal(Vec3 a,Vec3 b) {return Math.hypot(a.x-b.x,a.z-b.z);}
    private void finish(Minecraft game,int tick) {
        if (mining!=null) game.gameMode.stopDestroyBlock();
        game.player.getInventory().selected=originalSlot;game.options.keyUp.setDown(false);game.options.keyJump.setDown(false);
        mining=null;climbing=false;anchor=game.player.position();since=tick;
    }
}
