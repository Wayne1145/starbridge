package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridge_survivalqa", value = Dist.CLIENT)
public final class ExpeditionClient {
    private static final Path ORDERS = Path.of("expedition-orders.txt");
    private static final Path CURSOR = Path.of("expedition-cursor.txt");
    private static int tick, cursor = -1, lastAction;
    private static boolean mining;
    private static long nextReconnect;
    private static final Path JOIN_PORT = Path.of("expedition-join-port.txt");
    private static String lastStatus = "";
    private static BlockPos target;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || !Expedition.enabled()) return;
        var mc = Minecraft.getInstance();
        if (Files.isRegularFile(Path.of("expedition.stop"))) {
            mc.stop();
            return;
        }
        DigAt.advance(mc);
        if (++tick % 5 != 0) return;
        if (mc.screen instanceof DisconnectedScreen screen && mc.player == null && Files.isRegularFile(JOIN_PORT)) {
            long now = System.nanoTime();
            if (now >= nextReconnect) {
                int port = Integer.parseInt(Files.readString(JOIN_PORT).trim());
                if (port < 1 || port > 65535) throw new IllegalStateException("Invalid local expedition port");
                String address = "127.0.0.1:" + port;
                nextReconnect = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                Expedition.log("Reconnecting to the same local survival expedition");
                ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(address), new ServerData("Survival expedition", address, true), false);
            }
            return;
        }
        if (mc.player == null
                || mc.level == null
                || mc.gameMode == null
                || !Files.isRegularFile(ORDERS)) return;
        if (mc.player.isCreative() || mc.player.isSpectator() || mc.player.hasPermissions(2))
            throw new IllegalStateException("Fixture requires ordinary survival");
        if (FieldActions.surfaceForAir(mc) || FieldActions.respondToDanger(mc) || FieldActions.eatIfHungry(mc)) return;
        if (cursor < 0) {
            cursor =
                    Files.isRegularFile(CURSOR)
                            ? Integer.parseInt(Files.readString(CURSOR).trim())
                            : 0;
            var prefs = BaritoneAPI.getSettings();
            prefs.legitMine.value = true;
            prefs.avoidance.value = true;
            prefs.mobAvoidanceRadius.value = 8;
            prefs.mobAvoidanceCoefficient.value = 4.0;
            prefs.legitMineIncludeDiagonals.value = false;
            prefs.allowInventory.value = true;
            prefs.allowSprint.value = false;
            prefs.allowParkour.value = false;
            prefs.chatControl.value = false;
            var protectedBlocks = new ArrayList<>(prefs.blocksToDisallowBreaking.value);
            for(String block : List.of("minecraft:crafting_table","minecraft:furnace","minecraft:chest","minecraft:white_bed","minecraft:birch_planks","minecraft:birch_door","minecraft:torch", "advancedrocketry:tile.concrete", "advancedrocketry:launchpad", "advancedrocketry:seat", "advancedrocketry:rocketbuilder", "advancedrocketry:structuretower", "advancedrocketry:tile.blockhandpress", "libvulpes:tile.coalgenerator", "starbridge:ship_core")) {
                BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(block)).ifPresent(protectedBlocks::add);
            }
            prefs.blocksToDisallowBreaking.value = protectedBlocks;
        }
        List<String> orders =
                Files.readAllLines(ORDERS).stream()
                        .map(String::strip)
                        .filter(x -> !x.isBlank() && !x.startsWith("#"))
                        .toList();
        if (cursor >= orders.size()) {
            status("At saved checkpoint " + cursor);
            return;
        }
        String line = orders.get(cursor);
        String[] args = line.split("\\s+");
        if (lastAction + 6 > tick) return;
        boolean completed =
                switch (args[0]) {
                    case "dig" -> DigAt.one(mc, new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])));
                    case "exposed" -> DigAt.exposed(mc, args[1]);
                    case "lumber" -> SurfaceTimber.collect(mc, Integer.parseInt(args[1]), tick);
                    case "boards" -> boards(mc, Integer.parseInt(args[1]));
                    case "gather" -> gather(mc, args[1], args[2], Integer.parseInt(args[3]));
                    case "craft" -> craft(mc, args[1], args[2], Integer.parseInt(args[3]));
                    case "square" -> SquareCraft.tick(mc, args[1], args[2], Integer.parseInt(args[3]));
                    case "grid" -> SquareCraft.pattern(mc, args[1], args[2], Integer.parseInt(args[3]));
                    case "place" -> place(mc, args[1]);
                    case "bed" -> BlockUse.placeBed(mc, new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                            Integer.parseInt(args[3])), Direction.byName(args[4]));
                    case "use" -> BlockUse.click(mc, new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])));
                    case "lever" -> BlockUse.switchTo(mc, new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])), args[4].equals("on"));
                    case "awaitblock" -> {
                        BlockPos watched = new BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(watched)) > 5) yield false;
                        var hit = ray(mc, Vec3.atCenterOf(watched));
                        yield hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(watched)
                                && BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(watched).getBlock()).toString().equals(args[1]);
                    }
                    case "build" -> PlaceAt.tick(mc, args[1], new BlockPos(Integer.parseInt(args[2]),
                            Integer.parseInt(args[3]), Integer.parseInt(args[4])));
                    case "stand" -> PlaceAt.stand(mc, new BlockPos(Integer.parseInt(args[1]),
                            Integer.parseInt(args[2]), Integer.parseInt(args[3])));
                    case "open" -> open(mc, args[1]);
                    case "recover" -> FieldActions.recover(
                            mc,
                            args[1],
                            new BlockPos(
                                    Integer.parseInt(args[2]),
                                    Integer.parseInt(args[3]),
                                    Integer.parseInt(args[4])),
                            tick);
                    case "collect" -> FieldActions.collect(mc, args[1], Integer.parseInt(args[2]), tick);
                    case "hunt" -> FieldActions.hunt(
                            mc, args[1], args[2], Integer.parseInt(args[3]), tick);
                    case "readslate", "readslatecheck" -> FirstGuide.read(mc, tick, args[0].equals("readslatecheck"));
                    case "scoutobsidian" -> VisibleObsidian.search(mc, tick);
                    case "shield" -> FieldActions.equipShield(mc);
                    case "pour" -> BucketPlacement.pour(mc, args[1], new BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])));
                    case "water", "lava" -> FieldActions.fillBucket(mc, tick, args[0].equals("lava"));
                    case "smelt" -> FieldActions.smelt(
                            mc, args[1], args[2], args[3], Integer.parseInt(args[4]));
                    case "walk" -> walk(
                            mc,
                            new BlockPos(
                                    Integer.parseInt(args[1]),
                                    Integer.parseInt(args[2]),
                                    Integer.parseInt(args[3])));
                    case "store" -> FieldActions.store(mc,args[1]);
                    case "take" -> FieldActions.take(mc,args[1],Integer.parseInt(args[2]));
                    case "awake" -> !mc.player.isSleeping();
                    case "daylight" -> Math.floorMod(mc.level.getDayTime(), 24000L) < 10000;
                    case "close" -> {
                        if (mc.screen != null) mc.screen.onClose();
                        yield true;
                    }
                    default -> throw new IllegalArgumentException(
                            "Unrecognized survival action: " + line);
                };
        if (completed) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            Expedition.log(
                    mc.level.getGameTime()
                            + " complete "
                            + cursor
                            + " "
                            + line
                            + " at "
                            + mc.player.position());
            Files.writeString(CURSOR, Integer.toString(++cursor));
            DigAt.stop(mc);
            SurfaceTimber.reset();
            target = null;
            mining = false;
            lastAction = tick;
            lastStatus = "";
        }
    }

    static void interruptNavigation() {
        DigAt.stop(Minecraft.getInstance());
        SurfaceTimber.reset();
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        mining=false;
        target=null;
    }

    private static void status(String message) {
        if (!message.equals(lastStatus)) {
            Expedition.log(message);
            lastStatus = message;
        }
    }

    static boolean matches(ItemStack stack, String item) {
        if (item.startsWith("#")) return stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                new ResourceLocation(item.substring(1))));
        String[] identity = item.split("#", 2);
        return Expedition.item(stack).equals(identity[0])
                && (identity.length == 1
                        || (stack.hasTag() ? stack.getTag().getInt("LegacyMeta") : 0)
                                == Integer.parseInt(identity[1]));
    }

    static int count(Minecraft mc, String ids) {
        var names = Set.of(ids.split(","));
        int total = 0;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            var stack = mc.player.getInventory().getItem(slot);
            if (names.stream().anyMatch(name -> matches(stack, name))) total += stack.getCount();
        }
        return total;
    }

    private static boolean gather(Minecraft mc, String blocks, String result, int wanted) {
        if (count(mc, result) >= wanted) return true;
        if (mc.screen != null) {
            mc.screen.onClose();
            return false;
        }
        if (!mining) {
            status("Visible mining " + blocks + " for " + wanted + " " + result);
            var settings=BaritoneAPI.getSettings();
            if (blocks.contains("redstone_ore") || blocks.contains("diamond_ore") || blocks.equals("minecraft:obsidian")) settings.legitMineYLevel.value=-53;
            else if (blocks.contains("iron_ore")) settings.legitMineYLevel.value=16;
            else if (blocks.contains("coal_ore")) settings.legitMineYLevel.value=56;
            else if (blocks.contains("libvulpesore")) settings.legitMineYLevel.value=30;
            else if (blocks.contains("_log") || blocks.contains("minecraft:sand") || blocks.contains("minecraft:gravel") || blocks.contains("minecraft:dandelion"))
                settings.legitMineYLevel.value=mc.level.getSeaLevel() + 1;
            var process = BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess();
            process.mineByName(0, blocks.split(","));
            mining = true;
        }
        return false;
    }

    private static boolean boards(Minecraft mc, int wanted) {
        if (count(mc,"#minecraft:planks") >= wanted) return true;
        if (mc.screen instanceof InventoryScreen || mc.player.containerMenu instanceof CraftingMenu) {
            var output=mc.player.containerMenu.getSlot(0).getItem();
            if (!output.isEmpty() && matches(output, "#minecraft:planks")) {
                var name=Expedition.item(output);
                craft(mc,name,name,count(mc,name)+1);
                return false;
            }
        }
        for (String wood : List.of("oak","birch","spruce","dark_oak","jungle","acacia","cherry","mangrove")) {
            if (count(mc,"minecraft:"+wood+"_log")==0) continue;
            String plank="minecraft:"+wood+"_planks";
            craft(mc,plank,plank,count(mc,plank)+1);
            break;
        }
        return false;
    }

    private static boolean craft(Minecraft mc, String recipeId, String result, int wanted) {
        if (count(mc, result) >= wanted) return true;
        if (mc.screen == null) {
            mc.setScreen(new InventoryScreen(mc.player));
            return false;
        }
        var menu = mc.player.containerMenu;
        if (!(mc.screen instanceof InventoryScreen) && !(menu instanceof CraftingMenu)) {
            status("Need inventory or crafting table for " + recipeId);
            return false;
        }
        var output = menu.getSlot(0).getItem();
        if (!output.isEmpty() && matches(output, result)) {
            var taken = output.copy();
            String registryName = Expedition.item(taken);
            if (!registryName.startsWith("minecraft:")) {
                String image = "survival-craft-" + registryName.replace(':','-') + ".png";
                if (!new java.io.File(new java.io.File(mc.gameDirectory,"screenshots"),image).exists())
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory,image,mc.getMainRenderTarget(),message -> Expedition.log("Captured " + image));
            }
            mc.gameMode.handleInventoryMouseClick(
                    menu.containerId, 0, 0, ClickType.QUICK_MOVE, mc.player);
            Expedition.log(
                    "Took crafting result "
                            + taken
                            + " from "
                            + mc.screen.getClass().getSimpleName());
            lastAction = tick;
            return false;
        }
        var recipes = mc.level.getRecipeManager();
        var recipe =
                recipeId.equals("by-output")
                        ? recipes
                                .getAllRecipesFor(
                                        net.minecraft.world.item.crafting.RecipeType.CRAFTING)
                                .stream()
                                .filter(
                                        candidate ->
                                                matches(
                                                        candidate.getResultItem(
                                                                mc.level.registryAccess()),
                                                        result))
                                .filter(mc.player.getRecipeBook()::contains)
                                .min(
                                        java.util.Comparator.comparing(
                                                candidate -> candidate.getId().toString()))
                                .orElse(null)
                        : recipes.byKey(ResourceLocation.tryParse(recipeId)).orElse(null);
        if (recipe == null || !mc.player.getRecipeBook().contains(recipe)) {
            status("Recipe not yet known: " + recipeId);
            return false;
        }
        if (!(menu instanceof CraftingMenu) && !recipe.canCraftInDimensions(2, 2)) {
            if (mc.screen != null) mc.screen.onClose();
            open(mc, "minecraft:crafting_table");
            return false;
        }
        mc.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
        lastAction = tick;
        status("Recipe book selected " + recipeId);
        return false;
    }

    private static boolean walk(Minecraft mc, BlockPos pos) {
        if (mc.player.blockPosition().distManhattan(pos) <= 1) return true;
        if (!mining) {
            BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(pos, 1));
            mining = true;
        }
        return false;
    }

    static BlockHitResult ray(Minecraft mc, Vec3 point) {
        return mc.level.clip(
                new ClipContext(
                        mc.player.getEyePosition(),
                        point,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        mc.player));
    }

    static boolean select(Minecraft mc, String id) {
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++)
            if (matches(inventory.getItem(slot), id)) {
                if (slot < 9) {
                    inventory.selected = slot;
                    return true;
                }
                mc.gameMode.handleInventoryMouseClick(
                        mc.player.inventoryMenu.containerId,
                        slot,
                        inventory.selected,
                        ClickType.SWAP,
                        mc.player);
                lastAction = tick;
                return false;
            }
        status("Item not in inventory: " + id);
        return false;
    }

    static void use(Minecraft mc, BlockHitResult hit) {
        mc.player.lookAt(
                net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                hit.getLocation());
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastAction = tick;
    }

    private static boolean place(Minecraft mc, String item) {
        if (mc.screen != null) {
            mc.screen.onClose();
            return false;
        }
        if (target != null
                && BuiltInRegistries.BLOCK
                        .getKey(mc.level.getBlockState(target).getBlock())
                        .toString()
                        .equals(item)) {
            Expedition.log("Observed placed " + item + " at " + target.toShortString());
            return true;
        }
        if (!select(mc, item)) return false;
        var origin = mc.player.blockPosition();
        for (BlockPos candidate :
                BlockPos.betweenClosed(origin.offset(-3, -2, -3), origin.offset(3, 0, 3))) {
            BlockPos pos = candidate.immutable();
            if (target != null && !pos.equals(target)
                    || pos.distManhattan(origin) < 2
                    || !mc.level.getBlockState(pos).canBeReplaced()
                    || !mc.level
                            .getBlockState(pos.below())
                            .isFaceSturdy(mc.level, pos.below(), Direction.UP)) continue;
            var hit = ray(mc, Vec3.atCenterOf(pos.below()).add(0, .49, 0));
            if (hit.getType() != HitResult.Type.BLOCK
                    || hit.getDirection() != Direction.UP
                    || !hit.getBlockPos().equals(pos.below())
                    || mc.player.getEyePosition().distanceTo(hit.getLocation()) > 4.4) continue;
            target = pos;
            use(mc, hit);
            return false;
        }
        status("Need reachable level ground for " + item);
        return false;
    }

    static boolean open(Minecraft mc, String block) {
        if (mc.screen != null) {
            if (block.equals("minecraft:crafting_table") && mc.player.containerMenu instanceof CraftingMenu
                    || block.equals("minecraft:furnace") && mc.player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu
                    || block.equals("minecraft:chest") && mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu) return true;
            mc.screen.onClose();
            return false;
        }
        var origin=mc.player.blockPosition();
        BlockPos nearest=null;
        for (BlockPos candidate : BlockPos.betweenClosed(origin.offset(-5,-4,-5),origin.offset(5,4,5))) {
            if (!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(candidate).getBlock()).toString().equals(block)) continue;
            if (nearest==null || candidate.distSqr(origin)<nearest.distSqr(origin)) nearest=candidate.immutable();
            for (Direction face:Direction.values()) {
                Vec3 point=Vec3.atCenterOf(candidate).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.49));
                var hit=ray(mc,point);
                if (hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(candidate)
                        && mc.player.getEyePosition().distanceTo(hit.getLocation())<=4.4) {
                    use(mc,hit);
                    return false;
                }
            }
        }
        if (nearest!=null && (!nearest.equals(target) || tick-lastAction>60)) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                    .setGoalAndPath(new baritone.api.pathing.goals.GoalGetToBlock(nearest));
            target=nearest;
            lastAction=tick;
        }
        status("Approaching " + block + " to open its screen");
        return false;
    }
}
