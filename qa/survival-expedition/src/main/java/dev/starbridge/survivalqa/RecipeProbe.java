package dev.starbridge.survivalqa;

import dev.starbridge.compat.rocketry.OriginalFabrication;
import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read the actual server recipe selection using a detached vanilla crafting grid. */
final class RecipeProbe {
    static void run(MinecraftServer server) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(
                "fireResistanceAndStrength",
                fireBricks(server, Potions.FIRE_RESISTANCE, Potions.STRENGTH));
        results.put("twoWaterBottles", fireBricks(server, Potions.WATER, Potions.WATER));
        results.put("twoStrength", fireBricks(server, Potions.STRENGTH, Potions.STRENGTH));
        results.put(
                "strongStrength",
                fireBricks(server, Potions.FIRE_RESISTANCE, Potions.STRONG_STRENGTH));
        Expedition.save("press-layout-probe.json",Map.of(
                "topAlignedRecipeBook",press(server,0,3,Items.PISTON),
                "bottomAlignedOriginal",press(server,3,3,Items.PISTON),
                "oneIronMissing",press(server,3,2,Items.PISTON),
                "stickyPiston",press(server,3,3,Items.STICKY_PISTON)));
        Expedition.save("machine-fabrication-probe.json", Map.of(
                "trackingCircuit", batches(server, "advancedrocketry:circuitic", 1),
                "titaniumPlate", batches(server, "libvulpes:libvulpesproductplate", 7),
                "titaniumIngot", batches(server, "libvulpes:libvulpesproductingot", 7),
                "steelIngot", batches(server, "libvulpes:libvulpesproductingot", 6)));
        Map<String, Object> layouts = new LinkedHashMap<>();
        for (var station : OriginalMachineLayout.Station.values()) {
            layouts.put(station.id().toString(), OriginalMachineLayout.read(station)
                    .map(plan -> Map.<String, Object>of("dimensions", List.of(plan.width(), plan.height(), plan.depth()),
                            "cells", plan.cells().stream().map(cell -> Map.of("x", cell.x(), "y", cell.layer(),
                                    "z", cell.z(), "role", cell.role().name(), "item", cell.item().toString(),
                                    "metadata", cell.metadata())).toList())).orElseGet(Map::of));
        }
        Expedition.save("original-machine-layout-probe.json", layouts);
        Expedition.save("workshop-power-recipes-probe.json", Map.of(
                "fourTinPlates", battery(server, 5, true),
                "threeTinPlates", battery(server, 5, false),
                "wrongPlateVariant", battery(server, 6, true),
                "coalGenerator", generator(server)));
        Expedition.save("shipyard-recipes-probe.json", Map.of(
                "tower", workbench(server, "advancedrocketry:structuretower"),
                "assembler", workbench(server, "advancedrocketry:rocketbuilder"),
                "bridge", workbench(server, "starbridge:ship_core")));
        Expedition.save("shipyard-supply-chain-probe.json", Map.of(
                "arcFurnace", workbench(server, "advancedrocketry:tile.electricarcfurnace"),
                "precisionAssembler", workbench(server, "advancedrocketry:precisionassemblingmachine"),
                "goldDust", supplyRoutes(server, "libvulpes:libvulpesproductdust", 2),
                "aluminiumDust", supplyRoutes(server, "libvulpes:libvulpesproductdust", 4),
                "steelRod", supplyRoutes(server, "libvulpes:libvulpesproductrod", 6),
                "titaniumRod", supplyRoutes(server, "libvulpes:libvulpesproductrod", 7),
                "titaniumGear", supplyRoutes(server, "libvulpes:libvulpesproductgear", 7),
                "controlCircuit", supplyRoutes(server, "advancedrocketry:circuitic", 3),
                "structureBlock", workbench(server, "libvulpes:blockstructureblock"),
                "holdingPart", workbench(server, "advancedrocketry:miscpart", 0)));
        var ironPickaxe = new ItemStack(Items.IRON_PICKAXE);
        var silkPickaxe = ironPickaxe.copy();
        silkPickaxe.enchant(Enchantments.SILK_TOUCH, 1);
        Expedition.save("gold-ore-press-feed-probe.json", Map.of(
                "ironPickaxe", goldOreDrops(server, ironPickaxe),
                "silkTouch", goldOreDrops(server, silkPickaxe)));
        Expedition.save("blast-brick-probe.json", results);
    }

    private static List<String> goldOreDrops(MinecraftServer server, ItemStack tool) {
        return Block.getDrops(Blocks.GOLD_ORE.defaultBlockState(), server.overworld(), BlockPos.ZERO,
                null, null, tool).stream().map(RecipeProbe::describe).toList();
    }

    private static Map<String, Object> supplyRoutes(MinecraftServer server, String item, int metadata) {
        return Map.of("workbench", workbench(server, item, metadata), "machine", batches(server, item, metadata));
    }

    private static List<Map<String, Object>> workbench(MinecraftServer server, String item) {
        return workbench(server, item, 0);
    }

    private static List<Map<String, Object>> workbench(MinecraftServer server, String item, int metadata) {
        return server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(recipe -> {
                    var result = recipe.getResultItem(server.registryAccess());
                    return Expedition.item(result).equals(item)
                            && (result.getTag() == null ? 0 : result.getTag().getInt("LegacyMeta")) == metadata;
                })
                .filter(recipe -> !recipe.getIngredients().isEmpty() && recipe.canCraftInDimensions(3, 3))
                .sorted(Comparator.comparing(recipe -> recipe.getId()))
                .map(recipe -> {
                    var inputs = recipe.getIngredients().stream().map(Ingredient::getItems).toList();
                    int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
                    var grid = emptyGrid();
                    for (int i = 0; i < inputs.size(); i++) {
                        if (inputs.get(i).length > 0) grid.setItem((i / width) * 3 + i % width, inputs.get(i)[0].copy());
                    }
                    return Map.<String, Object>of("id", recipe.getId().toString(), "width", width,
                            "ingredients", inputs.stream().map(choices -> Arrays.stream(choices)
                                    .map(RecipeProbe::describe).toList()).toList(),
                            "displayedOutput", describe(recipe.getResultItem(server.registryAccess())),
                            "actualCraft", match(server, grid));
                }).toList();
    }

    private static String describe(ItemStack stack) {
        return stack.save(new net.minecraft.nbt.CompoundTag()).toString();
    }

    private static List<Map<String, Object>> batches(MinecraftServer server, String result, int metadata) {
        return OriginalFabrication.matching(server, ResourceLocation.parse(result), metadata).stream().map(batch ->
                Map.<String, Object>of("station", Expedition.item(batch.station()),
                        "result", batch.output().save(new net.minecraft.nbt.CompoundTag()).toString(),
                        "inputs", batch.inputs().stream().map(row -> row.stream().map(stack ->
                                stack.save(new net.minecraft.nbt.CompoundTag()).toString()).toList()).toList(),
                        "fluids", batch.fluids(), "ticks", batch.ticks(), "power", batch.power())).toList();
    }

    private static ItemStack original(String id, int metadata) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null) return ItemStack.EMPTY;
        var stack = new ItemStack(item);
        if (metadata != 0) stack.getOrCreateTag().putInt("LegacyMeta", metadata);
        return stack;
    }

    private static Map<String, String> battery(MinecraftServer server, int plateMeta, boolean full) {
        var grid = emptyGrid();
        grid.setItem(1, original("libvulpes:libvulpesproductrod", 1));
        for (int slot : new int[]{3, 5, 6, 8}) {
            if (full || slot != 8) grid.setItem(slot, original("libvulpes:libvulpesproductplate", plateMeta));
        }
        for (int slot : new int[]{4, 7}) grid.setItem(slot, new ItemStack(Items.REDSTONE));
        return match(server, grid);
    }

    private static Map<String, String> generator(MinecraftServer server) {
        var grid = emptyGrid();
        grid.setItem(0, original("libvulpes:battery", 0));
        grid.setItem(1, new ItemStack(Items.FURNACE));
        return match(server, grid);
    }

    private static Map<String, String> fireBricks(
            MinecraftServer server, Potion first, Potion second) {
        var grid = emptyGrid();
        grid.setItem(0, PotionUtils.setPotion(new ItemStack(Items.POTION), first));
        grid.setItem(1, PotionUtils.setPotion(new ItemStack(Items.POTION), second));
        for (int i = 2; i < 6; i++) grid.setItem(i, new ItemStack(Items.BRICKS));
        return match(server,grid);
    }

    private static Map<String,String> match(MinecraftServer server, TransientCraftingContainer grid) {
        return server.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, grid, server.overworld())
                .map(
                        recipe -> {
                            var output = recipe.assemble(grid, server.registryAccess());
                            return Map.of(
                                    "id",
                                    recipe.getId().toString(),
                                    "item",
                                    Expedition.item(output),
                                    "count",
                                    String.valueOf(output.getCount()));
                        })
                .orElseGet(Map::of);
    }

    private static TransientCraftingContainer emptyGrid() {
        var menu =
                new AbstractContainerMenu(null, 0) {
                    @Override
                    public ItemStack quickMoveStack(Player player, int slot) {
                        return ItemStack.EMPTY;
                    }

                    @Override
                    public boolean stillValid(Player player) {
                        return false;
                    }
                };
        return new TransientCraftingContainer(menu, 3, 3);
    }

    private static Map<String,String> press(MinecraftServer server,int offset,int irons,net.minecraft.world.item.Item piston) {
        var grid=emptyGrid();
        grid.setItem(offset+1,new ItemStack(piston));
        for (int i=0;i<irons;i++) grid.setItem(offset+3+i,new ItemStack(Items.IRON_INGOT));
        return match(server,grid);
    }

    private RecipeProbe() {}
}
