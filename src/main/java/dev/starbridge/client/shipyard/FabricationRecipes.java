package dev.starbridge.client.shipyard;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read the active world's recipes. Retrofit publishes classic workbench recipes as ordinary shaped/shapeless displays. */
final class FabricationRecipes {
    record Page(Item station, String kind, int columns, List<Ingredient> ingredients, ItemStack result) {}
    private final Map<Item, List<Page>> byItem = new HashMap<>();

    FabricationRecipes(Level world) {
        for (var recipe : world.getRecipeManager().getRecipes().stream().sorted(Comparator.comparing(Recipe::getId)).toList()) {
            Item tool;
            String kind;
            int columns;
            if (recipe instanceof ShapedRecipe shaped && shaped.canCraftInDimensions(3, 3)) {
                tool = Items.CRAFTING_TABLE; kind = "shaped"; columns = shaped.getWidth();
            } else if (recipe instanceof ShapelessRecipe) {
                tool = Items.CRAFTING_TABLE; kind = "shapeless"; columns = 3;
            } else if (recipe instanceof AbstractCookingRecipe cooking) {
                tool = cooking.getToastSymbol().getItem(); kind = "cooking"; columns = 1;
            } else if (recipe instanceof StonecutterRecipe) {
                tool = Items.STONECUTTER; kind = "cutting"; columns = 1;
            } else continue;
            var ingredients = recipe.getIngredients();
            var output = recipe.getResultItem(world.registryAccess());
            if (output.isEmpty() || ingredients.isEmpty() || ingredients.size() > 9 || columns < 1 || columns > 3) continue;
            byItem.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                    .add(new Page(tool, kind, columns, List.copyOf(ingredients), output.copy()));
        }
    }

    List<Page> forStack(ItemStack target) {
        if (target.isEmpty()) return List.of();
        return byItem.getOrDefault(target.getItem(), List.of()).stream()
                .filter(page -> ItemStack.isSameItemSameTags(page.result(), target)).toList();
    }
}
