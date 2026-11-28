package dev.starbridge.flightqa;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Optional live operator for this QA instance. Inputs act on the player's crosshair and rendered menus. */
final class LunarHands {
    private static final Path ENABLED=Path.of("field-operator.fixture"), ORDERS=Path.of("field-input.json");
    private static List<JsonObject> steps = List.of();
    private static int sequence=-1, step, since, lastTick;
    private static BlockPos mining;
    private static boolean wasEnabled;

    static boolean tick(Minecraft game, int tick) throws Exception {
        if (!Files.isRegularFile(ENABLED)) {
            if (wasEnabled) { release(game); wasEnabled=false; }
            return false;
        }
        if (!wasEnabled) { release(game); wasEnabled=true; }
        if (tick%3==0 && Files.isRegularFile(ORDERS)) {
            var order=JsonParser.parseString(Files.readString(ORDERS)).getAsJsonObject();
            int incoming=order.get("id").getAsInt();
            if (incoming>sequence) {
                release(game); sequence=incoming; step=0; since=tick;
                steps=new ArrayList<>(); order.getAsJsonArray("steps").forEach(value->steps.add(value.getAsJsonObject()));
                Files.writeString(Path.of("field-operator.log"),order+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
        }
        if (step<steps.size()) {
            var action=steps.get(step);
            if (act(game,action,tick-since)) {
                release(game);
                step++; since=tick+1;
                if (step==steps.size()) {observe(game,"field-view-"+sequence,true);lastTick=tick;}
            }
        } else if (tick-lastTick>=60) {observe(game,"field-current",false);lastTick=tick;}
        return true;
    }

    private static boolean act(Minecraft g, JsonObject a, int age) throws Exception {
        if (age<0) return false;
        var p=g.player; var o=g.options;
        int length=Math.max(1,Math.min(1600,a.has("ticks")?a.get("ticks").getAsInt():1));
        if (a.has("yaw")) p.setYRot(a.get("yaw").getAsFloat());
        if (a.has("pitch")) p.setXRot(Math.max(-90,Math.min(90,a.get("pitch").getAsFloat())));
        if (a.has("slot")) p.getInventory().selected=Math.max(0,Math.min(8,a.get("slot").getAsInt()));
        switch(a.get("action").getAsString()) {
            case "look", "wait" -> { }
            case "walk" -> {
                o.keyUp.setDown(!a.has("forward") || a.get("forward").getAsBoolean());
                o.keyLeft.setDown(flag(a,"left"));o.keyRight.setDown(flag(a,"right"));o.keyDown.setDown(flag(a,"back"));
                o.keySprint.setDown(flag(a,"sprint"));o.keyShift.setDown(flag(a,"sneak"));o.keyJump.setDown(flag(a,"jump"));
            }
            case "mine" -> {
                if (g.screen==null && g.hitResult instanceof BlockHitResult b && b.getType()==HitResult.Type.BLOCK) {
                    if (!b.getBlockPos().equals(mining)) {g.gameMode.stopDestroyBlock();mining=b.getBlockPos();g.gameMode.startDestroyBlock(mining,b.getDirection());}
                    else g.gameMode.continueDestroyBlock(mining,b.getDirection());
                    p.swing(InteractionHand.MAIN_HAND);
                }
            }
            case "place", "use" -> {
                if (g.screen!=null) break;
                o.keyShift.setDown(flag(a,"sneak")); o.keyJump.setDown(flag(a,"jump"));
                if (a.get("action").getAsString().equals("use")) {
                    o.keyUse.setDown(true);
                    if (age==2 || age==0 && length<=2) g.gameMode.useItem(p,InteractionHand.MAIN_HAND);
                } else if (age%4==0 && g.hitResult instanceof BlockHitResult b && b.getType()==HitResult.Type.BLOCK) {
                    if (g.gameMode.useItemOn(p,InteractionHand.MAIN_HAND,b).consumesAction()) p.swing(InteractionHand.MAIN_HAND);
                }
            }
            case "attack" -> {
                if (g.screen==null && g.hitResult instanceof EntityHitResult hit && p.getAttackStrengthScale(0)>.94f) {
                    g.gameMode.attack(p,hit.getEntity());p.swing(InteractionHand.MAIN_HAND);
                }
            }
            case "menu" -> {
                if (g.screen!=null && FlightClient.click(g.screen,a.get("button").getAsString())) return true;
            }
            case "inventory" -> {if (age==0 && g.screen==null) g.setScreen(new InventoryScreen(p));}
            case "take" -> {
                if (age==0 && g.screen instanceof AbstractContainerScreen<?> screen) {
                    int slot=a.get("index").getAsInt();
                    if (slot>=0 && slot<screen.getMenu().slots.size()) g.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,slot,0,ClickType.QUICK_MOVE,p);
                }
            }
            case "close" -> {if (g.screen!=null) g.screen.onClose();}
            case "photo" -> {if (age==length-1) observe(g,"field-eye-"+sequence+"-"+step,true);}
            default -> throw new IllegalArgumentException("Unknown field input");
        }
        return age+1>=length;
    }
    private static boolean flag(JsonObject a,String key) {return a.has(key)&&a.get(key).getAsBoolean();}
    private static void release(Minecraft g) {
        var o=g.options;
        for (var key:List.of(o.keyUp,o.keyLeft,o.keyRight,o.keyDown,o.keyJump,o.keySprint,o.keyShift,o.keyUse,o.keyAttack)) key.setDown(false);
        if (mining!=null) {g.gameMode.stopDestroyBlock();mining=null;}
        if (g.player.isUsingItem()) g.gameMode.releaseUsingItem(g.player);
    }
    private static void observe(Minecraft g,String name,boolean photo) throws Exception {
        var p=g.player;var state=new LinkedHashMap<String,Object>();
        state.put("order",sequence);state.put("step",step);state.put("feet",p.position().toString());
        state.put("yaw",p.getYRot());state.put("pitch",p.getXRot());state.put("screen",g.screen==null?"world":g.screen.getClass().getSimpleName());
        state.put("dimension",g.level.dimension().location().toString());state.put("health",p.getHealth());state.put("food",p.getFoodData().getFoodLevel());
        state.put("selected",p.getInventory().selected);state.put("inHand",p.getMainHandItem().getHoverName().getString());
        var eye=p.getEyePosition();var hit=g.level.clip(new ClipContext(eye,eye.add(p.getViewVector(1).scale(64)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if (hit.getType()==HitResult.Type.BLOCK) { state.put("visibleBlock",g.level.getBlockState(hit.getBlockPos()).toString());state.put("surface",hit.getBlockPos().toShortString());state.put("range",eye.distanceTo(hit.getLocation())); }
        if (g.hitResult instanceof EntityHitResult mob) state.put("entityUnderCrosshair",String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(mob.getEntity().getType())));
        if (g.screen instanceof AbstractContainerScreen<?> cont) {
            var items=new TreeMap<Integer,String>();
            for (var slot:cont.getMenu().slots) if (slot.hasItem()) items.put(slot.index,slot.getItem().toString());
            state.put("visibleSlots",items);
        }
        LunarTrail.recordLive(Path.of("field-operator-state.json"),state);
        if (photo) {FlightClient.snap(g,name); LunarTrail.record(Path.of(name+".json"),state);}
    }
}
