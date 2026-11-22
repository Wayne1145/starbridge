package dev.starbridge.survivalqa;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

/** Place each ingredient in a real workbench and take its result, including legacy item variants. */
final class SquareCraft {
    private static int originalSlot = -1;
    private static boolean recordedGrid;

    private SquareCraft() {}

    private static boolean matches(ItemStack stack, String choices) {
        return !stack.isEmpty() && Arrays.stream(choices.split(",")).anyMatch(id -> ExpeditionClient.matches(stack, id));
    }

    static boolean tick(Minecraft game, String material, String products, int wanted) {
        String[] grid = new String[9];
        Arrays.fill(grid, material);
        return fill(game, grid, products, wanted, true);
    }

    static boolean pattern(Minecraft game, String cells, String products, int wanted) {
        String[] grid = cells.split("\\|", -1);
        if (grid.length != 9) throw new IllegalArgumentException("A workbench pattern needs nine slots");
        return fill(game, grid, products, wanted, false);
    }

    private static boolean fill(Minecraft game, String[] gridItems, String products, int wanted, boolean photograph) {
        if (ExpeditionClient.count(game, products) >= wanted) return true;
        if (!(game.screen instanceof CraftingScreen) || !(game.player.containerMenu instanceof CraftingMenu menu)) {
            ExpeditionClient.open(game, "minecraft:crafting_table");
            return false;
        }
        ItemStack carried = menu.getCarried();
        for (int grid = 1; grid <= 9; grid++) {
            ItemStack ingredient = menu.getSlot(grid).getItem();
            String material = gridItems[grid - 1];
            if (material.equals(".") ? ingredient.isEmpty() : matches(ingredient, material)) continue;
            if (!ingredient.isEmpty()) {
                if (carried.isEmpty()) click(game, menu, grid, 0, ClickType.QUICK_MOVE);
                else returnCarried(game, menu);
                return false;
            }
            if (matches(carried, material)) {
                click(game, menu, grid, 1, ClickType.PICKUP);
            } else if (carried.isEmpty()) {
                for (int slot = 10; slot < menu.slots.size(); slot++) {
                    if (matches(menu.getSlot(slot).getItem(), material)) {
                        originalSlot = slot;
                        click(game, menu, slot, 0, ClickType.PICKUP);
                        break;
                    }
                }
            } else returnCarried(game, menu);
            return false;
        }
        if (!carried.isEmpty()) {
            returnCarried(game, menu);
            return false;
        }
        ItemStack result = menu.getSlot(0).getItem();
        if (photograph && !recordedGrid && !result.isEmpty()) {
            Expedition.log("Manual three-by-three workbench produced " + Expedition.item(result) + " " + result.getTag());
            net.minecraft.client.Screenshot.grab(game.gameDirectory, "survival-tin-workbench.png", game.getMainRenderTarget(), feedback -> {});
            recordedGrid = true;
        }
        if (matches(result, products)) click(game, menu, 0, 0, ClickType.QUICK_MOVE);
        return false;
    }

    private static void returnCarried(Minecraft game, CraftingMenu menu) {
        if (originalSlot >= 10 && originalSlot < menu.slots.size() && menu.getSlot(originalSlot).getItem().isEmpty()) {
            click(game, menu, originalSlot, 0, ClickType.PICKUP);
        } else {
            for (int i = 10; i < menu.slots.size(); i++) {
                if (menu.getSlot(i).getItem().isEmpty()) {
                    click(game, menu, i, 0, ClickType.PICKUP);
                    return;
                }
            }
        }
    }

    private static void click(Minecraft game, CraftingMenu menu, int slot, int button, ClickType action) {
        game.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, action, game.player);
    }
}
