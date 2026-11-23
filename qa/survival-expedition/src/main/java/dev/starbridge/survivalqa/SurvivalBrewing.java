package dev.starbridge.survivalqa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;

import java.util.function.Predicate;

/** Brew using the same stand slots and resource consumption as an ordinary survival player. */
final class SurvivalBrewing {
    private static final int FIRST_BOTTLE = 0, BOTTLES = 3, REAGENT = 3, FUEL = 4, INVENTORY_START = 5;

    static boolean brew(Minecraft game, String baseId, String reagentId, String resultId, int requested) {
        var base = BuiltInRegistries.POTION.getOptional(ResourceLocation.parse(baseId)).orElseThrow();
        var result = BuiltInRegistries.POTION.getOptional(ResourceLocation.parse(resultId)).orElseThrow();
        var reagent = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(reagentId)).orElseThrow();
        var input = PotionUtils.setPotion(new ItemStack(Items.POTION), base);
        var output = BrewingRecipeRegistry.getOutput(input, new ItemStack(reagent));
        if (!matches(output, result)) throw new IllegalArgumentException("The running stand cannot brew " + resultId);
        var player = game.player;
        int owned = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (matches(stack, result)) owned += stack.getCount();
        }
        if (owned >= requested) return true;
        if (!(player.containerMenu instanceof BrewingStandMenu stand)) {
            ExpeditionClient.open(game, "minecraft:brewing_stand");
            return false;
        }
        if (stand.getBrewingTicks() > 0) return false;
        for (int slot = FIRST_BOTTLE; slot < BOTTLES; slot++) {
            var stack = stand.getSlot(slot).getItem();
            if (!stack.isEmpty() && !matches(stack, base)) {
                game.gameMode.handleInventoryMouseClick(stand.containerId, slot, 0, ClickType.QUICK_MOVE, player);
                return false;
            }
        }
        if (stand.getFuel() == 0) {
            if (!stand.getSlot(FUEL).hasItem()) putOne(game, stand, FUEL, stack -> stack.is(Items.BLAZE_POWDER));
            return false;
        }
        var currentReagent = stand.getSlot(REAGENT).getItem();
        if (!currentReagent.isEmpty() && !currentReagent.is(reagent)) {
            game.gameMode.handleInventoryMouseClick(stand.containerId, REAGENT, 0, ClickType.QUICK_MOVE, player);
            return false;
        }
        int target = Math.min(BOTTLES, requested - owned);
        for (int slot = FIRST_BOTTLE; slot < target; slot++) {
            if (!stand.getSlot(slot).hasItem()) {
                putOne(game, stand, slot, stack -> matches(stack, base));
                return false;
            }
        }
        if (currentReagent.isEmpty()) putOne(game, stand, REAGENT, stack -> stack.is(reagent));
        return false;
    }

    private static boolean matches(ItemStack stack, Potion recipe) {
        return stack.is(Items.POTION) && PotionUtils.getPotion(stack) == recipe;
    }

    private static void putOne(Minecraft game, BrewingStandMenu menu, int target, Predicate<ItemStack> matches) {
        if (!menu.getCarried().isEmpty() || menu.getSlot(target).hasItem()) return;
        for (int index = INVENTORY_START; index < menu.slots.size(); index++) {
            var stack = menu.getSlot(index).getItem();
            if (!matches.test(stack) || !menu.getSlot(target).mayPlace(stack)) continue;
            int count = stack.getCount();
            game.gameMode.handleInventoryMouseClick(menu.containerId, index, 0, ClickType.PICKUP, game.player);
            game.gameMode.handleInventoryMouseClick(menu.containerId, target, 1, ClickType.PICKUP, game.player);
            if (count > 1) game.gameMode.handleInventoryMouseClick(menu.containerId, index, 0, ClickType.PICKUP, game.player);
            return;
        }
    }

    private SurvivalBrewing() {}
}
