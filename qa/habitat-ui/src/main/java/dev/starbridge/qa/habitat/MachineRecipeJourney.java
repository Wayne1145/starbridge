package dev.starbridge.qa.habitat;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.MachineAssemblyScreen;
import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Use the survival terminal and click the original navigation computer's ingredients. */
final class MachineRecipeJourney {
    static final Path MARKER = Path.of("starbridge-machine-recipes.fixture");
    private static final String TOWER = "advancedrocketry:structuretower";
    private static final String GUIDANCE = "advancedrocketry:tile.guidancecomputer";
    private static final String CIRCUIT = "advancedrocketry:circuitic";
    private static final String PLATE = "libvulpes:libvulpesproductplate";
    private static final String INGOT = "libvulpes:libvulpesproductingot";
    private static final String ARC_FURNACE = "advancedrocketry:tile.electricarcfurnace";
    private static int step, next;
    private static int stockedSlots, towerBatches, arcBrickBatches;

    private MachineRecipeJourney() {}

    static void supplyWorkbenchSample(net.minecraft.server.level.ServerPlayer player) {
        var circuit = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(CIRCUIT));
        if (circuit == null) throw new IllegalStateException("Original circuit required");
        var tracking = new ItemStack(circuit);
        tracking.getOrCreateTag().putInt("LegacyMeta", 1);
        var control = new ItemStack(circuit, 3);
        control.getOrCreateTag().putInt("LegacyMeta", 3);
        player.getInventory().setItem(10, tracking);
        player.getInventory().setItem(11, control);
        player.getInventory().setItem(12, new ItemStack(net.minecraft.world.item.Items.REDSTONE, 2));
        var tower = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(TOWER));
        if (tower == null) throw new IllegalStateException("Original tower required");
        player.getInventory().setItem(13, new ItemStack(tower, 2));
        var bricks = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("advancedrocketry:utilblock"));
        if (bricks == null) throw new IllegalStateException("Original blast bricks required");
        player.getInventory().setItem(14, new ItemStack(bricks, 17));
    }

    static void tick(Minecraft game, int tick) throws Exception {
        if (step > 41 || tick < next) return;
        var screen = game.screen;
        switch (step) {
            case 0 -> {
                if (!game.player.getMainHandItem().is(VoyageModule.SLATE.get())) return;
                for (String part : List.of(GUIDANCE, CIRCUIT, PLATE)) {
                    if (!ForgeRegistries.ITEMS.containsKey(ResourceLocation.parse(part))) {
                        throw new IllegalStateException("Original workshop item not installed: " + part);
                    }
                }
                game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            }
            case 1 -> {
                if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen, "slate.starbridge.guide.shipyard")) return;
            }
            case 2 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.title")) return;
            }
            case 3 -> {
                if (!clickItem(screen, GUIDANCE, 0)) return;
                HabitatClient.log("Selected the navigation computer recipe");
            }
            case 4 -> {
                if (!(screen instanceof FabricationScreen) || !contains(screen, CIRCUIT, 1)) return;
                stockedSlots = integer(screen, "readySlots");
                if (stockedSlots != 3 || integer(screen, "neededSlots") != 9) {
                    throw new IllegalStateException("One tracking circuit and two redstone must fill exactly three slots, including with three wrong circuits carried");
                }
                HabitatClient.snap(game, "machine-guidance-stock");
                HabitatClient.log("Guidance workbench showed three stocked slots of nine");
                if (!clickItem(screen, CIRCUIT, 1)) return;
                HabitatClient.log("Selected the tracking circuit ingredient");
            }
            case 5 -> {
                if (!contains(screen, "advancedrocketry:precisionassemblingmachine", 0)
                        || !contains(screen, "advancedrocketry:circuitplate", 0)
                        || !contains(screen, "minecraft:ender_eye", 0)) return;
                HabitatClient.snap(game, "machine-tracking-circuit");
                HabitatClient.log("Saw the original precision assembler and circuit ingredients");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 6 -> {
                if (!layout(screen, 4, 3, 3, 36, 2)) return;
                HabitatClient.snap(game, "machine-precision-assembly");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 7 -> {
                if (!contains(screen, "advancedrocketry:precisionassemblingmachine", 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 8 -> {
                if (!clickItem(screen, PLATE, 7)) return;
                HabitatClient.log("Selected the titanium plate ingredient");
            }
            case 9 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0)
                        || !contains(screen, "libvulpes:libvulpesproductingot", 7)) return;
                HabitatClient.snap(game, "machine-titanium-plate");
                HabitatClient.log("Saw the original rolling machine and titanium ingot");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 10 -> {
                if (!layout(screen, 4, 2, 3, 22, 6)) return;
                HabitatClient.snap(game, "machine-rolling-layout");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 11 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0) || !clickItem(screen, INGOT, 7)) return;
                HabitatClient.log("Selected the titanium ingot ingredient");
            }
            case 12 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.machine_list")) return;
            }
            case 13 -> {
                if (!contains(screen, ARC_FURNACE, 0) || !contains(screen, "libvulpes:libvulpesore0", 8)) return;
                HabitatClient.snap(game, "machine-titanium-ingot");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 14 -> {
                if (!layout(screen, 5, 5, 5, 105, 15)) return;
                HabitatClient.snap(game, "machine-arc-layout");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 15 -> {
                if (!contains(screen, ARC_FURNACE, 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 16 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 17 -> {
                if (!contains(screen, CIRCUIT, 1)) return;
                if (!clickItem(screen, TOWER, 0)) return;
            }
            case 18 -> {
                if (!contains(screen, "libvulpes:libvulpesproductrod", 6)) return;
                towerBatches = towerBatches(screen);
                if (towerBatches != 4) throw new IllegalStateException("Two finished tower blocks must leave four production batches");
                HabitatClient.snap(game, "machine-tower-batches");
                if (!clickItem(screen, "libvulpes:libvulpesproductrod", 6)) return;
                if (!contains(screen, INGOT, 6) || screen.children().stream().anyMatch(widget -> widget instanceof Button button
                        && button.getMessage().getString().equals("›"))) {
                    throw new IllegalStateException("Identical original steel rod grids should appear once");
                }
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")
                        || !HabitatClient.click(screen, "slate.starbridge.materials.workshop")) return;
            }
            case 19 -> {
                if (!clickItem(screen, ARC_FURNACE, 0)) return;
            }
            case 20 -> {
                if (!contains(screen, "minecraft:nether_brick", 0)
                        || !contains(screen, "advancedrocketry:utilblock", 0)) return;
                HabitatClient.snap(game, "machine-workshop-arc-recipe");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 21 -> {
                if (!layout(screen, 5, 5, 5, 105, 15)) return;
                var brick = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("advancedrocketry:utilblock"));
                if (!clickLabel(screen, new ItemStack(brick).getHoverName().getString())) return;
            }
            case 22 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.machine.part_recipe")) return;
            }
            case 23 -> {
                if (!(screen instanceof FabricationScreen)) return;
                var goal = FabricationScreen.class.getDeclaredMethod("structureGoal");
                goal.setAccessible(true);
                if (!Integer.valueOf(81).equals(goal.invoke(screen))) {
                    throw new IllegalStateException("Arc furnace diagram must carry the 81 blast bricks of the original structure into the recipe");
                }
                var bricks = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("advancedrocketry:utilblock"));
                int owned = fabricationStock(screen, new ItemStack(bricks));
                var result = fabricationDetails(screen, "shapeless", 16, owned);
                if (owned != 17 || !(result.getContents() instanceof TranslatableContents details)
                        || !details.getKey().equals("slate.starbridge.materials.structure_batches")
                        || !Integer.valueOf(4).equals(details.getArgs()[0])) {
                    throw new IllegalStateException("17 carried furnace bricks must leave four batches for the original example structure");
                }
                arcBrickBatches = 4;
                HabitatClient.snap(game, "machine-arc-brick-batches");
                HabitatClient.log("Original arc furnace brick recipe showed four remaining batches");
            }
            case 24 -> {
                if (!clickPotion(screen, Potions.FIRE_RESISTANCE)) return;
            }
            case 25 -> {
                if (!contains(screen, "minecraft:brewing_stand", 0)
                        || !contains(screen, "minecraft:magma_cream", 0)) return;
                HabitatClient.snap(game, "machine-fire-resistance-brew");
                if (!clickPotion(screen, Potions.AWKWARD)) return;
            }
            case 26 -> {
                if (!contains(screen, "minecraft:nether_wart", 0) || !containsPotion(screen, Potions.WATER)) return;
                HabitatClient.snap(game, "machine-awkward-brew");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 27 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 28 -> {
                if (!clickPotion(screen, Potions.STRENGTH)) return;
            }
            case 29 -> {
                if (!contains(screen, "minecraft:blaze_powder", 0) || !containsPotion(screen, Potions.AWKWARD)) return;
                HabitatClient.snap(game, "machine-strength-brew");
                HabitatClient.log("Opened the fire resistance, awkward and strength brewing recipes from the blast bricks");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 30 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_structure")) return;
            }
            case 31 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.machine.higher")) return;
            }
            case 32, 33, 34 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.machine.higher")) return;
            }
            case 35 -> {
                var battery = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("libvulpes:rfbattery"));
                var name = new ItemStack(battery).getHoverName().getString();
                if (!clickLabel(screen, name)) return;
                HabitatClient.log("Selected the top arc furnace power input");
            }
            case 36 -> {
                var diagram = MachineAssemblyScreen.class.getDeclaredMethod("showsArcPower");
                diagram.setAccessible(true);
                if (!Boolean.TRUE.equals(diagram.invoke(screen))) throw new IllegalStateException("Selected arc furnace power input lacks its connection diagram");
                HabitatClient.snap(game, "machine-arc-coal-power");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 37 -> {
                if (!clickItem(screen, CIRCUIT, 4)) return;
            }
            case 38 -> {
                if (!clickItem(screen, "libvulpes:libvulpesproductdust", 2)) return;
            }
            case 39 -> {
                if (!contains(screen, "minecraft:gold_ore", 0)
                        || !HabitatClient.click(screen, "slate.starbridge.materials.press_setup")) return;
            }
            case 40 -> {
                var input = pressItem(screen, "input");
                var output = pressItem(screen, "output");
                if (!input.is(net.minecraft.world.item.Items.GOLD_ORE) || output.getCount() != 2
                        || !"libvulpes:libvulpesproductdust".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(output.getItem())))
                        || output.getTag() == null || output.getTag().getInt("LegacyMeta") != 2) {
                    throw new IllegalStateException("The original hand press must show one gold ore producing two gold dust");
                }
                HabitatClient.snap(game, "machine-hand-press-gold-input");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.press_power")) return;
            }
            case 41 -> {
                var powered = screen.getClass().getDeclaredField("powered");
                powered.setAccessible(true);
                if (!powered.getBoolean(screen)) throw new IllegalStateException("The press diagram did not apply the lever state");
                var output = pressItem(screen, "output");
                HabitatClient.snap(game, "machine-hand-press-gold-output");
                var server = game.getSingleplayerServer();
                if (server == null || server.getWorldData().getAllowCommands() || game.player.isCreative()
                        || game.player.hasPermissions(2)) throw new IllegalStateException("Survival and cheats off required");
                GardenCamp.save("machine-recipes-client", Map.ofEntries(
                        Map.entry("pass", true), Map.entry("gameType", "survival"), Map.entry("cheats", false),
                        Map.entry("activeParts", List.of("trackingCircuit", "titaniumPlate", "titaniumIngot")),
                        Map.entry("returnedToGuidance", true), Map.entry("layers", List.of(3, 2, 5)),
                        Map.entry("guidanceStock", List.of(stockedSlots, 9)), Map.entry("towerBatches", towerBatches),
                        Map.entry("workshopArcFurnace", true), Map.entry("arcPowerDiagram", true),
                        Map.entry("arcBrickBatches", arcBrickBatches),
                        Map.entry("arcBrewing", List.of("fire_resistance", "awkward", "strength")),
                        Map.entry("goldDustPerOre", output.getCount())));
            }
            default -> throw new IllegalStateException("Unexpected fabrication step");
        }
        step++;
        next = tick + 16;
    }

    private static boolean clickLabel(Screen screen, String name) {
        for (var widget : screen.children()) {
            if (!(widget instanceof Button button) || !button.getMessage().getString().equals(name)) continue;
            double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
            if (screen.mouseClicked(x, y, 0)) {
                screen.mouseReleased(x, y, 0);
                return true;
            }
        }
        return false;
    }

    private static Component fabricationDetails(Screen screen, String kind, int result, int owned) throws Exception {
        var details = FabricationScreen.class.getDeclaredMethod("resultDetails", String.class, int.class, int.class);
        details.setAccessible(true);
        return (Component) details.invoke(screen, kind, result, owned);
    }

    private static ItemStack pressItem(Screen screen, String fieldName) throws Exception {
        if (screen == null || !screen.getClass().getSimpleName().equals("PressSetupScreen")) {
            throw new IllegalStateException("The hand press diagram did not open");
        }
        var field = screen.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ItemStack) field.get(screen);
    }

    private static int fabricationStock(Screen screen, ItemStack stack) throws Exception {
        var stock = FabricationScreen.class.getDeclaredMethod("owned", ItemStack.class);
        stock.setAccessible(true);
        return (int) stock.invoke(screen, stack);
    }

    private static int towerBatches(Screen screen) throws Exception {
        var tower = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(TOWER)));
        int owned = fabricationStock(screen, tower);
        var rendered = fabricationDetails(screen, "shaped", 1, owned);
        if (rendered.getContents() instanceof TranslatableContents label
                && label.getKey().equals("slate.starbridge.materials.plan_batches")
                && label.getArgs()[0] instanceof Integer batches) return batches;
        throw new IllegalStateException("Missing tower quantity from the open crafting panel");
    }

    private static int integer(Screen screen, String name) throws Exception {
        var field = FabricationScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static boolean layout(Screen screen, int width, int layers, int depth, int cells, int clear) throws Exception {
        if (!(screen instanceof MachineAssemblyScreen)) return false;
        var field = MachineAssemblyScreen.class.getDeclaredField("plan");
        field.setAccessible(true);
        if (!(field.get(screen) instanceof OriginalMachineLayout plan) || plan.width() != width || plan.depth() != depth
                || plan.height() != layers || plan.cells().size() != cells || plan.cells().stream()
                .filter(cell -> cell.role() == OriginalMachineLayout.Role.CLEAR).count() != clear) {
            throw new IllegalStateException("The original machine layout did not show the registered cells");
        }
        return true;
    }

    private static boolean clickItem(Screen screen, String id, int metadata) throws Exception {
        return clickIngredient(screen, itemButton(screen, id, metadata));
    }

    private static boolean clickPotion(Screen screen, Potion potion) throws Exception {
        return clickIngredient(screen, potionButton(screen, potion));
    }

    private static boolean containsPotion(Screen screen, Potion potion) throws Exception {
        return potionButton(screen, potion) != null;
    }

    private static Button potionButton(Screen screen, Potion potion) throws Exception {
        return ingredientButton(screen, stack -> stack.is(Items.POTION) && PotionUtils.getPotion(stack) == potion);
    }

    private static boolean clickIngredient(Screen screen, Button button) {
        if (button == null) return false;
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        if (!screen.mouseClicked(x, y, 0)) return false;
        screen.mouseReleased(x, y, 0);
        return true;
    }

    private static boolean contains(Screen screen, String id, int metadata) throws Exception {
        return itemButton(screen, id, metadata) != null;
    }

    private static Button itemButton(Screen screen, String id, int metadata) throws Exception {
        return ingredientButton(screen, stack -> id.equals(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                && metadata == (stack.getTag() == null ? 0 : stack.getTag().getInt("LegacyMeta")));
    }

    private static Button ingredientButton(Screen screen, java.util.function.Predicate<ItemStack> accepts) throws Exception {
        if (!(screen instanceof FabricationScreen)) return null;
        for (var widget : screen.children()) {
            if (!(widget instanceof Button button) || !button.visible || !button.active
                    || !button.getClass().getSimpleName().equals("IconButton")) continue;
            var icon = button.getClass().getDeclaredField("icon");
            icon.setAccessible(true);
            if (((Supplier<?>) icon.get(button)).get() instanceof ItemStack stack
                    && !stack.isEmpty() && accepts.test(stack)) return button;
        }
        return null;
    }
}
