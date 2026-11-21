package dev.starbridge.qa.habitat;

import dev.starbridge.Starbridge;
import dev.starbridge.client.ChartScreen;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;

/** Material-only setup. Use unlocked recipes, take their actual output, place the helm and link the terminal. */
final class FirstCraft {
    private static final BlockPos TABLE = new BlockPos(61,149,61), FLOOR = new BlockPos(63,148,63);
    private static int stage, next;
    private FirstCraft() {}

    static boolean tick(Minecraft mc, int tick) throws Exception {
        if (stage >= 19) return true;
        if (tick < next || !mc.level.getBlockState(TABLE).is(Blocks.CRAFTING_TABLE)) return false;
        var inv = mc.player.getInventory();
        switch (stage) {
            case 0 -> {
                if (mc.screen != null) return false;
                use(mc, TABLE, Direction.UP);
            }
            case 1, 3 -> {
                if (!(mc.screen instanceof CraftingScreen)) return false;
                String id = stage == 1 ? "navigation_slate" : "ship_core";
                var recipe = mc.level.getRecipeManager().byKey(new ResourceLocation("starbridge", id)).orElseThrow();
                if (!mc.player.getRecipeBook().contains(recipe)) return false;
                mc.gameMode.handlePlaceRecipe(mc.player.containerMenu.containerId, recipe, false);
                HabitatClient.log("Unlocked vanilla recipe book entry: " + id + "; selected recipe in the crafting table");
            }
            case 2, 4 -> {
                Item item = stage == 2 ? VoyageModule.SLATE.get() : Starbridge.SHIP_CORE_ITEM.get();
                var menu = mc.player.containerMenu;
                if (!(mc.screen instanceof CraftingScreen) || !menu.getSlot(0).getItem().is(item)) return false;
                mc.gameMode.handleInventoryMouseClick(menu.containerId, 0, stage == 2 ? 6 : 7, ClickType.SWAP, mc.player);
            }
            case 5 -> {
                if (!inv.getItem(6).is(VoyageModule.SLATE.get()) || !inv.getItem(7).is(Starbridge.SHIP_CORE_ITEM.get())) return false;
                mc.player.closeContainer();
                inv.selected = 6;
            }
            case 6 -> {
                if (mc.screen != null) return false;
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }
            case 7 -> {
                if (Files.isRegularFile(Path.of("starbridge-field-survey.fixture")) && !FieldCheck.tick(mc, tick)) return false;
                if (!(mc.screen instanceof FirstFlightScreen guide) || !HabitatClient.click(guide,"slate.starbridge.guide.shipyard")) return false;
            }
            case 8 -> {
                if (!(mc.screen instanceof FirstFlightScreen guide)) return false;
                HabitatClient.snap(mc,"00-shipyard-plan");
                if (!HabitatClient.click(guide,"slate.starbridge.guide.elevation")) return false;
            }
            case 9 -> {
                if (!(mc.screen instanceof FirstFlightScreen guide)) return false;
                HabitatClient.snap(mc,"00-shipyard-elevation");
                if (!HabitatClient.click(guide,"slate.starbridge.guide.plan")) return false;
            }
            case 10 -> {
                if (Files.isRegularFile(Path.of("starbridge-materials.fixture")) && !MaterialsCheck.tick(mc, tick)) return false;
                if (!(mc.screen instanceof FirstFlightScreen guide) || !HabitatClient.click(guide,"slate.starbridge.guide.recipe")) return false;
            }
            case 11 -> {
                if (!(mc.screen instanceof FirstFlightScreen guide)) return false;
                HabitatClient.snap(mc,"00-first-flight-recipe");
                HabitatClient.click(guide,"gui.done");
            }
            case 12 -> {
                if (mc.screen != null) return false;
                inv.selected = 7;
                use(mc, FLOOR, Direction.UP);
            }
            case 13 -> {
                if (!mc.level.getBlockState(FLOOR.above()).is(Starbridge.SHIP_CORE.get())) return false;
                inv.selected = 6;
                use(mc, FLOOR.above(), Direction.UP);
            }
            case 14 -> {
                if (!(mc.screen instanceof ChartScreen chart) || !HabitatClient.click(chart,"slate.starbridge.guide.title")) return false;
            }
            case 15 -> {
                if (!(mc.screen instanceof FirstFlightScreen guide) || !HabitatClient.click(guide,"gui.done") || !(mc.screen instanceof ChartScreen)) return false;
            }
            case 16 -> {
                if (Files.isRegularFile(Path.of("starbridge-supply.fixture")) && !SupplyInputs.tick(mc,tick)) return false;
                if (!(mc.screen instanceof ChartScreen chart)) return false;
                if (!HabitatClient.click(chart,"route.starbridge.moon")) return false;
                if (!HabitatClient.click(chart,"nav.starbridge.kit.title")) return false;
            }
            case 17 -> {
                if (!(mc.screen instanceof dev.starbridge.client.LunarKitScreen equipment)) return false;
                HabitatClient.snap(mc,"00-lunar-equipment");
                if (!HabitatClient.click(equipment,"nav.starbridge.kit.back")) return false;
            }
            case 18 -> {
                if (!(mc.screen instanceof ChartScreen)) return false;
                mc.screen.onClose();
                inv.selected = 0;
                HabitatClient.log("Crafted both items, placed the player's helm, linked the terminal and returned from the guide to the same chart");
                Files.writeString(Path.of("starter-result.json"), "{\"pass\":true,\"cheats\":false,\"recipesUnlocked\":2,\"itemsCrafted\":2,\"visitedBothYardViews\":true,\"visitedLunarKitFromChart\":true,\"guideOpenedFromLinkedHelm\":true}");
            }
            default -> throw new IllegalStateException();
        }
        stage++;
        next = tick + 12;
        return false;
    }

    private static void use(Minecraft mc, BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }
}
