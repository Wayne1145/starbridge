package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

/** The player installs the original press they crafted, powers it with a lever and observes its physical output. */
final class PressCycle {
    private static final BlockPos BASE = new BlockPos(63,148,61);
    private static final BlockPos METAL = BASE.above(), PRESS = METAL.above(), LEVER = PRESS.west();
    private static final String MACHINE = "advancedrocketry:tile.blockhandpress", TIN = "libvulpes:libvulpesmetal0", PLATES = "libvulpes:libvulpesproductplate";
    private static int step, next, started, pulse, diagramStep, nextFrame;
    private static net.minecraft.client.gui.screens.Screen materials, setup;
    private static CompletableFuture<Map<String,Object>> serverView;
    private PressCycle() {}

    static boolean diagram(Minecraft game, int tick) throws Exception {
        if (diagramStep >= 8) return true;
        if (tick < nextFrame) return false;
        switch (diagramStep) {
            case 0 -> {
                materials = game.screen;
                if (!HabitatClient.click(materials,"slate.starbridge.materials.press_setup")) return false;
            }
            case 1 -> {
                setup = game.screen;
                HabitatClient.snap(game,"00-press-diagram-off");
                if (!HabitatClient.click(setup,"slate.starbridge.materials.press_inspect_tin")) return false;
            }
            case 2, 5 -> {
                long inputs = game.screen.children().stream().filter(e -> e instanceof net.minecraft.client.gui.components.Button key
                        && key.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("slate.starbridge.materials.inspect")).count();
                if (inputs != 9) throw new IllegalStateException("The tin block must show nine ingot slots: " + inputs);
                if (diagramStep == 2) HabitatClient.snap(game,"00-press-tin-recipe");
                if (!HabitatClient.click(game.screen,"slate.starbridge.materials.back_press") || game.screen != setup)
                    throw new IllegalStateException("Tin recipe failed to return to the same press setup");
            }
            case 3 -> {
                if (!HabitatClient.click(game.screen,"slate.starbridge.materials.press_power")) return false;
            }
            case 4 -> {
                HabitatClient.snap(game,"00-press-diagram-on");
                if (HabitatClient.click(setup,"slate.starbridge.materials.press_inspect_tin"))
                    throw new IllegalStateException("The consumed tin slot must be empty");
                if (!HabitatClient.click(setup,"slate.starbridge.materials.press_tin_recipe")) return false;
            }
            case 6 -> {
                if (!HabitatClient.click(game.screen,"slate.starbridge.materials.press_reset")) return false;
            }
            case 7 -> {
                if (!HabitatClient.click(game.screen,"slate.starbridge.materials.press_back") || game.screen != materials)
                    throw new IllegalStateException("Press setup failed to return to the selected recipe");
                var result = Map.of("pass", true, "inputs", 9, "openedFromTinSlot", true, "openedFromPoweredButton", true,
                        "consumedSlotInactive", true, "returnedToSameLayout", true, "returnedToSameWorkshopRecipe", true);
                Files.writeString(Path.of("tin-recipe-route.json"), new Gson().toJson(result));
            }
            default -> throw new IllegalStateException();
        }
        diagramStep++;
        nextFrame=tick+10;
        return false;
    }

