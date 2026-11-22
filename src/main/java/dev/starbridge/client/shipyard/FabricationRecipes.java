package dev.starbridge.client.shipyard;

import dev.starbridge.compat.rocketry.OriginalFabrication;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.crafting.PartialNBTIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read the active world's recipes. Retrofit publishes classic workbench recipes as ordinary shaped/shapeless displays. */
final class FabricationRecipes {
    record Page(Item station, String kind, int columns, List<Ingredient> ingredients, ItemStack result,
                OriginalFabrication.Batch batch, boolean retrofitDisplay) {
        static Page machine(OriginalFabrication.Batch batch) {
            return new Page(batch.station().getItem(), "machine", 3,
                    batch.inputs().stream().map(items -> Ingredient.of(items.stream())).toList(), batch.output(), batch, false);
        }
    }
    private static final ResourceLocation BLAST_BRICKS = new ResourceLocation("starbridge", "original/blast_furnace_bricks");
    private static final Set<ResourceLocation> WORKBENCH_FIXES = Set.of(BLAST_BRICKS,
            new ResourceLocation("starbridge", "original/small_plate_press"));
    private final Map<Item, List<Page>> byItem = new HashMap<>();

    FabricationRecipes(Level world) {
        var manager = world.getRecipeManager();
        var catalogOrder = Comparator.<Recipe<?>, Boolean>comparing(recipe -> !WORKBENCH_FIXES.contains(recipe.getId()))
                .thenComparing(Recipe::getId);
        var repairedBricks = manager.byKey(BLAST_BRICKS).map(fix -> fix.getResultItem(world.registryAccess())).orElse(ItemStack.EMPTY);
        for (var recipe : manager.getRecipes().stream().sorted(catalogOrder).toList()) {
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
            if (isGenericPotionDisplay(recipe, output, repairedBricks)) continue;
            var page = new Page(tool, kind, columns, List.copyOf(ingredients), output.copy(), null,
                    recipe.getId().getNamespace().equals("retrofit"));
            var alternatives = byItem.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>());
            if (page.retrofitDisplay() && alternatives.stream().anyMatch(other -> sameDisplay(page, other))) continue;
            alternatives.add(page);
        }
        if (!repairedBricks.isEmpty()) {
            addBrew(Potions.WATER, Items.NETHER_WART, Potions.AWKWARD);
            addBrew(Potions.AWKWARD, Items.MAGMA_CREAM, Potions.FIRE_RESISTANCE);
            addBrew(Potions.AWKWARD, Items.BLAZE_POWDER, Potions.STRENGTH);
        }
    }

    private void addBrew(Potion base, Item reagent, Potion result) {
        var bottle = PotionUtils.setPotion(new ItemStack(Items.POTION), base);
        var output = BrewingRecipeRegistry.getOutput(bottle, new ItemStack(reagent));
        var expected = PotionUtils.setPotion(new ItemStack(Items.POTION), result);
        if (!ItemStack.isSameItemSameTags(output, expected)) return;
        var inputs = List.of(Ingredient.EMPTY, Ingredient.of(reagent), Ingredient.EMPTY,
                Ingredient.EMPTY, PartialNBTIngredient.of(Items.POTION, bottle.getOrCreateTag()));
        byItem.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                .add(new Page(Items.BREWING_STAND, "brewing", 3, inputs, output, null, false));
    }

    // Some classic aliases publish the same grid twice. Keep different patterns and legacy variants selectable.
    private static boolean sameDisplay(Page first, Page second) {
        if (!second.retrofitDisplay() || first.station() != second.station()
                || !first.kind().equals(second.kind()) || first.columns() != second.columns()
                || !ItemStack.matches(first.result(), second.result())
                || first.ingredients().size() != second.ingredients().size()) return false;
        for (int slot = 0; slot < first.ingredients().size(); slot++) {
            var left = first.ingredients().get(slot).getItems();
            var right = second.ingredients().get(slot).getItems();
            if (left.length != right.length) return false;
            for (int i = 0; i < left.length; i++) {
                if (!ItemStack.matches(left[i], right[i])) return false;
            }
        }
        return true;
    }

    // The classic brick display loses both potion tags. Other packs can add their own valid alternatives.
    private static boolean isGenericPotionDisplay(Recipe<?> recipe, ItemStack output, ItemStack repairedBricks) {
        if (repairedBricks.isEmpty() || !ItemStack.matches(output, repairedBricks)
                || !(recipe instanceof ShapelessRecipe) || !recipe.getId().getNamespace().equals("retrofit")) return false;
        var ingredients = recipe.getIngredients();
        return ingredients.size() == 6 && ingredients.stream().filter(i -> singlePlain(i, Items.POTION)).count() == 2
                && ingredients.stream().filter(i -> singlePlain(i, Items.BRICKS)).count() == 4;
    }

    private static boolean singlePlain(Ingredient ingredient, Item item) {
        var choices = ingredient.getItems();
        return choices.length == 1 && choices[0].is(item) && !choices[0].hasTag();
    }

    List<Page> forStack(ItemStack target) {
        if (target.isEmpty()) return List.of();
        return byItem.getOrDefault(target.getItem(), List.of()).stream()
                .filter(page -> ItemStack.isSameItemSameTags(page.result(), target)).toList();
    }
}