    static boolean tick(Minecraft game, int tick) throws Exception {
        if (step == 9) return true;
        if (tick < next) return false;
        var player=game.player;
        if (started == 0) started=tick;
        if (tick-started>450) {
            if (!Files.exists(Path.of("press-cycle-result.json"))) record(game,false,tick);
            return false;
        }
        switch(step) {
            case 0 -> { if (!(game.screen instanceof FirstFlightScreen) || !HabitatClient.click(game.screen,"gui.done")) return false; }
            case 1 -> {
                if (game.screen != null) return false;
                var machine=BuiltInRegistries.ITEM.getOptional(new ResourceLocation(MACHINE)).orElseThrow();
                var slot=player.inventoryMenu.slots.stream().filter(s -> s.hasItem() && s.getItem().is(machine)).findFirst().orElseThrow();
                game.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId,slot.index,8,ClickType.SWAP,player);
                player.getInventory().selected=4;
            }
            case 2 -> {
                if (!id(player.getInventory().getItem(8)).equals(MACHINE) || !id(player.getMainHandItem()).equals(TIN)) return false;
                use(game,BASE,Direction.UP);
            }
            case 3 -> {
                if (!block(game,METAL).equals(TIN)) return false;
                player.getInventory().selected=8;
                use(game,METAL,Direction.UP);
            }
            case 4 -> {
                if (!block(game,PRESS).equals(MACHINE)) return false;
                player.getInventory().selected=5;
                use(game,PRESS,Direction.WEST);
            }
            case 5 -> {
                if (!game.level.getBlockState(LEVER).is(Blocks.LEVER)) return false;
                HabitatClient.snap(game,"00-press-rig");
                var target=Vec3.atCenterOf(LEVER).add(.28,0,0);
                var hit=game.level.clip(new ClipContext(player.getEyePosition(),target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                if (hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(LEVER)) return false;
                player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,hit.getLocation());
                game.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit);
                pulse=tick;
            }
            case 6 -> {
                if (!game.level.getBlockState(LEVER).getValue(LeverBlock.POWERED)) return false;
                var dropped=game.level.getEntitiesOfClass(ItemEntity.class,new AABB(BASE).inflate(4));
                int plates=dropped.stream().map(ItemEntity::getItem).filter(PressCycle::tinPlate).mapToInt(ItemStack::getCount).sum();
                for (var stack:player.getInventory().items) if (tinPlate(stack)) plates+=stack.getCount();
                if (plates!=4) return false;
                var server=game.getSingleplayerServer();
                if (serverView == null) {
                    var pilotId=player.getUUID();
                    serverView=server.submit(() -> {
                        var level=server.overworld();
                        var pilot=server.getPlayerList().getPlayer(pilotId);
                        int inventory=pilot.getInventory().items.stream().filter(PressCycle::tinPlate).mapToInt(ItemStack::getCount).sum();
                        int ground=level.getEntitiesOfClass(ItemEntity.class,new AABB(BASE).inflate(4)).stream()
                                .map(ItemEntity::getItem).filter(PressCycle::tinPlate).mapToInt(ItemStack::getCount).sum();
                        return Map.of("inputConsumed",level.getBlockState(METAL).isAir(),"inInventory",inventory,"onGround",ground,
                                "powered",level.hasNeighborSignal(PRESS),"total",inventory+ground);
                    });
                    return false;
                }
                if (!serverView.isDone()) return false;
                var witness=serverView.join();
                serverView=null;
                if (!Boolean.TRUE.equals(witness.get("inputConsumed")) || !Integer.valueOf(4).equals(witness.get("total"))) return false;
                Files.writeString(Path.of("press-cycle-server.json"),new Gson().toJson(witness));
                HabitatClient.snap(game,"00-press-output");
                record(game,true,tick);
            }
            case 7 -> { player.getInventory().selected=6; }
            case 8 -> { game.gameMode.useItem(player,InteractionHand.MAIN_HAND); }
            default -> throw new IllegalStateException();
        }
        step++;
        next=tick+12;
        return false;
    }

    private static void record(Minecraft game, boolean pass, int tick) throws Exception {
        var level=game.level;
        var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(BASE).inflate(4)).stream().map(e -> e.getItem().save(new net.minecraft.nbt.CompoundTag()).toString()).toList();
        var state=Map.of("pass",pass,"step",step,"ticksSinceUse",pulse==0 ? -1 : tick-pulse,
                "obsidianBase",level.getBlockState(BASE).toString(),"inputCell",level.getBlockState(METAL).toString(),"machine",level.getBlockState(PRESS).toString(),
                "lever",level.getBlockState(LEVER).toString(),"powered",level.hasNeighborSignal(PRESS),"drops",drops,
                "acquiredPlates",game.player.getInventory().items.stream().filter(PressCycle::tinPlate).mapToInt(ItemStack::getCount).sum());
        Files.writeString(Path.of("press-cycle-result.json"),new Gson().toJson(state));
        HabitatClient.log("Press cycle " + state);
    }
    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static String block(Minecraft game, BlockPos pos) { return BuiltInRegistries.BLOCK.getKey(game.level.getBlockState(pos).getBlock()).toString(); }
    private static boolean tinPlate(ItemStack stack) { return id(stack).equals(PLATES) && stack.hasTag() && stack.getTag().getInt("LegacyMeta")==5; }
    private static void use(Minecraft game, BlockPos pos, Direction face) {
        var player=game.player;
        var point=Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499));
        var hit=game.level.clip(new ClipContext(player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if (hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection()!=face || player.getEyePosition().distanceTo(hit.getLocation())>4.4) return;
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,hit.getLocation());
        game.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit);
    }
}
